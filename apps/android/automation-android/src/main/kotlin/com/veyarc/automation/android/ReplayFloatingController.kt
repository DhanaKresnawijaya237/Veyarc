package com.veyarc.automation.android

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** A workflow entry that can be played from the service-owned control center. */
data class ReplayControllerWorkflow(
    val workflowId: String,
    val revision: Int,
    val name: String,
    val updatedAtMs: Long = 0L,
    val isFavorite: Boolean = false,
)

enum class EdgeLatchPlayTarget(val label: String) {
    MOST_RECENTLY_PLAYED("Most recently played"),
    LAST_SAVED("Last saved"),
    LATEST_FAVORITED("Latest favorited"),
}

internal fun selectReplayWorkflow(
    target: EdgeLatchPlayTarget,
    workflows: List<ReplayControllerWorkflow>,
    mostRecentlyPlayed: ReplayControllerWorkflow?,
    mostRecentlyFavorited: ReplayControllerWorkflow?,
): ReplayControllerWorkflow? = when (target) {
    EdgeLatchPlayTarget.MOST_RECENTLY_PLAYED -> mostRecentlyPlayed
    EdgeLatchPlayTarget.LAST_SAVED -> workflows.maxByOrNull(ReplayControllerWorkflow::updatedAtMs)
    EdgeLatchPlayTarget.LATEST_FAVORITED -> mostRecentlyFavorited
}

/** The usable latch edges. Top and bottom are excluded because system gestures obstruct them. */
enum class EdgeLatchSide {
    LEFT,
    RIGHT,
}

/**
 * Converts a drag release into a normalized start position along the selected edge.
 * The latch is centered on the release point, then clamped so its full length stays on-screen.
 */
internal fun edgeLatchOffsetForRelease(
    rawY: Float,
    displayHeightPx: Int,
    latchLengthPx: Int,
    fallback: Float,
): Float {
    val displayLength = displayHeightPx
    val usableLength = (displayLength - latchLengthPx).coerceAtLeast(0)
    if (displayLength <= 0 || latchLengthPx <= 0 || usableLength == 0) {
        return fallback.coerceIn(0f, 1f)
    }
    val startPx = (rawY - latchLengthPx / 2f).coerceIn(0f, usableLength.toFloat())
    return (startPx / usableLength.toFloat()).coerceIn(0f, 1f)
}

/** Corner radii for a tab whose rounded content-facing side meets the adjacent panel. */
internal fun edgeLatchCornerRadii(side: EdgeLatchSide, radiusPx: Float): FloatArray = when (side) {
    // Android's order is top-left, top-right, bottom-right, bottom-left.
    EdgeLatchSide.LEFT -> floatArrayOf(0f, 0f, radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f)

    EdgeLatchSide.RIGHT -> floatArrayOf(radiusPx, radiusPx, 0f, 0f, 0f, 0f, radiusPx, radiusPx)
}

/** Translation that parks the connected drawer and handle at the selected screen edge. */
internal fun edgeDrawerTranslationPx(
    side: EdgeLatchSide,
    panelWidthPx: Int,
    handleWidthPx: Int,
    visibleHandleWidthPx: Int,
): Float {
    val distance = (panelWidthPx + handleWidthPx - visibleHandleWidthPx).coerceAtLeast(0).toFloat()
    return if (side == EdgeLatchSide.LEFT) -distance else distance
}

/**
 * Selects the closest usable horizontal screen edge without ever producing an interior position.
 * The top and bottom gesture regions are deliberately excluded from active placement. Keeping
 * this calculation pure makes the placement rule easy to test without a device window.
 */
internal fun nearestEdgeLatchSide(
    rawX: Float,
    displayWidthPx: Int,
    fallback: EdgeLatchSide,
): EdgeLatchSide {
    if (displayWidthPx <= 0) return fallback
    val x = rawX.coerceIn(0f, displayWidthPx.toFloat())
    return if (x <= displayWidthPx - x) EdgeLatchSide.LEFT else EdgeLatchSide.RIGHT
}

/** Actions exposed while a replay is active or paused. */
data class ReplayControllerActions(
    val onResume: () -> Unit,
    val onStop: () -> Unit,
    val onServiceLost: () -> Unit,
    val onPause: () -> Unit = {},
)

/** Actions exposed by the process-owned recorder runtime. */
data class RecordingControllerActions(
    val onStart: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onStop: () -> Unit,
    val onServiceLost: () -> Unit,
    val onAdd: () -> Unit = {},
    val onSteps: () -> Unit = {},
)

/** The small surface state rendered by the control center. */
enum class ControlCenterRecordingState {
    IDLE,
    RECORDING,
    PAUSED,
}

/** In-process cache populated by the app while its encrypted workflow repository is available. */
object ReplayWorkflowCatalog {
    private val entries = AtomicReference<List<ReplayControllerWorkflow>>(emptyList())
    private val mostRecentlyPlayed = AtomicReference<ReplayControllerWorkflow?>(null)
    private val mostRecentlyFavorited = AtomicReference<ReplayControllerWorkflow?>(null)

    private const val PREFS_NAME = "replay_controller"
    private const val PREF_WORKFLOW_CATALOG = "workflow_catalog"
    private const val PREF_MOST_RECENTLY_PLAYED = "most_recently_played"
    private const val PREF_MOST_RECENTLY_FAVORITED = "most_recently_favorited"
    private const val FIELD_ID = "id"
    private const val FIELD_REVISION = "revision"
    private const val FIELD_NAME = "name"
    private const val FIELD_UPDATED_AT = "updatedAt"
    private const val FIELD_FAVORITE = "favorite"

