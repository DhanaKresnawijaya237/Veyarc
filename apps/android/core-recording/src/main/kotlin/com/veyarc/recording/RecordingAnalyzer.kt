package com.veyarc.recording

import com.veyarc.model.PackageName
import com.veyarc.model.ViewportSpec
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Android-provided thresholds and bounded simplification settings used by the recorder. */
data class RecordingClassificationConfig(
    val touchSlopPx: Double,
    val tapTimeoutMillis: Long,
    val longPressTimeoutMillis: Long,
    val minimumWaitMillis: Long = tapTimeoutMillis,
    val pathSimplificationFraction: Double = DEFAULT_PATH_SIMPLIFICATION_FRACTION,
    val maxPathPoints: Int = DEFAULT_MAX_PATH_POINTS,
) {
    init {
        require(touchSlopPx.isFinite() && touchSlopPx > 0.0) {
            "Touch slop must be finite and positive"
        }
        require(tapTimeoutMillis >= 0L) { "Tap timeout must be nonnegative" }
        require(longPressTimeoutMillis > tapTimeoutMillis) {
            "Long-press timeout must be greater than the tap timeout"
        }
        require(minimumWaitMillis > 0L) { "Minimum wait must be positive" }
        require(pathSimplificationFraction.isFinite() && pathSimplificationFraction > 0.0) {
            "Path simplification fraction must be finite and positive"
        }
        require(maxPathPoints >= 2) { "Maximum path points must be at least two" }
    }

    /** Uses the display diagonal while never simplifying below Android's touch slop. */
    fun pathSimplificationTolerancePx(viewport: ViewportSpec): Double {
        val diagonal = hypot(viewport.recordedWidthPx.toDouble(), viewport.recordedHeightPx.toDouble())
        return max(touchSlopPx, diagonal * pathSimplificationFraction)
    }

    companion object {
        const val DEFAULT_PATH_SIMPLIFICATION_FRACTION: Double = 0.0025
        const val DEFAULT_MAX_PATH_POINTS: Int = 128
    }
}

enum class ClassifiedGestureKind {
    TAP,
    HOLD,
    PATH,
}

data class RecordedPoint(
    val xPx: Double,
    val yPx: Double,
) {
    init {
        require(xPx.isFinite() && yPx.isFinite()) { "Recorded point coordinates must be finite" }
        require(xPx >= 0.0 && yPx >= 0.0) { "Recorded point coordinates must be nonnegative" }
    }
}

data class RecordedTimedPoint(
    val point: RecordedPoint,
    val offsetMillis: Long,
) {
    init {
        require(offsetMillis >= 0L) { "Recorded point offset must be nonnegative" }
    }
}

/** A classified single-pointer gesture, still expressed in recorded display pixels. */
data class ClassifiedTouchGesture(
    val startElapsedMillis: Long,
    val durationMillis: Long,
    val kind: ClassifiedGestureKind,
    val points: List<RecordedTimedPoint>,
) {
    init {
        require(startElapsedMillis >= 0L) { "Gesture start must be nonnegative" }
        require(durationMillis > 0L) { "Gesture duration must be positive" }
        require(points.size >= 2) { "A classified gesture must contain at least two points" }
        require(points.first().offsetMillis == 0L) { "A gesture must start at offset zero" }
        require(points.last().offsetMillis == durationMillis) {
            "A gesture must end at its declared duration"
        }
        require(points.zipWithNext().all { (first, second) -> first.offsetMillis < second.offsetMillis }) {
            "Gesture point offsets must be strictly increasing"
        }
    }

    val endElapsedMillis: Long
        get() = startElapsedMillis + durationMillis

    val startPoint: RecordedPoint
        get() = points.first().point
}

sealed interface RecordingAnalysisEvent {
    val elapsedMillis: Long

    data class Gesture(
        val gesture: ClassifiedTouchGesture,
    ) : RecordingAnalysisEvent {
        override val elapsedMillis: Long
            get() = gesture.startElapsedMillis
    }

