package com.example.gradle.mcp.dependency

import java.lang.reflect.Method
import java.nio.ByteBuffer

internal object MmapCleaner {
    private val invokeCleaner: ((ByteBuffer) -> Unit)? = runCatching {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val method: Method = unsafeClass.getMethod("invokeCleaner", ByteBuffer::class.java)
        val handle: (ByteBuffer) -> Unit = { buffer: ByteBuffer ->
            if (buffer.isDirect) {
                method.invoke(unsafe, buffer)
            }
        }
        handle
    }.getOrNull()

    fun clean(buffer: ByteBuffer?) {
        if (buffer != null && buffer.isDirect) {
            runCatching { invokeCleaner?.invoke(buffer) }
        }
    }
}
