package com.example.gradle.mcp.build

import com.example.gradle.mcp.cache.GradlePropertiesParser
import com.example.gradle.mcp.protocol.OutputNormalizer
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class TailCapturingStream(
    private val maxRetainedChars: Int = DEFAULT_MAX_RETAINED_CHARS,
) {
    private val lock = Any()
    private val buffer = StringBuilder()
    private var pendingBytes = ByteArray(0)
    private var totalChars = 0

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) {
            return
        }
        synchronized(lock) {
            val incoming = bytes.copyOfRange(offset, offset + length)
            val combined = if (pendingBytes.isEmpty()) incoming else pendingBytes + incoming
            val completeLength = utf8CompletePrefixLength(combined)
            if (completeLength > 0) {
                val decoded = String(combined, 0, completeLength, StandardCharsets.UTF_8)
                appendNormalizedText(decoded)
            }
            pendingBytes = if (completeLength < combined.size) {
                combined.copyOfRange(completeLength, combined.size)
            } else {
                ByteArray(0)
            }
        }
    }

    fun snapshot(): CapturedStreamSnapshot =
        synchronized(lock) {
            CapturedStreamSnapshot(text = buffer.toString(), totalChars = totalChars)
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
        trimToRetainedLimit()
    }

    private fun normalizeChunkPreservingTrailingCr(text: String): String {
        if (text.endsWith('\r') && !text.endsWith("\r\n")) {
            val prefix = text.dropLast(1)
            return OutputNormalizer.normalizeNewlines(prefix) + "\r"
        }
        return OutputNormalizer.normalizeNewlines(text)
    }

    private fun trimToRetainedLimit() {
        if (buffer.length <= maxRetainedChars) {
            return
        }
        val codePointCount = buffer.codePointCount(0, buffer.length)
        if (codePointCount <= maxRetainedChars) {
            return
        }
        val startIndex = buffer.offsetByCodePoints(buffer.length, -maxRetainedChars)
        buffer.delete(0, startIndex)
    }

    private fun utf8CompletePrefixLength(bytes: ByteArray): Int {
        var index = 0
        while (index < bytes.size) {
            val sequenceLength = utf8SequenceLength(bytes[index])
            if (sequenceLength <= 0 || index + sequenceLength > bytes.size) {
                return index
            }
            index += sequenceLength
        }
        return bytes.size
    }

    private fun utf8SequenceLength(firstByte: Byte): Int {
        val byte = firstByte.toInt() and 0xFF
        return when {
            byte and 0x80 == 0 -> 1
            byte and 0xE0 == 0xC0 -> 2
            byte and 0xF0 == 0xE0 -> 3
            byte and 0xF8 == 0xF0 -> 4
            else -> 0
        }
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
    private var pendingBytes = ByteArray(0)
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
                    val incoming = bytes.copyOfRange(offset, offset + length)
                    val combined = if (pendingBytes.isEmpty()) incoming else pendingBytes + incoming
                    val completeLength = Utf8ByteDecoder.completePrefixLength(combined)
                    if (completeLength > 0) {
                        appendText(String(combined, 0, completeLength, StandardCharsets.UTF_8))
                    }
                    pendingBytes = if (completeLength < combined.size) {
                        combined.copyOfRange(completeLength, combined.size)
                    } else {
                        ByteArray(0)
                    }
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
        if (lineBuffer.isNotEmpty()) {
            storeLine(lineBuffer.toString())
            lineBuffer.clear()
        }
        if (pendingBytes.isNotEmpty()) {
            appendText(String(pendingBytes, StandardCharsets.UTF_8))
            pendingBytes = ByteArray(0)
            flushLineBuffer()
        }
    }

    private fun storeLine(line: String) {
        val parsed = GradlePropertiesParser.parsePropertyLine(line) ?: return
        if (retainKey(parsed.first)) {
            properties[parsed.first] = parsed.second
        }
    }
}

private object Utf8ByteDecoder {
    fun completePrefixLength(bytes: ByteArray): Int {
        var index = 0
        while (index < bytes.size) {
            val sequenceLength = sequenceLength(bytes[index])
            if (sequenceLength <= 0 || index + sequenceLength > bytes.size) {
                return index
            }
            index += sequenceLength
        }
        return bytes.size
    }

    private fun sequenceLength(firstByte: Byte): Int {
        val byte = firstByte.toInt() and 0xFF
        return when {
            byte and 0x80 == 0 -> 1
            byte and 0xE0 == 0xC0 -> 2
            byte and 0xF0 == 0xE0 -> 3
            byte and 0xF8 == 0xF0 -> 4
            else -> 0
        }
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

    internal fun appendStdoutForTests(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        stdoutCapture.append(bytes, 0, bytes.size)
    }

    fun applyTo(launcher: org.gradle.tooling.ConfigurableLauncher<*>) {
        launcher.setStandardOutput(PrintStream(TailOutputStream(stdoutCapture), true, StandardCharsets.UTF_8))
        launcher.setStandardError(PrintStream(TailOutputStream(stderrCapture), true, StandardCharsets.UTF_8))
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
    }
}