    data class InferredWait(
        override val elapsedMillis: Long,
        val durationMillis: Long,
    ) : RecordingAnalysisEvent {
        init {
            require(elapsedMillis >= 0L) { "Wait start must be nonnegative" }
            require(durationMillis > 0L) { "Inferred wait must be positive" }
        }

        val endElapsedMillis: Long
            get() = elapsedMillis + durationMillis
    }

    /** A suggestion only; it never becomes a Launch app or condition automatically. */
    data class ForegroundPackageTransition(
        override val elapsedMillis: Long,
        val previousPackage: PackageName?,
        val currentPackage: PackageName?,
    ) : RecordingAnalysisEvent {
        init {
            require(elapsedMillis >= 0L) { "Foreground transition time must be nonnegative" }
            require(previousPackage != currentPackage) {
                "A foreground transition must change the package"
            }
        }
    }
}

data class RecordingAnalysis(
    val header: RecordingJournalHeader,
    val events: List<RecordingAnalysisEvent>,
    /** The first package observation, if the recorder observed one. */
    val initialForegroundPackage: PackageName?,
    /** True when the journal ended with a down/move sequence without a matching up. */
    val incompleteTouchIgnored: Boolean,
) {
    val gestures: List<ClassifiedTouchGesture>
        get() = events.filterIsInstance<RecordingAnalysisEvent.Gesture>().map { it.gesture }

    val inferredWaits: List<RecordingAnalysisEvent.InferredWait>
        get() = events.filterIsInstance<RecordingAnalysisEvent.InferredWait>()

    val foregroundTransitions: List<RecordingAnalysisEvent.ForegroundPackageTransition>
        get() = events.filterIsInstance<RecordingAnalysisEvent.ForegroundPackageTransition>()
}

/** Converts a validated raw journal into reviewable, platform-neutral recording facts. */
object RecordingAnalyzer {
    fun analyze(
        snapshot: RecordingJournalSnapshot,
        config: RecordingClassificationConfig,
    ): RecordingAnalysis {
        val gestures = extractGestures(snapshot.events, snapshot.header.viewport, config)
        val waits = inferWaits(gestures, config.minimumWaitMillis)
        val transitions = inferForegroundTransitions(snapshot.events)
        val events = buildList {
            gestures.forEach { add(RecordingAnalysisEvent.Gesture(it)) }
            waits.forEach { add(it) }
            transitions.forEach { add(it) }
        }.sortedWith(
            compareBy<RecordingAnalysisEvent> { it.elapsedMillis }
                .thenBy { eventOrder(it) },
        )
        return RecordingAnalysis(
            header = snapshot.header,
            events = events,
            initialForegroundPackage = snapshot.events
                .filterIsInstance<RecordingJournalLine.ForegroundPackage>()
                .firstOrNull()
                ?.packageName,
            incompleteTouchIgnored = snapshot.cursor.touchInProgress,
        )
    }

    private fun extractGestures(
        events: List<RecordingJournalLine>,
        viewport: ViewportSpec,
        config: RecordingClassificationConfig,
    ): List<ClassifiedTouchGesture> {
        val gestures = mutableListOf<ClassifiedTouchGesture>()
        val current = mutableListOf<RecordingJournalLine.Touch>()
        events.forEach { event ->
            if (event !is RecordingJournalLine.Touch) return@forEach
            when (event.action) {
                RecordingTouchAction.DOWN -> {
                    require(current.isEmpty()) { "A new gesture started before the previous gesture ended" }
                    current += event
                }

                RecordingTouchAction.MOVE -> {
                    require(current.isNotEmpty()) { "A move was recorded without a gesture start" }
                    current += event
                }

                RecordingTouchAction.UP -> {
                    require(current.isNotEmpty()) { "A gesture ended without a gesture start" }
                    current += event
                    gestures += classifyGesture(current, viewport, config)
                    current.clear()
                }
            }
        }
        // A crash can leave a valid, incomplete tail. Do not invent an UP or classify it.
        return gestures
    }

