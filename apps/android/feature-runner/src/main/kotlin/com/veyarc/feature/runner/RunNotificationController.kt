package com.veyarc.feature.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Owns the small, ongoing notification that makes an active run discoverable. */
internal class RunNotificationController(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    init {
        ensureChannel()
    }

    fun isReady(): Boolean {
        ensureChannel()
        if (!manager.areNotificationsEnabled()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
    }

    fun showRunning(
        workflowName: String,
        completedSteps: Int,
        totalSteps: Long?,
        paused: Boolean = false,
    ) {
        val progress = if (totalSteps != null && totalSteps in 1..Int.MAX_VALUE.toLong()) {
            NotificationProgress(completedSteps.coerceAtMost(totalSteps.toInt()), totalSteps.toInt())
        } else {
            null
        }
        notify(
            title = "Veyarc is running",
            text = if (progress == null) workflowName else "$workflowName (${progress.completed}/${progress.total} steps)",
            ongoing = true,
            paused = paused,
        )
    }

    fun showTerminal(text: String) {
        notify(title = "Veyarc run finished", text = text, ongoing = false, paused = false)
    }

    fun dismiss() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun notify(title: String, text: String, ongoing: Boolean, paused: Boolean) {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            Notification.Builder(appContext)
        }.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (ongoing) {
            val toggleIntent = Intent(
                appContext,
                if (paused) WorkflowRunResumeReceiver::class.java else WorkflowRunPauseReceiver::class.java,
            )
            builder.addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                PendingIntent.getBroadcast(
                    appContext,
                    if (paused) RESUME_REQUEST_CODE else PAUSE_REQUEST_CODE,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                PendingIntent.getBroadcast(
                    appContext,
                    STOP_REQUEST_CODE,
                    Intent(appContext, WorkflowRunStopReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val notification = builder.build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Workflow runs",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Status and emergency access for active Veyarc workflow runs"
                },
            )
        }
    }

    private data class NotificationProgress(val completed: Int, val total: Int)

    private companion object {
        const val CHANNEL_ID = "workflow-runs"
        const val NOTIFICATION_ID = 2_371
        const val PAUSE_REQUEST_CODE = 2_372
        const val RESUME_REQUEST_CODE = 2_373
        const val STOP_REQUEST_CODE = 2_374
    }
}
