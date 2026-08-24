package com.veyarc.automation.android

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A deliberately narrow Shizuku UserService shared by the read-only probe and recorder.
 *
 * The service first discovers a touchscreen node with the fixed read-only `getevent -lp` command,
 * then observes only that node with `getevent -lt`. Probe mode reports metadata and counters;
 * recording mode forwards transient frame markers to the already-bound app callback. It never
 * opens a writable input device or calls sendevent/uinput. Encrypted journal persistence remains
 * owned by the normal Veyarc process, not this helper.
 */
class ShizukuTouchProbeUserService : IShizukuTouchProbeService.Stub() {
    private val running = AtomicBoolean(false)
    private val eventCount = AtomicLong(0L)
    private val touchContactCount = AtomicLong(0L)
    private val positionSampleCount = AtomicLong(0L)
    private val frameCount = AtomicLong(0L)
    private val completeGestureCount = AtomicLong(0L)
    private val malformedFrameCount = AtomicLong(0L)
    private val incompleteGestureCount = AtomicLong(0L)
    private val outOfRangeSampleCount = AtomicLong(0L)
    private val stateLock = Any()

    @Volatile
    private var readerProcess: java.lang.Process? = null

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var deviceSummary: String? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var recordingCallback: IShizukuTouchRecordingCallback? = null

    override fun startObservation(durationMillis: Long) {
        startInternal(durationMillis, callback = null)
    }

    override fun startRecording(
        durationMillis: Long,
        callback: IShizukuTouchRecordingCallback,
    ) {
        startInternal(durationMillis, callback)
    }