    /** Updates the replay list and persists only workflow metadata, never workflow bodies. */
    fun replace(
        context: Context,
        workflows: List<ReplayControllerWorkflow>,
        lastPlayed: ReplayControllerWorkflow? = mostRecentlyPlayed.get(),
    ) {
        val bounded = workflows.take(MAX_ENTRIES)
        entries.set(bounded)
        mostRecentlyPlayed.set(lastPlayed)
        val latestFavorite = mostRecentlyFavorited.get()
            ?.let { current -> bounded.firstOrNull { it.workflowId == current.workflowId && it.isFavorite } }
            ?: bounded.filter(ReplayControllerWorkflow::isFavorite).maxByOrNull(ReplayControllerWorkflow::updatedAtMs)
        mostRecentlyFavorited.set(latestFavorite)
        val json = JSONArray()
        bounded.forEach { workflow ->
            json.put(workflow.toJson())
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_WORKFLOW_CATALOG, json.toString())
            .putString(PREF_MOST_RECENTLY_PLAYED, lastPlayed?.toJson()?.toString())
            .putString(PREF_MOST_RECENTLY_FAVORITED, latestFavorite?.toJson()?.toString())
            .apply()
    }

    /** Restores the replay list after the accessibility service process is recreated. */
    fun load(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val restored = preferences.getString(PREF_WORKFLOW_CATALOG, null)?.let { raw ->
            runCatching {
                val json = JSONArray(raw)
                buildList {
                    for (index in 0 until json.length()) {
                        val item = json.optJSONObject(index) ?: continue
                        val workflowId = item.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: continue
                        val revision = item.optInt(FIELD_REVISION, -1).takeIf { it > 0 } ?: continue
                        val name = item.optString(FIELD_NAME).ifBlank { "Workflow $revision" }
                        add(
                            ReplayControllerWorkflow(
                                workflowId = workflowId,
                                revision = revision,
                                name = name,
                                updatedAtMs = item.optLong(FIELD_UPDATED_AT, 0L),
                                isFavorite = item.optBoolean(FIELD_FAVORITE, false),
                            ),
                        )
                    }
                }.take(MAX_ENTRIES)
            }.getOrDefault(emptyList())
        }.orEmpty()
        entries.set(restored)
        mostRecentlyPlayed.set(
            preferences.getString(PREF_MOST_RECENTLY_PLAYED, null)
                ?.let { rawPlayed -> runCatching { JSONObject(rawPlayed).toWorkflow() }.getOrNull() },
        )
        mostRecentlyFavorited.set(
            preferences.getString(PREF_MOST_RECENTLY_FAVORITED, null)
                ?.let { rawFavorite -> runCatching { JSONObject(rawFavorite).toWorkflow() }.getOrNull() }
                ?.let { current -> restored.firstOrNull { it.workflowId == current.workflowId && it.isFavorite } }
                ?: restored.filter(ReplayControllerWorkflow::isFavorite).maxByOrNull(ReplayControllerWorkflow::updatedAtMs),
        )
    }

    fun snapshot(): List<ReplayControllerWorkflow> = entries.get()

    fun selected(target: EdgeLatchPlayTarget): ReplayControllerWorkflow? = selectReplayWorkflow(
        target = target,
        workflows = entries.get(),
        mostRecentlyPlayed = mostRecentlyPlayed.get(),
        mostRecentlyFavorited = mostRecentlyFavorited.get(),
    )

    fun markPlayed(context: Context, workflow: ReplayControllerWorkflow) {
        mostRecentlyPlayed.set(workflow)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_MOST_RECENTLY_PLAYED, workflow.toJson().toString())
            .apply()
    }

    fun markFavorite(context: Context, workflow: ReplayControllerWorkflow, favorite: Boolean) {
        val updated = workflow.copy(isFavorite = favorite)
        if (favorite) {
            mostRecentlyFavorited.set(updated)
        } else if (mostRecentlyFavorited.get()?.workflowId == workflow.workflowId) {
            mostRecentlyFavorited.set(null)
        }
        replace(
            context,
            buildList {
                add(updated)
                entries.get().filterNot { it.workflowId == workflow.workflowId }.forEach(::add)
            },
        )
    }

    /** Adds a newly persisted workflow without exposing its encrypted body to the controller. */
    fun addOrReplace(context: Context, workflow: ReplayControllerWorkflow) {
        val saved = workflow.copy(updatedAtMs = System.currentTimeMillis())
        val next = buildList {
            add(saved)
            entries.get().filterNot { it.workflowId == workflow.workflowId }.forEach(::add)
        }
        replace(context, next)
    }

    private fun ReplayControllerWorkflow.toJson(): JSONObject = JSONObject()
        .put(FIELD_ID, workflowId)
        .put(FIELD_REVISION, revision)
        .put(FIELD_NAME, name)
        .put(FIELD_UPDATED_AT, updatedAtMs)
        .put(FIELD_FAVORITE, isFavorite)

    private fun JSONObject.toWorkflow(): ReplayControllerWorkflow? {
        val workflowId = optString(FIELD_ID).takeIf(String::isNotBlank) ?: return null
        val revision = optInt(FIELD_REVISION, -1).takeIf { it > 0 } ?: return null
        return ReplayControllerWorkflow(
            workflowId = workflowId,
            revision = revision,
            name = optString(FIELD_NAME).ifBlank { "Workflow $revision" },
            updatedAtMs = optLong(FIELD_UPDATED_AT, 0L),
            isFavorite = optBoolean(FIELD_FAVORITE, false),
        )
    }

    private const val MAX_ENTRIES = 20
}

/**
 * Compact edge-latch control center for recording and replay.
 *
 * This is deliberately not an input guard. It owns only a small accessibility
 * window containing a small rectangular latch and, when opened, a compact panel. The latch is
 * always attached to the left or right screen edge; dragging selects the nearest usable side and
 * stores a normalized position along it, so it can never be parked in the content area or gesture
 * navigation edge. It never changes
 * accessibility flags, enables touch exploration, or creates a full-screen input window. While
 * replay is active only the collapsed latch remains visible; a user touch on it cancels replay
 * before the panel can open. Replay callers must preflight their points against
 * [isNormalizedPointOnSurface] so synthetic input cannot be directed at this window.
 */
object ReplayFloatingController {
    const val ACTION_OPEN_WORKFLOW = "com.veyarc.app.action.OPEN_WORKFLOW"
    const val EXTRA_WORKFLOW_ID = "com.veyarc.app.extra.WORKFLOW_ID"
    const val EXTRA_WORKFLOW_REVISION = "com.veyarc.app.extra.WORKFLOW_REVISION"
    const val EXTRA_AUTO_START = "com.veyarc.app.extra.AUTO_START"

