package com.veyarc.automation.android

import java.util.Locale

/**
 * A small, allocation-light description of a touchscreen evdev node.
 *
 * The descriptor is diagnostic metadata only. It does not contain any touch samples.
 */
data class TouchscreenDeviceDescriptor(
    val path: String,
    val name: String?,
    val minX: Long?,
    val maxX: Long?,
    val minY: Long?,
    val maxY: Long?,
    val hasMultiTouchAxes: Boolean,
    val hasDirectInputProperty: Boolean,
) {
    val hasCoordinateRange: Boolean
        get() = minX != null && maxX != null && minY != null && maxY != null

    fun summary(): String = buildString {
        append(path)
        name?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        if (hasCoordinateRange) {
            append(" · X ").append(minX).append("..").append(maxX)
            append(" · Y ").append(minY).append("..").append(maxY)
        }
        if (hasDirectInputProperty) append(" · direct")
        if (!hasMultiTouchAxes) append(" · no multi-touch axes reported")
    }
}

/** Parses the bounded text returned by the fixed `getevent -lp` diagnostic command. */
object TouchscreenDeviceDiscoveryParser {
    private val devicePath = Regex("(/dev/input/event\\d+)")
    private val range = Regex("min\\s+(-?\\d+)\\s*,\\s*max\\s+(-?\\d+)", RegexOption.IGNORE_CASE)
    private val numericAxis = Regex("\\b(0035|0036)\\b")

    fun parse(output: String): List<TouchscreenDeviceDescriptor> {
        val devices = mutableListOf<MutableDescriptor>()
        var current: MutableDescriptor? = null

        output.lineSequence().forEach { line ->
            val path = devicePath.find(line)?.groupValues?.getOrNull(1)
            if (path != null && (line.contains("add device", ignoreCase = true) || current == null)) {
                current?.let(devices::add)
                current = MutableDescriptor(path)
                return@forEach
            }

            val descriptor = current ?: return@forEach
            if (line.contains("name:", ignoreCase = true)) {
                descriptor.name = line.substringAfter(':').trim().trim('"')
            }
            if (line.contains("INPUT_PROP_DIRECT", ignoreCase = true)) {
                descriptor.hasDirectInputProperty = true
            }

            val axis = axisFor(line)
            if (axis != null) {
                val bounds = range.find(line)
                val min = bounds?.groupValues?.getOrNull(1)?.toLongOrNull()
                val max = bounds?.groupValues?.getOrNull(2)?.toLongOrNull()
                when (axis) {
                    Axis.X -> {
                        descriptor.minX = min ?: descriptor.minX
                        descriptor.maxX = max ?: descriptor.maxX
                    }

                    Axis.Y -> {
                        descriptor.minY = min ?: descriptor.minY
                        descriptor.maxY = max ?: descriptor.maxY
                    }
                }
                if (line.contains("ABS_MT_POSITION_X") || line.contains("ABS_MT_POSITION_Y") ||
                    numericAxis.containsMatchIn(line.uppercase(Locale.US))
                ) {
                    descriptor.hasMultiTouchAxes = true
                }
            }
        }
        current?.let(devices::add)
        return devices.map(MutableDescriptor::toImmutable)
    }

    fun select(output: String): TouchscreenDeviceDescriptor? = select(parse(output))

    fun select(devices: List<TouchscreenDeviceDescriptor>): TouchscreenDeviceDescriptor? = devices
        .filter { it.hasCoordinateRange && it.hasMultiTouchAxes && it.hasDirectInputProperty }
        .maxWithOrNull(
            compareBy<TouchscreenDeviceDescriptor> { nameScore(it.name) }
                .thenBy { it.path },
        )

