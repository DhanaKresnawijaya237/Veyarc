package com.veyarc.feature.runner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Process-owned notification bridge for the one active workflow run. */
internal object WorkflowRunRuntime {
    @Volatile
    private var activeController: WorkflowRunController? = null

    @Synchronized
    fun register(controller: WorkflowRunController) {
        activeController = controller
    }

    @Synchronized
    fun unregister(controller: WorkflowRunController) {
        if (activeController === controller) activeController = null
    }

    fun pause() {
        activeController?.pause()
    }

    fun resume() {
        activeController?.resume()
    }

    fun stop() {
        activeController?.stop()
    }
}

internal class WorkflowRunPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WorkflowRunRuntime.pause()
    }
}

internal class WorkflowRunResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WorkflowRunRuntime.resume()
    }
}

internal class WorkflowRunStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WorkflowRunRuntime.stop()
    }
}