    private const val PREFS_NAME = "replay_controller"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_EDGE = "edge"
    private const val PREF_EDGE_OFFSET = "edge_offset"
    private const val PREF_PLAY_TARGET = "play_target"
    private const val PREF_LEGACY_ANCHOR_X = "anchor_x"
    private const val PREF_LEGACY_ANCHOR_Y = "anchor_y"
    private const val LATCH_THICKNESS_DP = 20
    private const val LATCH_VISIBLE_DP = 8
    private const val LATCH_LENGTH_DP = 60
    private const val ICON_SIZE_DP = 44
    private const val PANEL_PADDING_DP = 8
    private const val PANEL_GAP_DP = 5
    private const val PANEL_WIDTH_DP = 184
    private const val SCREEN_MARGIN_DP = 8
    private val DEFAULT_EDGE = EdgeLatchSide.RIGHT
    private const val MAX_PANEL_ACTIONS = 4
    private const val CONTROLLER_RESHOW_DELAY_MILLIS = 500L
    private const val SHOW_TIMEOUT_MILLIS = 300L
    private const val OVERLAY_REMOVAL_RETRY_DELAY_MILLIS = 50L
    private const val MAX_OVERLAY_REMOVAL_RETRIES = 6
    private const val MAIN_TASK_PENDING = 0
    private const val MAIN_TASK_RUNNING = 1
    private const val MAIN_TASK_CANCELLED = 2
    private const val MAIN_TASK_DONE = 3

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var replayActive = false
    private var replayPaused = false
    private var replayStopRequested = false
    private var openAfterReplayStop = false
    private var recordingState = ControlCenterRecordingState.IDLE
    private var replayActions: ReplayControllerActions? = null
    private var recordingActions: RecordingControllerActions? = null
    private var screenshotOwner: Any? = null
    private var edgeLoaded = false
    private var edge = DEFAULT_EDGE
    private var edgeOffset = 0.5f
    private var dragState: DragState? = null