    private fun axisFor(line: String): Axis? {
        val normalized = line.uppercase(Locale.US)
        return when {
            normalized.contains("ABS_MT_POSITION_X") || numericAxis.find(normalized)?.groupValues?.get(1) == "0035" -> Axis.X
            normalized.contains("ABS_MT_POSITION_Y") || numericAxis.find(normalized)?.groupValues?.get(1) == "0036" -> Axis.Y
            normalized.matches(Regex(".*\\bABS_X\\b.*")) -> Axis.X
            normalized.matches(Regex(".*\\bABS_Y\\b.*")) -> Axis.Y
            else -> null
        }
    }

    private fun nameScore(name: String?): Int {
        val normalized = name.orEmpty().lowercase(Locale.US)
        return when {
            "touchscreen" in normalized || "touchpanel" in normalized -> 4

            "touch" in normalized -> 3

            "synaptics" in normalized || "goodix" in normalized ||
                "focaltech" in normalized || "novatek" in normalized || "elan" in normalized -> 2

            else -> 0
        }
    }

    private enum class Axis { X, Y }

    private class MutableDescriptor(
        val path: String,
        var name: String? = null,
        var minX: Long? = null,
        var maxX: Long? = null,
        var minY: Long? = null,
        var maxY: Long? = null,
        var hasMultiTouchAxes: Boolean = false,
        var hasDirectInputProperty: Boolean = false,
    ) {
        fun toImmutable() = TouchscreenDeviceDescriptor(
            path = path,
            name = name,
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            hasMultiTouchAxes = hasMultiTouchAxes,
            hasDirectInputProperty = hasDirectInputProperty,
        )
    }
}

data class ParsedEvdevEvent(
    val devicePath: String?,
    val timestampMicros: Long?,
    val type: String,
    val code: String,
    val value: Long,
)

/** Parses one labelled line from the fixed `getevent -lt <device>` stream. */
object EvdevLineParser {
    private val timestamp = Regex("\\[\\s*(\\d+)(?:\\.(\\d+))?\\]")
    private val devicePath = Regex("(/dev/input/event\\d+)")

    fun parse(line: String): ParsedEvdevEvent? {
        val eventStart = line.indexOf("EV_")
        if (eventStart < 0) return null
        val tokens = line.substring(eventStart).trim().split(Regex("\\s+"))
        if (tokens.size < 3) return null
        val value = parseValue(tokens[2]) ?: return null
        return ParsedEvdevEvent(
            devicePath = devicePath.find(line)?.groupValues?.getOrNull(1),
            timestampMicros = timestamp.find(line)?.let { match ->
                val seconds = match.groupValues[1].toLongOrNull() ?: return@let null
                val fraction = match.groupValues.getOrNull(2).orEmpty().padEnd(6, '0').take(6)
                seconds * 1_000_000L + (fraction.toLongOrNull() ?: 0L)
            },
            type = tokens[0].uppercase(Locale.US),
            code = tokens[1].uppercase(Locale.US),
            value = value,
        )
    }

    private fun parseValue(token: String): Long? {
        when (token.uppercase(Locale.US)) {
            "DOWN" -> return 1L
            "UP" -> return 0L
            "REPEAT" -> return 2L
        }
        if (token.startsWith('-')) return token.toLongOrNull()
        val unsigned = token.removePrefix("0X").removePrefix("0x").toLongOrNull(16) ?: return null
        return if (unsigned > Int.MAX_VALUE && token.length <= 8) {
            unsigned - 0x1_0000_0000L
        } else {
            unsigned
        }
    }
}

enum class TouchFrameKind { DOWN, MOVE, UP }

data class TouchFrameObservation(
    val kind: TouchFrameKind,
    val timestampMicros: Long?,
    val x: Long,
    val y: Long,
)

data class TouchFrameParserSnapshot(
    val frameCount: Long,
    val completeGestureCount: Long,
    val malformedFrameCount: Long,
    val incompleteGestureCount: Long,
    val outOfRangeSampleCount: Long,
)

/**
 * Converts evdev events into complete single-pointer frame markers.
 *
 * Coordinates are held only in memory while the diagnostic is running. The snapshot deliberately
 * contains counters only, so the feasibility probe cannot persist or return a raw trace.
 */
