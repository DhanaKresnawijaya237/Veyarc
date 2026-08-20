package com.veyarc.automation.android

enum class CapturedTouchAction {
    Down,
    Move,
    Up,
}

data class CapturedTouchSample(
    val action: CapturedTouchAction,
    val elapsedMillis: Long,
    val point: ScreenPoint,
)

data class CapturedTouchGesture(
    val samples: List<CapturedTouchSample>,
) {
    val durationMillis: Long = samples.last().elapsedMillis.coerceAtLeast(1L)
    val points: List<ScreenPoint> = samples.map(CapturedTouchSample::point)
}

internal class CapturedTouchGestureBuilder {
    private val samples = mutableListOf<CapturedTouchSample>()
    private var startedAtMillis: Long? = null

    val hasStarted: Boolean
        get() = samples.isNotEmpty()

    fun append(
        action: CapturedTouchAction,
        eventTimeMillis: Long,
        point: ScreenPoint,
    ): Boolean {
        if (!point.x.isFinite() || !point.y.isFinite() || point.x < 0f || point.y < 0f) {
            return false
        }
        if (samples.isEmpty() && action != CapturedTouchAction.Down) {
            return false
        }
        if (samples.isNotEmpty() && action == CapturedTouchAction.Down) {
            return false
        }
        if (samples.lastOrNull()?.action == CapturedTouchAction.Up) {
            return false
        }

        val origin = startedAtMillis ?: eventTimeMillis.also { startedAtMillis = it }
        samples +=
            CapturedTouchSample(
                action = action,
                elapsedMillis = (eventTimeMillis - origin).coerceAtLeast(0L),
                point = point,
            )
        return true
    }

    fun build(): CapturedTouchGesture? {
        if (samples.firstOrNull()?.action != CapturedTouchAction.Down) return null
        if (samples.lastOrNull()?.action != CapturedTouchAction.Up) return null
        return CapturedTouchGesture(samples.toList())
    }
}
