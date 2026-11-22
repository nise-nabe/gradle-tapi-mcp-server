package com.example.gradle.mcp.build

import com.example.gradle.mcp.cache.GradlePropertiesParser
import com.example.gradle.mcp.protocol.OutputNormalizer
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class TailCapturingStream(
    private val maxRetainedChars: Int = DEFAULT_MAX_RETAINED_CHARS,
) {
    private val lock = Any()
    private val buffer = StringBuilder()
    private val decoder = CharsetStreamAccumulator()
    private var totalChars = 0
    private var bufferCodePoints = 0

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) {
            return
        }
        synchronized(lock) {
            decoder.feed(bytes, offset, length, ::appendNormalizedText)
        }
    }

    fun snapshot(): CapturedStreamSnapshot =
        synchronized(lock) {
            CapturedStreamSnapshot(text = retainedText(), totalChars = totalChars)
        }

    /**
     * Marks the stream as complete: decodes any pending UTF-8 bytes and
     * resolves a trailing `\r` that was kept pending for a possible `\r\n`
     * split across appends. Idempotent.
     */
    fun finish() {
        synchronized(lock) {
            decoder.flush(::appendNormalizedText)
            if (buffer.isNotEmpty() && buffer[buffer.length - 1] == '\r') {
                buffer.setCharAt(buffer.length - 1, '\n')
            }
        }
    }

    private fun appendNormalizedText(text: String) {
        var chunk = text
        if (buffer.isNotEmpty() && buffer[buffer.length - 1] == '\r') {
            buffer.deleteCharAt(buffer.length - 1)
            totalChars -= 1
            if (chunk.startsWith("\n")) {
                chunk = chunk.removePrefix("\n")
            }
            buffer.append('\n')
            totalChars += 1
        }
        chunk = normalizeChunkPreservingTrailingCr(chunk)
        totalChars += chunk.length
        buffer.append(chunk)
        bufferCodePoints += chunk.codePointCount(0, chunk.length)
        trimToRetainedLimit()
    }

    private fun normalizeChunkPreservingTrailingCr(text: String): String {
        if (text.endsWith('\r') && !text.endsWith("\r\n")) {
            val prefix = text.dropLast(1)
            return OutputNormalizer.normalizeNewlines(prefix) + "\r"
        }
        return OutputNormalizer.normalizeNewlines(text)
    }

    /**
     * Amortized trimming: the buffer is allowed to overshoot the cap so a
     * steady stream of small writes does not rescan the whole buffer on
     * every append. [snapshot] still returns exactly the last
     * [maxRetainedChars] code points via [retainedText].
     */
    private fun trimToRetainedLimit() {
        if (bufferCodePoints <= maxRetainedChars * 2L) {
            return
        }
        val startIndex = buffer.offsetByCodePoints(buffer.length, -maxRetainedChars)
        bufferCodePoints -= buffer.codePointCount(0, startIndex)
        buffer.delete(0, startIndex)
    }

    private fun retainedText(): String {
        if (bufferCodePoints <= maxRetainedChars) {
            return buffer.toString()
        }
        val startIndex = buffer.offsetByCodePoints(buffer.length, -maxRetainedChars)
        return buffer.substring(startIndex)
    }

    companion object {
        const val DEFAULT_MAX_RETAINED_CHARS = 65_536
    }
}

/**
 * Incrementally parses Gradle `properties` task stdout without tail retention.
 * Only matching keys are stored, so early cache-related lines cannot be dropped.
 */
class GradlePropertiesStreamCapture(
    private val retainKey: (String) -> Boolean = { true },
) {
    private val lock = Any()
    private val properties = linkedMapOf<String, String>()
    private val decoder = CharsetStreamAccumulator()
    private val lineBuffer = StringBuilder()

    fun asOutputStream(): OutputStream =
        object : OutputStream() {
            override fun write(byte: Int) {
                write(byteArrayOf(byte.toByte()), 0, 1)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length <= 0) {
                    return
                }
                synchronized(lock) {
                    decoder.feed(bytes, offset, length, ::appendText)
                }
            }
        }

    fun snapshotProperties(): Map<String, String> =
        synchronized(lock) {
            flushLineBuffer()
            properties.toMap()
        }

    private fun appendText(text: String) {
        lineBuffer.append(OutputNormalizer.normalizeNewlines(text))
        while (true) {
            val newlineIndex = lineBuffer.indexOf('\n')
            if (newlineIndex < 0) {
                return
            }
            val line = lineBuffer.substring(0, newlineIndex)
            lineBuffer.delete(0, newlineIndex + 1)
            storeLine(line)
        }
    }

    private fun flushLineBuffer() {
        decoder.flush(::appendText)
        if (lineBuffer.isNotEmpty()) {
            storeLine(lineBuffer.toString())
            lineBuffer.clear()
        }
    }

    private fun storeLine(line: String) {
        val parsed = GradlePropertiesParser.parsePropertyLine(line) ?: return
        if (retainKey(parsed.first)) {
            properties[parsed.first] = parsed.second
        }
    }
}

