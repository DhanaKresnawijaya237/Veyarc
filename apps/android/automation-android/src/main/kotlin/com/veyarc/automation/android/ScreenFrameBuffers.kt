package com.veyarc.automation.android

import java.io.Closeable

/** Session-owned, cleared storage. Outstanding frames always keep exclusive ownership. */
internal class ScreenFrameBuffers : Closeable {
    private val idle = ArrayList<ByteArray>(4)
    private var closed = false

    @Synchronized
    fun take(size: Int): ByteArray {
        check(!closed) { "Screen capture buffers are closed" }
        val index = idle.indexOfFirst { it.size == size }
        return if (index == -1) ByteArray(size) else idle.removeAt(index)
    }

    @Synchronized
    fun release(bytes: ByteArray) {
        bytes.fill(0)
        // Large captures still work; only idle storage is bounded.
        if (closed || bytes.size > MAX_RETAINED_BYTES) return
        idle.removeAll { it.size == bytes.size }
        while (idle.isNotEmpty() && (idle.size >= 4 || idle.sumOf { it.size } + bytes.size > MAX_RETAINED_BYTES)) idle.removeAt(0)
        idle.add(bytes)
    }

    @Synchronized
    override fun close() {
        closed = true
        idle.clear()
    }

    private companion object {
        const val MAX_RETAINED_BYTES = 16 * 1024 * 1024
    }
}
