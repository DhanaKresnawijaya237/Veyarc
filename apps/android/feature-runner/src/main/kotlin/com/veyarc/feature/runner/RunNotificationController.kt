package com.veyarc.feature.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

    fun showRunning(workflowName: String, completedSteps: Int, totalSteps: Long?) {
        val progress = if (totalSteps != null && totalSteps in 1..Int.MAX_VALUE.toLong()) {
            NotificationProgress(completedSteps.coerceAtMost(totalSteps.toInt()), totalSteps.toInt())
        } else {
            null
        }
        notify(
            title = "Veyarc is running",
            text = if (progress == null) workflowName else "$workflowName (${progress.completed}/${progress.total} steps)",
            ongoing = true,
        )
    }

    fun showTerminal(text: String) {
        notify(title = "Veyarc run finished", text = text, ongoing = false)
    }

    fun dismiss() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun notify(title: String, text: String, ongoing: Boolean) {
        ensureChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            Notification.Builder(appContext)
        }.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
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
    }
}