/**
 * Incrementally decodes stream bytes with [Charset.defaultCharset] — the same
 * charset the Tooling API client JVM uses to encode characters written to
 * `setStandardOutput` / `setStandardError`. Incomplete trailing sequences are
 * held as pending bytes across writes; malformed input decodes as U+FFFD.
 * Not thread-safe; callers synchronize around [feed] and [flush].
 */
private class CharsetStreamAccumulator(
    private val charset: Charset = Charset.defaultCharset(),
) {
    private val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes = ByteArray(0)
    private val charBuffer = CharBuffer.allocate(CHAR_BUFFER_SIZE)

    fun feed(bytes: ByteArray, offset: Int, length: Int, sink: (String) -> Unit) {
        val combined = ByteArray(pendingBytes.size + length)
        pendingBytes.copyInto(combined, 0, 0, pendingBytes.size)
        bytes.copyInto(combined, pendingBytes.size, offset, offset + length)
        pendingBytes = decode(combined, endOfInput = false, sink)
    }

    /** Decodes any remainder (an incomplete tail decodes as U+FFFD) and resets. */
    fun flush(sink: (String) -> Unit) {
        if (pendingBytes.isEmpty()) {
            return
        }
        pendingBytes = decode(pendingBytes, endOfInput = true, sink)
        drainDecoder(sink)
        decoder.reset()
    }

    /** Returns the bytes the decoder could not consume (incomplete tail). */
    private fun decode(input: ByteArray, endOfInput: Boolean, sink: (String) -> Unit): ByteArray {
        val byteBuffer = ByteBuffer.wrap(input)
        val out = StringBuilder()
        while (true) {
            charBuffer.clear()
            val result = decoder.decode(byteBuffer, charBuffer, endOfInput)
            charBuffer.flip()
            out.append(charBuffer)
            if (!result.isOverflow) {
                break
            }
        }
        if (out.isNotEmpty()) {
            sink(out.toString())
        }
        return input.copyOfRange(byteBuffer.position(), input.size)
    }

    private fun drainDecoder(sink: (String) -> Unit) {
        while (true) {
            charBuffer.clear()
            val result = decoder.flush(charBuffer)
            charBuffer.flip()
            if (charBuffer.hasRemaining()) {
                sink(charBuffer.toString())
            }
            if (!result.isOverflow) {
                return
            }
        }
    }

    private companion object {
        const val CHAR_BUFFER_SIZE = 8192
    }
}

class CapturingStreams(
    maxRetainedChars: Int = TailCapturingStream.DEFAULT_MAX_RETAINED_CHARS,
) {
    private val stdoutCapture = TailCapturingStream(maxRetainedChars)
    private val stderrCapture = TailCapturingStream(maxRetainedChars)

    fun stdoutSnapshot(): CapturedStreamSnapshot = stdoutCapture.snapshot()
    fun stderrSnapshot(): CapturedStreamSnapshot = stderrCapture.snapshot()

    fun stdoutText(): String = stdoutSnapshot().text
    fun stderrText(): String = stderrSnapshot().text

    fun finish() {
        stdoutCapture.finish()
        stderrCapture.finish()
    }

    internal fun appendStdoutForTests(text: String) {
        val bytes = text.toByteArray(Charset.defaultCharset())
        stdoutCapture.append(bytes, 0, bytes.size)
    }

    internal fun appendStderrForTests(text: String) {
        val bytes = text.toByteArray(Charset.defaultCharset())
        stderrCapture.append(bytes, 0, bytes.size)
    }

    fun applyTo(launcher: org.gradle.tooling.ConfigurableLauncher<*>) {
        launcher.setStandardOutput(
            PrintStream(TailOutputStream(stdoutCapture), true, Charset.defaultCharset()),
        )
        launcher.setStandardError(
            PrintStream(TailOutputStream(stderrCapture), true, Charset.defaultCharset()),
        )
    }

    private class TailOutputStream(
        private val capture: TailCapturingStream,
    ) : OutputStream() {
        override fun write(byte: Int) {
            write(byteArrayOf(byte.toByte()), 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            capture.append(bytes, offset, length)
        }

        override fun close() {
            capture.finish()
        }
    }
}