    private fun startInternal(
        durationMillis: Long,
        callback: IShizukuTouchRecordingCallback?,
    ) {
        val maxDuration = if (callback == null) MAX_PROBE_DURATION_MILLIS else MAX_RECORDING_DURATION_MILLIS
        val duration = durationMillis.coerceIn(MIN_DURATION_MILLIS, maxDuration)
        synchronized(stateLock) {
            if (running.get()) return
            eventCount.set(0L)
            touchContactCount.set(0L)
            positionSampleCount.set(0L)
            frameCount.set(0L)
            completeGestureCount.set(0L)
            malformedFrameCount.set(0L)
            incompleteGestureCount.set(0L)
            outOfRangeSampleCount.set(0L)
            deviceSummary = null
            lastError = null
            recordingCallback = callback
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

    override fun getDeviceSummary(): String? = deviceSummary

    override fun getFrameCount(): Long = frameCount.get()

    override fun getCompleteGestureCount(): Long = completeGestureCount.get()

    override fun getMalformedFrameCount(): Long = malformedFrameCount.get()

    override fun getIncompleteGestureCount(): Long = incompleteGestureCount.get()

    override fun getOutOfRangeSampleCount(): Long = outOfRangeSampleCount.get()

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
        var parser: EvdevTouchFrameParser? = null
        try {
            val descriptor = discoverTouchscreen()
            if (descriptor == null || !running.get()) return
            deviceSummary = descriptor.summary()
            recordingCallback?.let { callback ->
                runCatching {
                    callback.onStarted(
                        SystemClock.elapsedRealtimeNanos() / 1_000L,
                        descriptor.summary(),
                        requireNotNull(descriptor.minX),
                        requireNotNull(descriptor.maxX),
                        requireNotNull(descriptor.minY),
                        requireNotNull(descriptor.maxY),
                    )
                }.onFailure { error ->
                    lastError = "Recording callback disconnected: ${error.message ?: error.javaClass.simpleName}"
                    running.set(false)
                }
            }
            if (!running.get()) return
            parser = EvdevTouchFrameParser(descriptor)
            process = ProcessBuilder(
                "/system/bin/getevent",
                "-lt",
                descriptor.path,
            ).redirectErrorStream(true).start()
            synchronized(stateLock) {
                readerProcess = process
            }
            val observedProcess = process
            val observedParser = parser
            reader = thread(start = true, name = "VeyarcShizukuTouchProbeReader") {
                runCatching {
                    observedProcess.inputStream.bufferedReader().forEachLine { line ->
                        if (running.get()) parseEventLine(line, observedParser)
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
            parser?.finish()
            parser?.let(::publishParserCounters)
            val callback = recordingCallback
            val error = lastError
            if (callback != null) {
                if (!error.isNullOrBlank()) {
                    runCatching { callback.onError(error) }
                }
                runCatching { callback.onCompleted() }
            }
            recordingCallback = null
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

    private fun discoverTouchscreen(): TouchscreenDeviceDescriptor? {
        val result = captureCommand(
            args = listOf("/system/bin/getevent", "-lp"),
            timeoutMillis = DISCOVERY_TIMEOUT_MILLIS,
        )
        val selected = TouchscreenDeviceDiscoveryParser.select(result.output)
        if (selected != null) return selected
        if (running.get()) {
            lastError = when {
                result.timedOut -> "Touchscreen evdev discovery timed out."

                else -> diagnosticMessage(result.output)
                    ?: "No touchscreen evdev device with ABS_MT_POSITION_X/Y ranges was discovered."
            }
        }
        return null
    }

    private fun parseEventLine(line: String, parser: EvdevTouchFrameParser) {
        if (line.isBlank()) return
        eventCount.incrementAndGet()
        val event = EvdevLineParser.parse(line)
        if (event == null) {
            if (line.contains("permission denied", ignoreCase = true) ||
                line.contains("could not open", ignoreCase = true)
            ) {
                lastError = line.trim().take(MAX_ERROR_LENGTH)
            }
            return
        }
        if (event.type == "EV_ABS" &&
            (
                event.code == "ABS_MT_POSITION_X" || event.code == "ABS_MT_POSITION_Y" ||
                    event.code == "ABS_X" || event.code == "ABS_Y"
                )
        ) {
            positionSampleCount.incrementAndGet()
        }
        val frame = parser.accept(event)
        if (frame?.kind == TouchFrameKind.DOWN) touchContactCount.incrementAndGet()
        publishParserCounters(parser)
        if (frame != null) {
            val callback = recordingCallback
            if (callback != null) {
                runCatching {
                    callback.onFrame(
                        frame.timestampMicros ?: SystemClock.elapsedRealtimeNanos() / 1_000L,
                        frame.kind.callbackAction,
                        frame.x,
                        frame.y,
                    )
                }.onFailure { error ->
                    lastError = "Recording callback disconnected: ${error.message ?: error.javaClass.simpleName}"
                    running.set(false)
                }
            }
        }
    }

    private val TouchFrameKind.callbackAction: Int
        get() = when (this) {
            TouchFrameKind.DOWN -> 0
            TouchFrameKind.MOVE -> 1
            TouchFrameKind.UP -> 2
        }

    private fun publishParserCounters(parser: EvdevTouchFrameParser) {
        val snapshot = parser.snapshot()
        frameCount.set(snapshot.frameCount)
        completeGestureCount.set(snapshot.completeGestureCount)
        malformedFrameCount.set(snapshot.malformedFrameCount)
        incompleteGestureCount.set(snapshot.incompleteGestureCount)
        outOfRangeSampleCount.set(snapshot.outOfRangeSampleCount)
    }

    private fun captureCommand(args: List<String>, timeoutMillis: Long): CommandOutput {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        synchronized(stateLock) { readerProcess = process }
        val output = StringBuilder()
        val reader = thread(start = true, name = "VeyarcShizukuTouchProbeDiscoveryReader") {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        if (output.length < MAX_DISCOVERY_OUTPUT_CHARS) {
                            output.append(line.take(MAX_DISCOVERY_LINE_LENGTH)).append('\n')
                        }
                    }
                }
            }
        }
        var timedOut = false
        try {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (running.get() && !hasExited(process) && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(POLL_MILLIS)
            }
            if (!hasExited(process)) {
                timedOut = running.get()
                process.destroy()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            process.destroy()
            reader.interrupt()
            runCatching { reader.join(READER_JOIN_TIMEOUT_MILLIS) }
            synchronized(stateLock) {
                if (readerProcess === process) readerProcess = null
            }
        }
        return CommandOutput(output.toString(), timedOut)
    }

    private fun hasExited(process: java.lang.Process): Boolean = runCatching {
        process.exitValue()
        true
    }.getOrDefault(false)

    private fun diagnosticMessage(output: String): String? = output.lineSequence()
        .map(String::trim)
        .firstOrNull {
            it.contains("permission denied", ignoreCase = true) ||
                it.contains("could not open", ignoreCase = true) ||
                it.contains("not found", ignoreCase = true)
        }?.take(MAX_ERROR_LENGTH)

    private data class CommandOutput(
        val output: String,
        val timedOut: Boolean,
    )

    private companion object {
        const val MIN_DURATION_MILLIS = 1_000L
        const val MAX_PROBE_DURATION_MILLIS = 30_000L
        const val MAX_RECORDING_DURATION_MILLIS = 5L * 60L * 1_000L
        const val DISCOVERY_TIMEOUT_MILLIS = 2_000L
        const val POLL_MILLIS = 100L
        const val READER_JOIN_TIMEOUT_MILLIS = 500L
        const val MAX_DISCOVERY_OUTPUT_CHARS = 128_000
        const val MAX_DISCOVERY_LINE_LENGTH = 2_000
        const val MAX_ERROR_LENGTH = 240
    }
}
