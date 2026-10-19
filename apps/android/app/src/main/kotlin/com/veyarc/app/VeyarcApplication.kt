package com.veyarc.app

import android.app.Application

/**
 * Application entry point kept intentionally small while process-owned services are still gated
 * behind explicit user actions.
 */
class VeyarcApplication : Application() {
    internal val editor by lazy { EditorSessionOwner(this) }
    internal val assistantUsage by lazy { com.veyarc.data.storage.AssistantUsageRepository(this) }
}
