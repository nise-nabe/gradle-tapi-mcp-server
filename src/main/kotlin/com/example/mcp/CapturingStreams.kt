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
    private var totalChars = 0

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) {
            return
        }
        synchronized(lock) {
            val text = String(bytes, offset, length, StandardCharsets.UTF_8)
            totalChars += text.length
            buffer.append(text)
            trimToRetainedLimit()
        }
    }

    fun snapshot(): CapturedStreamSnapshot =
        synchronized(lock) {
            CapturedStreamSnapshot(text = buffer.toString(), totalChars = totalChars)
        }

    private fun trimToRetainedLimit() {
        if (buffer.length <= maxRetainedChars) {
            return
        }
        buffer.delete(0, buffer.length - maxRetainedChars)
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
