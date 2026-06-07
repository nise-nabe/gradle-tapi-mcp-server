package com.example.gradle.mcp

import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal class EofSignalingInputStream(
    private val delegate: InputStream,
    private val transportClosed: CountDownLatch,
) : InputStream() {
    private val signaled = AtomicBoolean(false)

    override fun read(): Int {
        val value = delegate.read()
        if (value == -1) {
            signalClosed()
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = delegate.read(buffer, offset, length)
        if (read == -1) {
            signalClosed()
        }
        return read
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            signalClosed()
        }
    }

    private fun signalClosed() {
        if (signaled.compareAndSet(false, true)) {
            transportClosed.countDown()
        }
    }
}
