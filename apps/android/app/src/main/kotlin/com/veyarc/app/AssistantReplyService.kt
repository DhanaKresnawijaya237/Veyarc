package com.veyarc.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.veyarc.feature.assistant.PreviewReplyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Only a user-sent chat request starts this service. Never restarts or resends a paid request. */
class AssistantReplyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val assistant get() = (application as VeyarcApplication).editor.assistant
    private var token: String? = null
    private var finish: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "AI replies", NotificationManager.IMPORTANCE_LOW))
        // Promote even if a very fast cancellation has already finished the request.
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setAction(OPEN_CHAT).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = stopAction(assistant.activeRequestId)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_assistant_reply)
            .setContentTitle("AI is replying")
            .setContentText("You can leave Veyarc while it finishes.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: RuntimeException) {
            assistant.stop(PreviewReplyStatus.INTERRUPTED)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val received = intent?.getStringExtra(TOKEN)
        if (intent?.action == STOP) {
            if (received != null && received == assistant.activeRequestId) assistant.stop()
        } else {
            token = received
            // Reuse the notification action but replace its token for the latest request.
            stopAction(token)
        }
        finish?.cancel()
        val owned = token
        finish = scope.launch {
            // Keep protection until the final reply/status has been durably stored.
            snapshotFlow { (assistant.activeRequestId == owned && owned != null) || assistant.pendingWrites > 0 }
                .first { !it }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        assistant.stop(PreviewReplyStatus.INTERRUPTED)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (token != null && assistant.activeRequestId == token) assistant.stop(PreviewReplyStatus.INTERRUPTED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopAction(token: String?) = PendingIntent.getService(this, 0, Intent(this, AssistantReplyService::class.java).setAction(STOP).putExtra(TOKEN, token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val OPEN_CHAT = "com.veyarc.app.action.OPEN_AI_CHAT"
        internal const val STOP = "com.veyarc.app.action.STOP_AI_REPLY"
        internal const val TOKEN = "request_token"
        private const val CHANNEL = "ai_replies"
        private const val NOTIFICATION = 401

        fun start(context: Context, token: String) {
            ContextCompat.startForegroundService(context, Intent(context, AssistantReplyService::class.java).putExtra(TOKEN, token))
        }
    }
}
