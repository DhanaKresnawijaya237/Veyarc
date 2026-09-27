package com.veyarc.feature.assistant

import okhttp3.Call

/** One attempt only. Stop, owner loss, background, disable, and key changes share cancel().
 * The 4E owner must invalidate its attempt token and cancel this handle; never reuse it. */
class DeepSeekCancellation {
    private var call: Call? = null
    private var cancelled = false
    private var used = false
    private var finished = false

    fun cancel() {
        val active = synchronized(this) {
            if (finished || cancelled) return
            cancelled = true
            call
        }
        active?.cancel()
    }

    internal fun attach(value: Call) = synchronized(this) {
        checkActive()
        require(!used)
        used = true
        call = value
    }

    internal fun checkActive() = synchronized(this) {
        if (cancelled) throw DeepSeekException(DeepSeekFailure.CANCELLED)
    }

    internal fun finish() = synchronized(this) {
        call = null
        finished = true
        checkActive()
    }

    internal fun detach() = synchronized(this) {
        call = null
        finished = true
    }
}
