package com.veyarc.automation.android

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A deliberately narrow Shizuku UserService used only for the Vivo raw-input feasibility spike.
 *
 * The service runs with Shizuku's shell identity, starts the read-only getevent command for the
 * known Vivo V50 touchscreen node, and reports counters rather than returning or persisting raw
 * touch data. It never opens a writable input device and never calls sendevent or uinput.
 */
class ShizukuTouchProbeUserService : IShizukuTouchProbeService.Stub() {
    private val running = AtomicBoolean(false)
    private val eventCount = AtomicLong(0L)
    private val touchContactCount = AtomicLong(0L)
    private val positionSampleCount = AtomicLong(0L)
    private val stateLock = Any()

    @Volatile
    private var readerProcess: java.lang.Process? = null

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var lastError: String? = null

    override fun startObservation(durationMillis: Long) {
        val duration = durationMillis.coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)
        synchronized(stateLock) {
            if (running.get()) return
            eventCount.set(0L)
            touchContactCount.set(0L)
            positionSampleCount.set(0L)
            lastError = null
            running.set(true)
            workerThread = thread(
                start = true,
                name = "VeyarcShizukuTouchProbe",
                block = { observeFor(duration) },
            )
        }
    }

    override fun stopObservation() {
        running.set(false)
        synchronized(stateLock) {
            readerProcess?.destroy()
            workerThread?.interrupt()
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun getEventCount(): Long = eventCount.get()

    override fun getTouchContactCount(): Long = touchContactCount.get()

    override fun getPositionSampleCount(): Long = positionSampleCount.get()

    override fun getProcessUid(): Int = android.os.Process.myUid()

    override fun getLastError(): String? = lastError

    /** Reserved transaction used by Shizuku to tear down a UserService. */
    override fun destroy() {
        stopObservation()
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    private fun observeFor(durationMillis: Long) {
        var process: java.lang.Process? = null
        var reader: Thread? = null
        try {
            process = ProcessBuilder(
                "/system/bin/getevent",
                "-lt",
                TOUCHSCREEN_DEVICE,
            ).redirectErrorStream(true).start()
            synchronized(stateLock) {
                readerProcess = process
            }
            val observedProcess = process
            reader = thread(start = true, name = "VeyarcShizukuTouchProbeReader") {
                runCatching {
                    observedProcess.inputStream.bufferedReader().forEachLine { line ->
                        if (running.get()) parseEventLine(line)
                    }
                }.onFailure { error ->
                    if (running.get()) {
                        lastError = error.message ?: error.javaClass.simpleName
                    }
                }
            }

            val deadline = SystemClock.elapsedRealtime() + durationMillis
            while (running.get() && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(POLL_MILLIS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (running.get()) {
                lastError = error.message ?: error.javaClass.simpleName
            }
        } finally {
            running.set(false)
            process?.destroy()
            reader?.interrupt()
            runCatching { reader?.join(READER_JOIN_TIMEOUT_MILLIS) }
            synchronized(stateLock) {
                if (readerProcess === process) readerProcess = null
                if (workerThread === Thread.currentThread()) workerThread = null
            }
        }
    }

    private fun parseEventLine(line: String) {
        eventCount.incrementAndGet()
        when {
            line.contains("EV_KEY") &&
                line.contains("BTN_TOUCH") &&
                line.contains("DOWN") -> touchContactCount.incrementAndGet()

            line.contains("EV_ABS") &&
                (line.contains("ABS_MT_POSITION_X") || line.contains("ABS_MT_POSITION_Y")) ->
                positionSampleCount.incrementAndGet()

            line.contains("permission denied", ignoreCase = true) ||
                line.contains("could not open", ignoreCase = true) ->
                lastError = line.trim()
        }
    }

    private companion object {
        const val TOUCHSCREEN_DEVICE = "/dev/input/event5"
        const val MIN_DURATION_MILLIS = 1_000L
        const val MAX_DURATION_MILLIS = 30_000L
        const val POLL_MILLIS = 100L
        const val READER_JOIN_TIMEOUT_MILLIS = 500L
    }
}
