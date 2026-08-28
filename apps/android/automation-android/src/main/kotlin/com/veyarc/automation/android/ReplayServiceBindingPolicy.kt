package com.veyarc.automation.android

/**
 * Decides whether a helper that connected after the request ended must be disposed immediately.
 *
 * A cancelled or closed client must never leave a late Shizuku UserService binding alive. An
 * active request may keep a binding even when its command list has already been submitted.
 */
internal object ReplayServiceBindingPolicy {
    fun shouldDisposeConnectedService(
        closed: Boolean,
        activeRequest: Boolean,
        hasPendingCommands: Boolean,
    ): Boolean = closed || (!activeRequest && !hasPendingCommands)
}