    private fun classifyGesture(
        samples: List<RecordingJournalLine.Touch>,
        viewport: ViewportSpec,
        config: RecordingClassificationConfig,
    ): ClassifiedTouchGesture {
        val first = samples.first()
        val last = samples.last()
        val rawDurationMillis = (last.elapsedMillis - first.elapsedMillis).coerceAtLeast(0L)
        val durationMillis = rawDurationMillis.coerceAtLeast(1L)
        val start = RecordedPoint(first.xPx, first.yPx)
        val maxTravelPx = samples.maxOf { sample -> distance(start, RecordedPoint(sample.xPx, sample.yPx)) }
        val kind = when {
            maxTravelPx > config.touchSlopPx -> ClassifiedGestureKind.PATH
            rawDurationMillis >= config.longPressTimeoutMillis -> ClassifiedGestureKind.HOLD
            else -> ClassifiedGestureKind.TAP
        }
        val points = when (kind) {
            ClassifiedGestureKind.PATH -> simplifyPath(
                samples = samples,
                durationMillis = durationMillis,
                tolerancePx = config.pathSimplificationTolerancePx(viewport),
                maxPathPoints = config.maxPathPoints,
            )

            ClassifiedGestureKind.TAP,
            ClassifiedGestureKind.HOLD,
            -> listOf(
                RecordedTimedPoint(start, 0L),
                RecordedTimedPoint(RecordedPoint(last.xPx, last.yPx), durationMillis),
            )
        }
        return ClassifiedTouchGesture(
            startElapsedMillis = first.elapsedMillis,
            durationMillis = durationMillis,
            kind = kind,
            points = points,
        )
    }

    private fun simplifyPath(
        samples: List<RecordingJournalLine.Touch>,
        durationMillis: Long,
        tolerancePx: Double,
        maxPathPoints: Int,
    ): List<RecordedTimedPoint> {
        val rawPoints = samples.map { sample ->
            RawTimedPoint(
                point = RecordedPoint(sample.xPx, sample.yPx),
                elapsedMillis = sample.elapsedMillis,
            )
        }
        val timedPoints = collapseDuplicateTimestamps(rawPoints)
        val simplified = ramerDouglasPeucker(timedPoints, tolerancePx)
        val bounded = limitPointCount(simplified, maxPathPoints)
        return if (rawPoints.first().elapsedMillis == rawPoints.last().elapsedMillis) {
            bounded.mapIndexed { index, point ->
                val offset = if (bounded.size == 1) 0L else (index.toLong() * durationMillis) / (bounded.size - 1)
                RecordedTimedPoint(point.point, offset)
            }
        } else {
            val startMillis = rawPoints.first().elapsedMillis
            bounded.map { point ->
                RecordedTimedPoint(
                    point = point.point,
                    offsetMillis = ((point.elapsedMillis - startMillis).coerceAtLeast(0L))
                        .coerceAtMost(durationMillis),
                )
            }.let(::makeOffsetsStrict)
        }
    }

    private fun collapseDuplicateTimestamps(points: List<RawTimedPoint>): List<RawTimedPoint> {
        if (points.size <= 2) return points
        val result = mutableListOf<RawTimedPoint>()
        var index = 0
        while (index < points.size) {
            val timestamp = points[index].elapsedMillis
            var end = index + 1
            while (end < points.size && points[end].elapsedMillis == timestamp) end += 1
            result += when {
                index == 0 -> points[index]
                end == points.size -> points[end - 1]
                else -> points[end - 1]
            }
            index = end
        }
        if (result.first() != points.first()) result.add(0, points.first())
        if (result.last() != points.last()) result += points.last()
        return result
    }

    private fun makeOffsetsStrict(points: List<RecordedTimedPoint>): List<RecordedTimedPoint> {
        if (points.size <= 1) return points
        val result = ArrayList<RecordedTimedPoint>(points.size)
        var previous = -1L
        points.forEachIndexed { index, point ->
            val offset = when {
                index == 0 -> 0L
                index == points.lastIndex -> max(point.offsetMillis, previous + 1L)
                else -> max(point.offsetMillis, previous + 1L)
            }
            result += point.copy(offsetMillis = offset)
            previous = offset
        }
        val declaredDuration = points.last().offsetMillis
        if (result.last().offsetMillis == declaredDuration) return result
        val finalOffset = result.last().offsetMillis
        if (finalOffset <= 0L) {
            return result.mapIndexed { index, point ->
                point.copy(offsetMillis = (index.toLong() * max(1L, declaredDuration)) / points.lastIndex)
            }
        }
        return result
    }

