package com.example.mcp

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

data class CapturedStreamSnapshot(
    val text: String,
    val totalChars: Int,
)

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
                val text = String(combined, 0, completeLength, StandardCharsets.UTF_8)
                totalChars += text.length
                buffer.append(text)
                trimToRetainedLimit()
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
            val text = OutputNormalizer.normalizeNewlines(buffer.toString())
            CapturedStreamSnapshot(text = text, totalChars = totalChars)
        }

    private fun trimToRetainedLimit() {
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

    private fun utf8SequenceLength(firstByte: Byte): Int =
        when (firstByte.toInt() and 0xF0) {
            0xF0 -> 4
            0xE0 -> 3
            0xC0 -> 2
            else -> if (firstByte.toInt() and 0x80 == 0) 1 else 0
        }

    companion object {
        const val DEFAULT_MAX_RETAINED_CHARS = 65_536
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
