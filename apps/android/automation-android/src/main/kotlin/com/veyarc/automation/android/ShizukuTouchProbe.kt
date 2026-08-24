package com.veyarc.automation.android

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.veyarc.automation.android.IShizukuTouchProbeService.Stub
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class ShizukuTouchProbeSnapshot(
    val binderAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val serverUid: Int? = null,
    val serverSelinuxContext: String? = null,
    val serviceConnected: Boolean = false,
    val running: Boolean = false,
    val eventCount: Long = 0L,
    val touchContactCount: Long = 0L,
    val positionSampleCount: Long = 0L,
    val remoteProcessUid: Int? = null,
    val message: String = "Shizuku is not connected.",
)

/**
 * Normal-app side of the read-only Shizuku feasibility test.
 *
 * This class intentionally does not expose a general command runner. The only remote operation
 * available in this spike is the fixed-duration touchscreen counter in ShizukuTouchProbeUserService.
 */
class ShizukuTouchProbe(
    context: Context,
) : Closeable {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(applicationContext.packageName, ShizukuTouchProbeUserService::class.java.name),
    ).daemon(false)
        .tag("veyarc-touch-probe")
        .version(1)
        .processNameSuffix("touch-probe")

    @Volatile
    private var currentSnapshot = ShizukuTouchProbeSnapshot()

    @Volatile
    private var callback: ((ShizukuTouchProbeSnapshot) -> Unit)? = null

    @Volatile
    private var remoteService: IShizukuTouchProbeService? = null

    @Volatile
    private var pollTask: ScheduledFuture<*>? = null

    @Volatile
    private var requestedDurationMillis = DEFAULT_DURATION_MILLIS

    @Volatile
    private var closed = false

    @Volatile
    private var expectedServiceDisconnect = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        publish(
            currentSnapshot.copy(
                binderAvailable = false,
                permissionGranted = false,
                serverUid = null,
                serverSelinuxContext = null,
                serviceConnected = false,
                running = false,
                message = "Shizuku stopped. Start it again, then refresh.",
            ),
        )
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            refresh(messageOverride = "Shizuku permission granted.")
        } else {
            publish(currentSnapshot.copy(message = "Shizuku permission was not granted."))
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            val service = Stub.asInterface(binder)
            remoteService = service
            try {
                service.startObservation(requestedDurationMillis)
                publish(
                    currentSnapshot.copy(
                        serviceConnected = true,
                        running = true,
                        remoteProcessUid = runCatching { service.getProcessUid() }.getOrNull(),
                        message = "Listening to the touchscreen read-only for 30 seconds. Leave Veyarc and use a third-party app.",
                    ),
                )
                startPolling()
            } catch (error: Throwable) {
                finishService("Shizuku could not start the read-only probe: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remoteService = null
            stopPolling()
            val expected = expectedServiceDisconnect
            expectedServiceDisconnect = false
            if (expected) {
                publish(currentSnapshot.copy(serviceConnected = false, running = false))
            } else {
                publish(
                    currentSnapshot.copy(
                        serviceConnected = false,
                        running = false,
                        message = "The Shizuku probe service disconnected before the test completed.",
                    ),
                )
            }
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh()
    }

    fun snapshot(): ShizukuTouchProbeSnapshot = currentSnapshot

    fun setCallback(callback: ((ShizukuTouchProbeSnapshot) -> Unit)?) {
        this.callback = callback
        callback?.let { listener -> mainHandler.post { listener(currentSnapshot) } }
    }

    fun refresh(messageOverride: String? = null) {
        if (closed) return
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAvailable) {
            publish(
                currentSnapshot.copy(
                    binderAvailable = false,
                    permissionGranted = false,
                    serverUid = null,
                    serverSelinuxContext = null,
                    message = messageOverride ?: "Shizuku is not running or its binder is not ready.",
                ),
            )
            return
        }

        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val serverUid = runCatching { Shizuku.getUid() }.getOrNull()
        val serverSelinuxContext = runCatching { Shizuku.getSELinuxContext() }.getOrNull()
        publish(
            currentSnapshot.copy(
                binderAvailable = true,
                permissionGranted = permissionGranted,
                serverUid = serverUid,
                serverSelinuxContext = serverSelinuxContext,
                message = messageOverride ?: if (permissionGranted) {
                    "Shizuku is ready for the read-only probe."
                } else {
                    "Grant Veyarc permission in Shizuku before starting the probe."
                },
            ),
        )
    }

    fun requestPermission() {
        if (closed) return
        try {
            if (!Shizuku.pingBinder()) {
                refresh("Start Shizuku first, then refresh Veyarc.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refresh("Shizuku permission is already granted.")
                return
            }
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            publish(currentSnapshot.copy(message = "Approve Veyarc in the Shizuku permission prompt."))
        } catch (error: Throwable) {
            publish(currentSnapshot.copy(message = "Could not request Shizuku permission: ${error.message ?: error.javaClass.simpleName}"))
        }
    }

    fun startThirdPartyTest(durationMillis: Long = DEFAULT_DURATION_MILLIS) {
        if (closed || currentSnapshot.running || remoteService != null) return
        refresh()
        val snapshot = currentSnapshot
        if (!snapshot.binderAvailable) {
            publish(snapshot.copy(message = "Start Shizuku first, then refresh Veyarc."))
            return
        }
        if (!snapshot.permissionGranted) {
            publish(snapshot.copy(message = "Grant Veyarc permission in Shizuku first."))
            return
        }
        requestedDurationMillis = durationMillis.coerceIn(1_000L, DEFAULT_DURATION_MILLIS)
        expectedServiceDisconnect = false
        publish(snapshot.copy(message = "Connecting to the Shizuku shell-identity probe…"))
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (error: Throwable) {
            publish(currentSnapshot.copy(message = "Could not start the Shizuku UserService: ${error.message ?: error.javaClass.simpleName}"))
        }
    }

    fun stop() {
        val service = remoteService
        runCatching { service?.stopObservation() }
        finishService("Probe stopped. No input was injected or saved.")
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        callback = null
        executor.shutdownNow()
    }

    private fun startPolling() {
        stopPolling()
        pollTask = executor.scheduleAtFixedRate(::pollRemoteService, 0L, 250L, TimeUnit.MILLISECONDS)
    }

    private fun stopPolling() {
        pollTask?.cancel(false)
        pollTask = null
    }

    private fun pollRemoteService() {
        val service = remoteService ?: return
        try {
            val running = service.isRunning()
            publish(
                currentSnapshot.copy(
                    serviceConnected = true,
                    running = running,
                    eventCount = service.getEventCount(),
                    touchContactCount = service.getTouchContactCount(),
                    positionSampleCount = service.getPositionSampleCount(),
                    remoteProcessUid = service.getProcessUid(),
                    message = if (running) {
                        "Listening. Leave Veyarc, use a third-party app, and return before the timer ends."
                    } else {
                        "Probe completed. No input was injected or saved."
                    },
                ),
            )
            if (!running) finishService(null, keepSnapshot = true)
        } catch (error: Throwable) {
            finishService("The Shizuku probe failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun finishService(message: String?, keepSnapshot: Boolean = false) {
        stopPolling()
        val service = remoteService
        remoteService = null
        runCatching { service?.stopObservation() }
        expectedServiceDisconnect = true
        runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        if (!keepSnapshot) {
            publish(
                currentSnapshot.copy(
                    serviceConnected = false,
                    running = false,
                    message = message ?: currentSnapshot.message,
                ),
            )
        } else {
            publish(currentSnapshot.copy(serviceConnected = false, running = false))
        }
    }

    private fun publish(snapshot: ShizukuTouchProbeSnapshot) {
        if (closed) return
        currentSnapshot = snapshot
        val listener = callback ?: return
        mainHandler.post { if (!closed) listener(snapshot) }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 20_426
        const val DEFAULT_DURATION_MILLIS = 30_000L
    }
}