class EvdevTouchFrameParser(
    private val descriptor: TouchscreenDeviceDescriptor,
) {
    private var active = false
    private var hasValidDown = false
    private var currentX: Long? = null
    private var currentY: Long? = null
    private var pendingXChanged = false
    private var pendingYChanged = false
    private var pendingDown = false
    private var pendingUp = false
    private var pendingTrackingStart = false
    private var pendingTrackingEnd = false
    private var frameCount = 0L
    private var completeGestureCount = 0L
    private var malformedFrameCount = 0L
    private var incompleteGestureCount = 0L
    private var outOfRangeSampleCount = 0L

    fun accept(event: ParsedEvdevEvent): TouchFrameObservation? {
        when (event.type to event.code) {
            "EV_ABS" to "ABS_MT_POSITION_X", "EV_ABS" to "ABS_X" -> {
                currentX = event.value
                pendingXChanged = true
                if (!isInRange(event.value, descriptor.minX, descriptor.maxX)) outOfRangeSampleCount++
            }

            "EV_ABS" to "ABS_MT_POSITION_Y", "EV_ABS" to "ABS_Y" -> {
                currentY = event.value
                pendingYChanged = true
                if (!isInRange(event.value, descriptor.minY, descriptor.maxY)) outOfRangeSampleCount++
            }

            "EV_ABS" to "ABS_MT_TRACKING_ID" -> {
                if (event.value < 0L) {
                    pendingTrackingEnd = true
                } else {
                    pendingTrackingStart = true
                }
            }

            "EV_KEY" to "BTN_TOUCH" -> if (event.value > 0L) pendingDown = true else pendingUp = true

            "EV_SYN" to "SYN_REPORT" -> return finishFrame(event.timestampMicros)
        }
        return null
    }

    fun finish() {
        if (active || pendingDown || pendingTrackingStart) {
            incompleteGestureCount++
        }
        active = false
        pendingDown = false
        pendingUp = false
        pendingTrackingStart = false
        pendingTrackingEnd = false
    }

    fun snapshot() = TouchFrameParserSnapshot(
        frameCount = frameCount,
        completeGestureCount = completeGestureCount,
        malformedFrameCount = malformedFrameCount,
        incompleteGestureCount = incompleteGestureCount,
        outOfRangeSampleCount = outOfRangeSampleCount,
    )

    private fun finishFrame(timestampMicros: Long?): TouchFrameObservation? {
        val hasPosition = currentX != null && currentY != null
        val downSignal = pendingDown || pendingTrackingStart
        val upSignal = pendingUp || pendingTrackingEnd
        val observation = when {
            downSignal && upSignal -> malformed()

            downSignal && active -> malformed()

            downSignal && !hasPosition -> {
                incompleteGestureCount++
                malformed()
            }

            downSignal -> {
                active = true
                hasValidDown = true
                frame(TouchFrameKind.DOWN, timestampMicros)
            }

            upSignal && !active -> malformed()

            upSignal && !hasPosition -> malformed()

            upSignal -> {
                active = false
                if (!hasValidDown) {
                    malformed()
                } else {
                    completeGestureCount++
                    frame(TouchFrameKind.UP, timestampMicros)
                }
            }

            active && (pendingXChanged || pendingYChanged) -> {
                if (hasPosition) frame(TouchFrameKind.MOVE, timestampMicros) else malformed()
            }

            else -> null
        }
        pendingXChanged = false
        pendingYChanged = false
        pendingDown = false
        pendingUp = false
        pendingTrackingStart = false
        pendingTrackingEnd = false
        return observation
    }

    private fun frame(kind: TouchFrameKind, timestampMicros: Long?): TouchFrameObservation {
        frameCount++
        return TouchFrameObservation(kind, timestampMicros, currentX ?: 0L, currentY ?: 0L)
    }

    private fun malformed(): Nothing? {
        malformedFrameCount++
        return null
    }

    private fun isInRange(value: Long, min: Long?, max: Long?): Boolean = min == null || max == null || value in min..max
}