    @Volatile
    private var surfaceBounds: ControlSurfaceBounds? = null

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_ENABLED, false)

    fun playTarget(context: Context): EdgeLatchPlayTarget {
        val saved = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_PLAY_TARGET, null)
        return EdgeLatchPlayTarget.entries.firstOrNull { it.name == saved } ?: EdgeLatchPlayTarget.LAST_SAVED
    }

    fun setPlayTarget(context: Context, target: EdgeLatchPlayTarget) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_PLAY_TARGET, target.name)
            .apply()
        runOnMain { if (!replayActive && !replayPaused) showCurrentInternal() }
    }

    /**
     * Observes controller preference changes made outside the host activity, such as the close
     * action in the accessibility overlay. The returned function must be called when the observer
     * leaves composition.
     */
    fun observeEnabled(context: Context, onChanged: (Boolean) -> Unit): () -> Unit {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == PREF_ENABLED) onChanged(prefs.getBoolean(PREF_ENABLED, false))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onChanged(preferences.getBoolean(PREF_ENABLED, false))
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_ENABLED, enabled)
            .apply()
        ReplayWorkflowCatalog.load(appContext)
        runOnMain {
            expanded = false
            ensureEdgeLoaded(appContext)
            if (enabled) showCurrentInternal() else removeOverlayOrMakePassThrough()
        }
    }

    /** Restores the latch to the default right-edge position without changing enabled state. */
    fun resetPosition(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_EDGE)
            .remove(PREF_EDGE_OFFSET)
            .remove(PREF_LEGACY_ANCHOR_X)
            .remove(PREF_LEGACY_ANCHOR_Y)
            .apply()
        runOnMain {
            edgeLoaded = false
            ensureEdgeLoaded(appContext)
            if (service != null && isEnabledForCurrentService()) showCurrentInternal()
        }
    }

    internal fun attach(accessibilityService: AccessibilityService) {
        service = accessibilityService
        ReplayWorkflowCatalog.load(accessibilityService)
        runOnMain {
            ensureEdgeLoaded(accessibilityService)
            if (service === accessibilityService && isEnabled(accessibilityService)) showCurrentInternal()
        }
    }

    /** Rebuilds the small overlay after a display rotation or density/size change. */
    internal fun onDisplayChanged() {
        runOnMain {
            if (service != null && isEnabledForCurrentService()) showCurrentInternal()
        }
    }

    internal fun detach(accessibilityService: AccessibilityService) {
        if (service !== accessibilityService) return
        runOnMain {
            if (service !== accessibilityService) return@runOnMain
            removeOverlayOrMakePassThrough()
            service = null
            notifyServiceLost()
        }
    }

    /** Removes all controller state during an accessibility-service interruption. */
    internal fun onServiceInterrupted(accessibilityService: AccessibilityService) {
        if (service !== accessibilityService) return
        runOnMain {
            if (service !== accessibilityService) return@runOnMain
            removeOverlayOrMakePassThrough()
            notifyServiceLost()
            mainHandler.postDelayed({
                if (service === accessibilityService && isEnabled(accessibilityService)) {
                    showCurrentInternal()
                }
            }, CONTROLLER_RESHOW_DELAY_MILLIS)
        }
    }

    fun setRecordingActions(actions: RecordingControllerActions) {
        runOnMain {
            recordingActions = actions
            if (!replayActive && !replayPaused) showCurrentInternal()
        }
    }

    fun clearRecordingActions() {
        runOnMain { recordingActions = null }
    }

    /** Hides only our small window for one paused-recording screenshot, without changing settings. */
    fun hideForRecordingScreenshot(owner: Any): Boolean = runOnMainAndWait {
        if (screenshotOwner != null || recordingState != ControlCenterRecordingState.PAUSED) return@runOnMainAndWait false
        screenshotOwner = owner
        if (removeOverlay()) {
            true
        } else {
            screenshotOwner = null
            removeOverlayOrMakePassThrough()
            false
        }
    }

    fun finishRecordingScreenshot(owner: Any, returnToSteps: Boolean) {
        runOnMain {
            if (screenshotOwner !== owner) return@runOnMain
            screenshotOwner = null
            showCurrentInternal()
            if (returnToSteps && recordingState == ControlCenterRecordingState.PAUSED) recordingActions?.onSteps?.invoke()
        }
    }

    fun setRecordingState(state: ControlCenterRecordingState) {
        runOnMain {
            recordingState = state
            if (!replayActive && !replayPaused) showCurrentInternal()
        }
    }

    fun setReplayActions(actions: ReplayControllerActions) {
        runOnMain { replayActions = actions }
    }

    fun clearReplayActions() {
        runOnMain { replayActions = null }
    }

    /**
     * Keeps the collapsed latch visible while dispatching replay. Returns false if the control
     * surface could not be created, so callers can refuse input before dispatch.
     */
    fun showReplay(): Boolean = runOnMainAndWait {
        replayActive = true
        replayPaused = false
        replayStopRequested = false
        openAfterReplayStop = false
        expanded = false
        showCurrentInternal()
    }

    /** Shows the controller after a replay has paused at a safe gesture boundary. */
    fun showPaused(actions: ReplayControllerActions) {
        runOnMain {
            replayActive = false
            replayPaused = true
            replayStopRequested = false
            openAfterReplayStop = false
            expanded = false
            replayActions = actions
            showCurrentInternal()
        }
    }

    /** Returns to the idle controller after completion, cancellation, or refusal. */
    fun showIdle() {
        runOnMain {
            replayActive = false
            replayPaused = false
            replayStopRequested = false
            val expandAfterStop = openAfterReplayStop
            openAfterReplayStop = false
            expanded = expandAfterStop
            showCurrentInternal()
        }
    }

    /** Returns true when a normalized replay/recording point falls inside the current surface. */
    fun isNormalizedPointOnSurface(normalizedX: Double, normalizedY: Double): Boolean {
        val bounds = currentSurfaceBounds() ?: return false
        if (!normalizedX.isFinite() || !normalizedY.isFinite()) return false
        val x = normalizedX * bounds.displayWidthPx
        val y = normalizedY * bounds.displayHeightPx
        return isScreenPointOnSurface(x.toFloat(), y.toFloat())
    }

    /** Returns true when a logical display point falls inside the current control surface. */
    fun isScreenPointOnSurface(x: Float, y: Float): Boolean {
        val bounds = currentSurfaceBounds() ?: return false
        return x.isFinite() && y.isFinite() &&
            x >= bounds.leftPx && x <= bounds.rightPx &&
            y >= bounds.topPx && y <= bounds.bottomPx
    }

    private fun showCurrentInternal(): Boolean {
        val activeService = service ?: return false
        if (screenshotOwner != null) return false
        if (!isEnabledForCurrentService()) {
            removeOverlayOrMakePassThrough()
            return false
        }
        val newView = buildRoot(activeService)
        if (!removeOverlay()) {
            removeOverlayOrMakePassThrough()
            return false
        }
        return addOverlay(newView, activeService)
    }

    @SuppressLint("ClickableViewAccessibility") // Drag handling calls performClick on non-drag UP.
    private fun buildRoot(activeService: AccessibilityService): ControllerRoot {
        ensureEdgeLoaded(activeService)
        val displayMetrics = realDisplayMetrics(activeService)
        val displayWidth = displayMetrics?.widthPixels ?: activeService.resources.displayMetrics.widthPixels
        val displayHeight = displayMetrics?.heightPixels ?: activeService.resources.displayMetrics.heightPixels
        val root = ControllerRoot(activeService)
        val latchWidth = dp(activeService, LATCH_THICKNESS_DP)

        val panel = buildPanel(activeService)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(
                (displayWidth - dp(activeService, SCREEN_MARGIN_DP) * 2).coerceAtLeast(1),
                View.MeasureSpec.AT_MOST,
            ),
            View.MeasureSpec.makeMeasureSpec(
                (displayHeight - dp(activeService, SCREEN_MARGIN_DP) * 2).coerceAtLeast(1),
                View.MeasureSpec.AT_MOST,
            ),
        )
        val panelWidth = panel.measuredWidth
            .coerceAtLeast(dp(activeService, PANEL_WIDTH_DP))
            .coerceAtMost((displayWidth - dp(activeService, SCREEN_MARGIN_DP) * 2).coerceAtLeast(1))
        val panelHeight = panel.measuredHeight.coerceAtLeast(1)
        val latchHeight = panelHeight
        val panelVisible = expanded && !replayActive

        val placement = when (edge) {
            EdgeLatchSide.LEFT -> Placement(
                panelLeft = 0,
                panelTop = 0,
                handleLeft = if (panelVisible) panelWidth else 0,
                handleTop = 0,
            )

            EdgeLatchSide.RIGHT -> Placement(
                panelLeft = latchWidth,
                panelTop = 0,
                handleLeft = 0,
                handleTop = 0,
            )
        }

        root.panel = panel
        root.panelWidthPx = panelWidth
        root.addView(
            panel,
            FrameLayout.LayoutParams(panelWidth, panelHeight).apply {
                leftMargin = placement.panelLeft
                topMargin = placement.panelTop
            },
        )
        panel.visibility = if (panelVisible) View.VISIBLE else View.GONE
        val handle = LatchButton(activeService, edge, latchAccentColor(activeService)).apply {
            setImageDrawable(null)
            contentDescription = when {
                replayActive -> "Stop Veyarc replay"
                replayPaused -> "Open paused Veyarc replay controls"
                recordingState == ControlCenterRecordingState.RECORDING -> "Open Veyarc recording controls"
                recordingState == ControlCenterRecordingState.PAUSED -> "Open paused Veyarc recording controls"
                else -> "Open Veyarc control center"
            }
            background = handleBackground(activeService)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(activeService, 8), dp(activeService, 8), dp(activeService, 8), dp(activeService, 8))
        }
        root.addView(
            handle,
            FrameLayout.LayoutParams(latchWidth, latchHeight).apply {
                leftMargin = placement.handleLeft
                topMargin = placement.handleTop
            },
        )
        root.handleLeftPx = placement.handleLeft
        root.handleTopPx = placement.handleTop
        root.handleWidthPx = latchWidth
        root.handleHeightPx = latchHeight
        root.handle = handle
        handle.setOnClickListener {
            if (!replayActive) {
                setExpandedInternal(root, !expanded)
            }
        }
        handle.setOnTouchListener { view, event -> handleTouch(root, view, event) }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(displayWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(displayHeight, View.MeasureSpec.AT_MOST),
        )
        return root
    }

    private fun setExpandedInternal(root: ControllerRoot, shouldExpand: Boolean) {
        val activeService = service ?: return
        if (overlayView !== root || replayActive || expanded == shouldExpand || root.animating) return
        val panel = root.panel ?: return
        val handle = root.handle ?: return
        root.animating = true
        val translation = edgeDrawerTranslationPx(
            side = edge,
            panelWidthPx = root.panelWidthPx,
            handleWidthPx = root.handleWidthPx,
            visibleHandleWidthPx = dp(activeService, LATCH_VISIBLE_DP),
        )
        if (shouldExpand) {
            expanded = true
            panel.visibility = View.VISIBLE
            if (!updateRootLayout(root, drawerVisible = true)) {
                expanded = false
                panel.visibility = View.GONE
                root.animating = false
                return
            }
            root.translationX = translation
            root.animate().translationX(0f).setDuration(180L).withEndAction {
                root.animating = false
            }.start()
        } else {
            if (!animateRootClosed(root, panel, translation.roundToInt())) {
                expanded = false
                panel.visibility = View.GONE
                root.animating = false
                showCurrentInternal()
            }
        }
    }

    private fun animateRootClosed(root: ControllerRoot, panel: View, distancePx: Int): Boolean {
        val manager = windowManager ?: return false
        val params = layoutParams ?: return false
        val metrics = realDisplayMetrics(service ?: return false) ?: return false
        val startX = params.x
        val animator = ValueAnimator.ofInt(startX, startX + distancePx).apply {
            duration = 160L
            addUpdateListener { animation ->
                if (overlayView !== root) return@addUpdateListener
                params.x = animation.animatedValue as Int
                runCatching {
                    manager.updateViewLayout(root, params)
                    updateSurfaceGeometry(metrics, root)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    root.positionAnimator = null
                    if (!cancelled && overlayView === root) {
                        expanded = false
                        panel.visibility = View.GONE
                        if (!updateRootLayout(root, drawerVisible = false)) showCurrentInternal()
                    }
                    root.animating = false
                }
            })
        }
        root.positionAnimator = animator
        animator.start()
        return true
    }

    private fun updateRootLayout(root: ControllerRoot, drawerVisible: Boolean): Boolean {
        val activeService = service ?: return false
        val manager = windowManager ?: return false
        val params = layoutParams ?: return false
        val metrics = realDisplayMetrics(activeService) ?: return false
        val handle = root.handle ?: return false
        val panel = root.panel ?: return false
        val panelParams = panel.layoutParams as FrameLayout.LayoutParams
        val handleParams = handle.layoutParams as FrameLayout.LayoutParams
        panelParams.leftMargin = if (edge == EdgeLatchSide.RIGHT) root.handleWidthPx else 0
        handleParams.leftMargin = if (edge == EdgeLatchSide.LEFT && drawerVisible) root.panelWidthPx else 0
        panel.layoutParams = panelParams
        handle.layoutParams = handleParams
        root.handleLeftPx = handleParams.leftMargin
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST),
        )
        params.x = when (edge) {
            EdgeLatchSide.LEFT -> if (drawerVisible) 0 else -(root.handleWidthPx - dp(activeService, LATCH_VISIBLE_DP))

            EdgeLatchSide.RIGHT -> if (drawerVisible) {
                metrics.widthPixels - root.measuredWidth
            } else {
                metrics.widthPixels - dp(activeService, LATCH_VISIBLE_DP)
            }
        }
        params.y = params.y.coerceIn(0, (metrics.heightPixels - root.measuredHeight).coerceAtLeast(0))
        return runCatching {
            manager.updateViewLayout(root, params)
            updateSurfaceGeometry(metrics, root)
            true
        }.getOrDefault(false)
    }

    private fun handleTouch(root: ControllerRoot, handle: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && replayActive) {
            if (!replayStopRequested) {
                replayStopRequested = true
                openAfterReplayStop = true
                replayActions?.onStop?.invoke()
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragState = DragState(
                    startRawX = event.rawX,
                    startRawY = event.rawY,
                    touchSlopPx = ViewConfiguration.get(root.context).scaledTouchSlop,
                    startEdgeOffset = edgeOffset,
                )
            }

            MotionEvent.ACTION_MOVE -> {
                val state = dragState ?: return true
                val dx = event.rawX - state.startRawX
                val dy = event.rawY - state.startRawY
                if (!state.dragging && (abs(dx) > state.touchSlopPx || abs(dy) > state.touchSlopPx)) {
                    state.dragging = true
                }
                if (state.dragging) updateCollapsedPosition(root, event.rawY)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                val state = dragState
                dragState = null
                if (state?.dragging == true) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) saveEdgeFromRelease(event.rawX, event.rawY)
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) edgeOffset = state.startEdgeOffset
                    expanded = false
                    showCurrentInternal()
                } else if (event.actionMasked == MotionEvent.ACTION_UP && !replayActive) {
                    handle.performClick()
                }
            }
        }
        return true
    }

    private fun saveEdgeFromRelease(rawX: Float, rawY: Float) {
        val activeService = service ?: return
        val metrics = realDisplayMetrics(activeService) ?: return
        edge = nearestEdgeLatchSide(rawX, metrics.widthPixels, edge)
        edgeOffset = edgeLatchOffsetForRelease(
            rawY = rawY,
            displayHeightPx = metrics.heightPixels,
            latchLengthPx = dp(activeService, LATCH_LENGTH_DP),
            fallback = edgeOffset,
        )
        edgeLoaded = true
        activeService.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_EDGE, edge.name)
            .putFloat(PREF_EDGE_OFFSET, edgeOffset)
            .remove(PREF_LEGACY_ANCHOR_X)
            .remove(PREF_LEGACY_ANCHOR_Y)
            .apply()
    }

    /** Moves only the collapsed latch while the user drags, without rebuilding the touch window. */
    private fun updateCollapsedPosition(root: ControllerRoot, rawY: Float) {
        val activeService = service ?: return
        val manager = windowManager ?: return
        val params = layoutParams ?: return
        val metrics = realDisplayMetrics(activeService) ?: return
        val nextOffset = edgeLatchOffsetForRelease(
            rawY = rawY,
            displayHeightPx = metrics.heightPixels,
            latchLengthPx = root.handleHeightPx,
            fallback = edgeOffset,
        )
        val previousOffset = edgeOffset
        val oldX = params.x
        val oldY = params.y
        val measuredWidth = root.measuredWidth.coerceAtLeast(root.handleWidthPx)
        val measuredHeight = root.measuredHeight.coerceAtLeast(root.handleHeightPx)
        edgeOffset = nextOffset
        val position = collapsedWindowPosition(edge, nextOffset, root, metrics, measuredWidth, measuredHeight)
        params.x = position.first
        params.y = position.second
        runCatching {
            manager.updateViewLayout(root, params)
            updateSurfaceGeometry(metrics, root, measuredWidth, measuredHeight)
        }.onFailure {
            params.x = oldX
            params.y = oldY
            edgeOffset = previousOffset
        }
    }

    private fun collapsedWindowPosition(
        side: EdgeLatchSide,
        offset: Float,
        root: ControllerRoot,
        metrics: DisplayMetrics,
        measuredWidth: Int,
        measuredHeight: Int,
    ): Pair<Int, Int> {
        val x = when (side) {
            EdgeLatchSide.LEFT -> -(root.handleWidthPx - dp(root.context, LATCH_VISIBLE_DP))

            EdgeLatchSide.RIGHT ->
                metrics.widthPixels -
                    (root.handleLeftPx + dp(root.context, LATCH_VISIBLE_DP))
        }
        val usableAxis = (metrics.heightPixels - root.handleHeightPx).coerceAtLeast(0)
        val y = (offset * usableAxis).roundToInt()
            .coerceIn(0, (metrics.heightPixels - measuredHeight).coerceAtLeast(0))
        return x to y
    }

    private fun ensureEdgeLoaded(context: Context) {
        if (edgeLoaded) return
        val preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val persisted = preferences.getString(PREF_EDGE, null)
            ?.let { value -> runCatching { EdgeLatchSide.valueOf(value) }.getOrNull() }
            ?.takeIf { it == EdgeLatchSide.LEFT || it == EdgeLatchSide.RIGHT }
        edge = persisted ?: legacyEdge(preferences)
        edgeOffset = if (preferences.contains(PREF_EDGE_OFFSET)) {
            preferences.getFloat(PREF_EDGE_OFFSET, 0.5f)
        } else {
            preferences.getFloat(PREF_LEGACY_ANCHOR_Y, 0.5f)
        }.coerceIn(0f, 1f)
        edgeLoaded = true
    }

    private fun legacyEdge(preferences: android.content.SharedPreferences): EdgeLatchSide {
        val legacyX = preferences.getFloat(PREF_LEGACY_ANCHOR_X, 0.98f)
        return nearestEdgeLatchSide(
            rawX = legacyX * 1000f,
            displayWidthPx = 1000,
            fallback = DEFAULT_EDGE,
        )
    }

    private fun buildPanel(activeService: AccessibilityService): LinearLayout {
        val panel = LinearLayout(activeService).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(activeService, PANEL_PADDING_DP),
                dp(activeService, PANEL_PADDING_DP),
                dp(activeService, PANEL_PADDING_DP),
                dp(activeService, PANEL_PADDING_DP),
            )
            background = panelBackground(activeService)
        }

        val row = LinearLayout(activeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val actions = controlActions(activeService).take(MAX_PANEL_ACTIONS)
        actions.forEachIndexed { index, action ->
            row.addView(
                iconButton(activeService, action.icon, action.description, action.onClick),
                LinearLayout.LayoutParams(
                    dp(activeService, ICON_SIZE_DP),
                    dp(activeService, ICON_SIZE_DP),
                ).apply {
                    setMargins(
                        if (index == 0) 0 else dp(activeService, PANEL_GAP_DP),
                        0,
                        0,
                        0,
                    )
                },
            )
        }
        panel.addView(row)
        return panel
    }

    private fun controlActions(activeService: AccessibilityService): List<ControlAction> {
        val actions = mutableListOf<ControlAction>()
        when {
            replayPaused -> {
                replayActions?.let { replay ->
                    actions += ControlAction(android.R.drawable.ic_media_play, "Resume replay") {
                        expanded = false
                        if (showReplay()) replay.onResume() else replay.onStop()
                    }
                    actions += ControlAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop replay") {
                        expanded = false
                        replay.onStop()
                    }
                }
            }

            recordingState == ControlCenterRecordingState.RECORDING -> {
                recordingActions?.let { recording ->
                    actions += ControlAction(android.R.drawable.ic_media_pause, "Pause recording") {
                        expanded = false
                        recording.onPause()
                    }
                    actions += ControlAction(android.R.drawable.ic_menu_add, "Add recording block") {
                        expanded = false
                        recording.onAdd()
                    }
                    actions += ControlAction(android.R.drawable.ic_menu_agenda, "Recording steps") {
                        expanded = false
                        recording.onSteps()
                    }
                    actions += ControlAction(android.R.drawable.ic_menu_save, "Stop and save recording") {
                        expanded = false
                        recording.onStop()
                    }
                }
            }

            recordingState == ControlCenterRecordingState.PAUSED -> {
                recordingActions?.let { recording ->
                    actions += ControlAction(android.R.drawable.ic_media_play, "Continue recording") {
                        expanded = false
                        recording.onResume()
                    }
                    actions += ControlAction(android.R.drawable.ic_menu_add, "Add recording block") {
                        expanded = false
                        recording.onAdd()
                    }
                    actions += ControlAction(android.R.drawable.ic_menu_agenda, "Recording steps") {
                        expanded = false
                        recording.onSteps()
                    }
                    actions += ControlAction(android.R.drawable.ic_menu_save, "Stop and save recording") {
                        expanded = false
                        recording.onStop()
                    }
                }
            }

            else -> {
                recordingActions?.let { recording ->
                    actions += ControlAction(R.drawable.ic_controller_record, "Start recording") {
                        expanded = false
                        recording.onStart()
                    }
                }
                val displayedTarget = playTarget(activeService)
                actions += ControlAction(
                    android.R.drawable.ic_media_play,
                    "Replay ${displayedTarget.label.lowercase()}",
                ) {
                    // The drawer can outlive changes to its settings or workflow catalog.
                    val currentTarget = playTarget(activeService)
                    ReplayWorkflowCatalog.load(activeService)
                    val selectedWorkflow = ReplayWorkflowCatalog.selected(currentTarget)
                    if (selectedWorkflow == null) {
                        Toast.makeText(
                            activeService,
                            "No ${currentTarget.label.lowercase()} workflow to replay",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        openWorkflow(activeService, selectedWorkflow, autoStart = true)
                    }
                }
                actions += ControlAction(android.R.drawable.ic_menu_manage, "Open Veyarc workflow library") {
                    openWorkflow(activeService, null)
                }
            }
        }
        return actions + ControlAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Hide Veyarc control center",
        ) {
            setEnabled(activeService, false)
        }
    }

    private fun iconButton(
        context: Context,
        icon: Int,
        description: String,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageResource(icon)
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        setColorFilter(if (dark) 0xFFF5EEF8.toInt() else 0xFF2D2631.toInt(), PorterDuff.Mode.SRC_IN)
        contentDescription = description
        background = iconBackground(context)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
        setOnClickListener { onClick() }
    }

    private fun addOverlay(view: ControllerRoot, activeService: AccessibilityService): Boolean {
        val manager = activeService.getSystemService(WindowManager::class.java) ?: return false
        val displayMetrics = realDisplayMetrics(activeService) ?: return false
        val measuredWidth = view.measuredWidth.coerceAtLeast(view.handleWidthPx)
        val measuredHeight = view.measuredHeight.coerceAtLeast(view.handleHeightPx)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        // Most of the latch stays outside the display so it does not cover app controls. The
        // panel stays fully on-screen and touches the visible latch edge
        // with no interior placement or gap. Along-edge placement is persisted as a normalized
        // start coordinate and clamped only when the expanded panel would exceed the display.
        params.x = when (edge) {
            EdgeLatchSide.LEFT -> if (expanded) {
                0
            } else {
                -(view.handleWidthPx - dp(activeService, LATCH_VISIBLE_DP))
            }

            EdgeLatchSide.RIGHT -> if (expanded) {
                displayMetrics.widthPixels - measuredWidth
            } else {
                displayMetrics.widthPixels - dp(activeService, LATCH_VISIBLE_DP)
            }
        }
        val usableAxis = (displayMetrics.heightPixels - view.handleHeightPx).coerceAtLeast(0)
        val desired = (edgeOffset * usableAxis).roundToInt()
        params.y = desired.coerceIn(0, (displayMetrics.heightPixels - measuredHeight).coerceAtLeast(0))
        val openingTranslation = if (expanded) {
            edgeDrawerTranslationPx(
                side = edge,
                panelWidthPx = view.panelWidthPx,
                handleWidthPx = view.handleWidthPx,
                visibleHandleWidthPx = dp(activeService, LATCH_VISIBLE_DP),
            )
        } else {
            0f
        }
        view.translationX = openingTranslation
        return runCatching {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            layoutParams = params
            updateSurfaceGeometry(displayMetrics, view, measuredWidth, measuredHeight)
            if (expanded) {
                view.animating = true
                view.animate().translationX(0f).setDuration(180L).withEndAction {
                    view.animating = false
                }.start()
            }
            true
        }.getOrElse {
            surfaceBounds = null
            false
        }
    }

    private fun removeOverlay(): Boolean {
        val view = overlayView ?: run {
            surfaceBounds = null
            windowManager = null
            layoutParams = null
            return true
        }
        (view as? ControllerRoot)?.positionAnimator?.cancel()
        view.animate().cancel()
        val manager = windowManager
        if (manager == null) {
            if (view.parent != null) return false
            overlayView = null
            layoutParams = null
            surfaceBounds = null
            return true
        }
        val removed = runCatching {
            manager.removeViewImmediate(view)
            true
        }.getOrElse { view.parent == null }
        if (!removed) return false
        overlayView = null
        windowManager = null
        layoutParams = null
        surfaceBounds = null
        return true
    }

    /**
     * Accessibility teardown can race WindowManager removal. If removal is temporarily rejected,
     * make the stale window non-touchable before retrying so it cannot consume device input after
     * the service has been interrupted.
     */
    private fun removeOverlayOrMakePassThrough() {
        val view = overlayView
        if (removeOverlay() || view == null) return
        surfaceBounds = null
        val manager = windowManager
        val params = layoutParams
        if (manager != null && params != null) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { manager.updateViewLayout(view, params) }
        }
        disableViewTree(view)
        retryOverlayRemoval(view, MAX_OVERLAY_REMOVAL_RETRIES)
    }

    private fun disableViewTree(view: View) {
        view.visibility = View.INVISIBLE
        view.isEnabled = false
        view.isClickable = false
        view.isFocusable = false
        view.setOnClickListener(null)
        view.setOnTouchListener(null)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) disableViewTree(view.getChildAt(index))
        }
    }

    private fun retryOverlayRemoval(view: View, attemptsRemaining: Int) {
        if (overlayView !== view || attemptsRemaining <= 0) return
        mainHandler.postDelayed({
            if (overlayView !== view) return@postDelayed
            if (removeOverlay()) return@postDelayed
            retryOverlayRemoval(view, attemptsRemaining - 1)
        }, OVERLAY_REMOVAL_RETRY_DELAY_MILLIS)
    }

    /** Returns the current visible portion of the overlay for collision checks. */
    private fun currentSurfaceBounds(): ControlSurfaceBounds? = surfaceBounds

    private fun realDisplayMetrics(activeService: AccessibilityService): DisplayMetrics? = runCatching {
        val manager = activeService.getSystemService(WindowManager::class.java) ?: return null
        DisplayMetrics().also { metrics ->
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(metrics)
        }.takeIf { it.widthPixels > 0 && it.heightPixels > 0 }
    }.getOrNull()

    private fun updateSurfaceGeometry(
        displayMetrics: DisplayMetrics,
        root: ControllerRoot,
        measuredWidth: Int = root.measuredWidth.coerceAtLeast(1),
        measuredHeight: Int = root.measuredHeight.coerceAtLeast(1),
    ) {
        val params = layoutParams ?: return
        val left = params.x
        val top = params.y
        val right = left + measuredWidth
        val bottom = top + measuredHeight
        surfaceBounds = ControlSurfaceBounds(
            leftPx = left.coerceAtLeast(0),
            topPx = top.coerceAtLeast(0),
            rightPx = right.coerceAtMost(displayMetrics.widthPixels),
            bottomPx = bottom.coerceAtMost(displayMetrics.heightPixels),
            displayWidthPx = displayMetrics.widthPixels,
            displayHeightPx = displayMetrics.heightPixels,
        )
    }

    private fun openWorkflow(
        activeService: AccessibilityService,
        workflow: ReplayControllerWorkflow?,
        autoStart: Boolean = false,
    ) {
        expanded = false
        removeOverlayOrMakePassThrough()
        val intent = Intent(ACTION_OPEN_WORKFLOW)
            .setPackage(activeService.packageName)
            .setClassName(activeService.packageName, "${activeService.packageName}.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (workflow != null) {
            intent.putExtra(EXTRA_WORKFLOW_ID, workflow.workflowId)
            intent.putExtra(EXTRA_WORKFLOW_REVISION, workflow.revision)
            intent.putExtra(EXTRA_AUTO_START, autoStart)
        }
        runCatching { activeService.startActivity(intent) }
        mainHandler.postDelayed({ showCurrentInternal() }, CONTROLLER_RESHOW_DELAY_MILLIS)
    }

    private fun notifyServiceLost() {
        val replay = replayActions
        val recording = recordingActions
        replayActions = null
        recordingActions = null
        recordingState = ControlCenterRecordingState.IDLE
        replayActive = false
        replayPaused = false
        replayStopRequested = false
        openAfterReplayStop = false
        expanded = false
        runCatching { replay?.onServiceLost?.invoke() }
        runCatching { recording?.onServiceLost?.invoke() }
    }

    private fun isEnabledForCurrentService(): Boolean = service?.let(::isEnabled) == true

    private fun handleBackground(context: Context): Drawable {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (dark) 0xF23A323F.toInt() else 0xF2F3EDF7.toInt())
            cornerRadii = edgeLatchCornerRadii(edge, dp(context, 16).toFloat())
        }
    }

    private fun latchAccentColor(context: Context): Int {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return when {
            replayActive || recordingState == ControlCenterRecordingState.RECORDING -> 0xFFE55B62.toInt()
            replayPaused || recordingState == ControlCenterRecordingState.PAUSED -> 0xFFE8C8FF.toInt()
            dark -> 0xFFD0BCFF.toInt()
            else -> 0xFF6F3FB2.toInt()
        }
    }

    private fun iconBackground(context: Context): Drawable {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 12).toFloat()
            setColor(if (dark) 0xFF443A49.toInt() else 0xFFE8DFEB.toInt())
        }
        return RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000),
            content,
            null,
        )
    }

    private fun panelBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        setColor(if (dark) 0xF23A323F.toInt() else 0xF2F3EDF7.toInt())
        val attachedSide = if (edge == EdgeLatchSide.LEFT) EdgeLatchSide.RIGHT else EdgeLatchSide.LEFT
        cornerRadii = edgeLatchCornerRadii(attachedSide, dp(context, 18).toFloat())
    }

    private fun dp(context: Context?, value: Int): Int {
        val density = context?.resources?.displayMetrics?.density ?: 1f
        return (value * density).roundToInt().coerceAtLeast(1)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun runOnMainAndWait(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Boolean?>(null)
        val completed = CountDownLatch(1)
        val state = AtomicInteger(MAIN_TASK_PENDING)
        mainHandler.post {
            if (!state.compareAndSet(MAIN_TASK_PENDING, MAIN_TASK_RUNNING)) {
                completed.countDown()
                return@post
            }
            try {
                result.set(runCatching { block() }.getOrDefault(false))
            } finally {
                state.set(MAIN_TASK_DONE)
                completed.countDown()
            }
        }
        val completedInTime = runCatching {
            completed.await(SHOW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!completedInTime && state.compareAndSet(MAIN_TASK_PENDING, MAIN_TASK_CANCELLED)) {
            return false
        }
        if (!completedInTime && !runCatching {
                completed.await(SHOW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        ) {
            return false
        }
        return result.get() == true
    }

    private data class ControlAction(
        val icon: Int,
        val description: String,
        val onClick: () -> Unit,
    )

    private data class Placement(
        val panelLeft: Int,
        val panelTop: Int,
        val handleLeft: Int,
        val handleTop: Int,
    )

    private data class ControlSurfaceBounds(
        val leftPx: Int,
        val topPx: Int,
        val rightPx: Int,
        val bottomPx: Int,
        val displayWidthPx: Int,
        val displayHeightPx: Int,
    )

    private data class DragState(
        val startRawX: Float,
        val startRawY: Float,
        val touchSlopPx: Int,
        val startEdgeOffset: Float,
        var dragging: Boolean = false,
    )

    private class ControllerRoot(context: Context) : FrameLayout(context) {
        var animating: Boolean = false
        var positionAnimator: ValueAnimator? = null
        var panel: View? = null
        var handle: View? = null
        var panelWidthPx: Int = 0
        var handleLeftPx: Int = 0
        var handleTopPx: Int = 0
        var handleWidthPx: Int = 0
        var handleHeightPx: Int = 0
    }

    private class LatchButton(
        context: Context,
        private val side: EdgeLatchSide,
        accentColor: Int,
    ) : ImageButton(context) {
        private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val gripWidth = dp(context, 3).toFloat()
            val gripHeight = dp(context, 24).toFloat()
            val visibleCenterX = if (side == EdgeLatchSide.LEFT) width * 0.75f else width * 0.25f
            val top = (height - gripHeight) / 2f
            canvas.drawRoundRect(
                RectF(visibleCenterX - gripWidth / 2f, top, visibleCenterX + gripWidth / 2f, top + gripHeight),
                gripWidth,
                gripWidth,
                gripPaint,
            )
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