    private fun ramerDouglasPeucker(points: List<RawTimedPoint>, tolerancePx: Double): List<RawTimedPoint> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        simplifyRange(points, 0, points.lastIndex, tolerancePx, keep)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun simplifyRange(
        points: List<RawTimedPoint>,
        startIndex: Int,
        endIndex: Int,
        tolerancePx: Double,
        keep: BooleanArray,
    ) {
        if (endIndex - startIndex <= 1) return
        val start = points[startIndex].point
        val end = points[endIndex].point
        var farthestIndex = -1
        var farthestDistance = tolerancePx
        for (index in (startIndex + 1) until endIndex) {
            val distance = distanceToSegment(points[index].point, start, end)
            if (distance > farthestDistance) {
                farthestDistance = distance
                farthestIndex = index
            }
        }
        if (farthestIndex >= 0) {
            keep[farthestIndex] = true
            simplifyRange(points, startIndex, farthestIndex, tolerancePx, keep)
            simplifyRange(points, farthestIndex, endIndex, tolerancePx, keep)
        }
    }

    private fun limitPointCount(points: List<RawTimedPoint>, maximum: Int): List<RawTimedPoint> {
        if (points.size <= maximum) return points
        val lastIndex = points.lastIndex
        return (0 until maximum).map { outputIndex ->
            val sourceIndex = (outputIndex.toDouble() * lastIndex / (maximum - 1)).roundToInt()
            points[sourceIndex]
        }.distinctBy { it.elapsedMillis }
            .let { bounded ->
                if (bounded.last() == points.last()) bounded else bounded.dropLast(1) + points.last()
            }
    }

    private fun inferWaits(
        gestures: List<ClassifiedTouchGesture>,
        minimumWaitMillis: Long,
    ): List<RecordingAnalysisEvent.InferredWait> = gestures.zipWithNext()
        .mapNotNull { (previous, next) ->
            val gap = next.startElapsedMillis - previous.endElapsedMillis
            gap.takeIf { it >= minimumWaitMillis }
                ?.let { RecordingAnalysisEvent.InferredWait(previous.endElapsedMillis, it) }
        }

    private fun inferForegroundTransitions(
        events: List<RecordingJournalLine>,
    ): List<RecordingAnalysisEvent.ForegroundPackageTransition> {
        var previous: PackageName? = null
        var hasPrevious = false
        return buildList {
            events.filterIsInstance<RecordingJournalLine.ForegroundPackage>().forEach { event ->
                if (!hasPrevious || event.packageName != previous) {
                    if (hasPrevious) {
                        add(
                            RecordingAnalysisEvent.ForegroundPackageTransition(
                                elapsedMillis = event.elapsedMillis,
                                previousPackage = previous,
                                currentPackage = event.packageName,
                            ),
                        )
                    }
                    previous = event.packageName
                    hasPrevious = true
                }
            }
        }
    }

    private fun eventOrder(event: RecordingAnalysisEvent): Int = when (event) {
        is RecordingAnalysisEvent.ForegroundPackageTransition -> 0
        is RecordingAnalysisEvent.InferredWait -> 1
        is RecordingAnalysisEvent.Gesture -> 2
    }

    private fun distance(first: RecordedPoint, second: RecordedPoint): Double = hypot(first.xPx - second.xPx, first.yPx - second.yPx)

    private fun distanceToSegment(point: RecordedPoint, start: RecordedPoint, end: RecordedPoint): Double {
        val dx = end.xPx - start.xPx
        val dy = end.yPx - start.yPx
        if (dx == 0.0 && dy == 0.0) return distance(point, start)
        val projection = ((point.xPx - start.xPx) * dx + (point.yPx - start.yPx) * dy) / (dx * dx + dy * dy)
        val clamped = projection.coerceIn(0.0, 1.0)
        val closest = RecordedPoint(start.xPx + clamped * dx, start.yPx + clamped * dy)
        return distance(point, closest)
    }

    private data class RawTimedPoint(
        val point: RecordedPoint,
        val elapsedMillis: Long,
    )
}
