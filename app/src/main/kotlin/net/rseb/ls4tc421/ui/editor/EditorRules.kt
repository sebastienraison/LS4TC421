package net.rseb.ls4tc421.ui.editor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import net.rseb.ls4tc421.model.KeyPoint
import net.rseb.ls4tc421.model.ProgramSpec
import net.rseb.ls4tc421.model.QUARTERS
import net.rseb.ls4tc421.model.STEPS

/**
 * The editor's rules (D19), out of Compose so that they can be tested on the JVM.
 *
 * Units: a step is a half-hour (0..47); the window of the main graph is given by its first step and
 * spans [WINDOW_STEPS] steps, so it shows [WINDOW_STEPS] + 1 point positions. It never crosses
 * midnight: its first step is in 0..[MAX_WINDOW_START], and its end may be 24:00 (position 48, which
 * is not a step and holds no point).
 */
object EditorRules {
    /** 3 h. */
    const val WINDOW_STEPS = 6

    /** The window ends at 24:00 at the latest. */
    const val MAX_WINDOW_START = STEPS - WINDOW_STEPS

    /** A channel without any lit step opens at 06:00. */
    const val EMPTY_CHANNEL_WINDOW_START = 12

    /** A press closer than this to an editable point grabs it. */
    const val GRAB_RADIUS_DP = 28f

    /** Levels snap to 5 %. */
    const val LEVEL_SNAP = 5

    // ---- window ------------------------------------------------------------------------------

    fun clampWindowStart(start: Int): Int = start.coerceIn(0, MAX_WINDOW_START)

    /**
     * Where the window opens (and goes when the channel changes): 30 min before the first step of
     * [channel] above 0; 06:00 for a channel at 0 all day.
     */
    fun initialWindowStart(levels: List<List<Int>>, channel: Int): Int {
        val first = (0 until STEPS).firstOrNull { levels[it][channel] > 0 } ?: return EMPTY_CHANNEL_WINDOW_START
        return clampWindowStart(first - 1)
    }

    /** The window centred on [minute] (touch on the overview), on the half-hour, kept within the day. */
    fun centeredWindowStart(minute: Float): Int = clampWindowStart((minute / 30f - WINDOW_STEPS / 2f).roundToInt())

    /** The window dragged by [deltaMinutes] from where it was when the drag started, on the half-hour. */
    fun draggedWindowStart(startAtGrab: Int, deltaMinutes: Float): Int =
        clampWindowStart((startAtGrab + deltaMinutes / 30f).roundToInt())

    fun inWindow(step: Int, windowStart: Int): Boolean = step in windowStart..windowStart + WINDOW_STEPS

    // ---- points ------------------------------------------------------------------------------

    /**
     * With symmetry, a movable point stays strictly before the axis: on the source side, and not on
     * the axis itself (a point there would be its own mirror). Without symmetry, every step is allowed.
     */
    fun isBeforeAxis(spec: ProgramSpec, step: Int): Boolean {
        val axis = spec.symmetryAxis ?: return true
        return !spec.isMirrorSide(step) && Math.floorMod(axis - 2 * step, QUARTERS) != 0
    }

    /** The points of [channel] that can be grabbed: its own, on the source side; none if linked. */
    fun movablePoints(spec: ProgramSpec, channel: Int): List<KeyPoint> =
        if (spec.isLinked(channel)) emptyList() else spec.points[channel].filterNot { spec.isMirrorSide(it.step) }

    /** The movable points of [channel] inside the window. */
    fun movablePointsInWindow(spec: ProgramSpec, channel: Int, windowStart: Int): List<KeyPoint> =
        movablePoints(spec, channel).filter { inWindow(it.step, windowStart) }

    /** Points drawn hollow in the window: the mirrors of the symmetry (none on a linked channel). */
    fun mirrorPointsInWindow(spec: ProgramSpec, channel: Int, windowStart: Int): List<KeyPoint> =
        if (spec.isLinked(channel)) emptyList() else spec.mirrorPoints(channel).filter { inWindow(it.step, windowStart) }

    /**
     * The point grabbed by a press at ([x], [y]) px: the nearest movable point of [channel] in the
     * window, if closer than [radius] px. [px] and [py] map a step and a level to pixels.
     */
    fun grab(
        spec: ProgramSpec,
        channel: Int,
        windowStart: Int,
        x: Float,
        y: Float,
        radius: Float,
        px: (step: Int) -> Float,
        py: (level: Int) -> Float,
    ): KeyPoint? = movablePointsInWindow(spec, channel, windowStart)
        .map { it to hypot(px(it.step) - x, py(it.level) - y) }
        .filter { it.second < radius }
        .minByOrNull { it.second }
        ?.first

    /** Level to the nearest 5 %, within 0..100. */
    fun snapLevel(level: Float): Int = ((level / LEVEL_SNAP).roundToInt() * LEVEL_SNAP).coerceIn(0, 100)

    /**
     * The steps where the point at [step] of [channel] may go (R1 and the symmetry, then R2): between
     * its neighbours, 30 min from each; before the symmetry axis; inside the window.
     */
    fun dragRange(spec: ProgramSpec, channel: Int, step: Int, windowStart: Int): IntRange {
        val others = movablePoints(spec, channel).map { it.step }.filter { it != step }
        val allowed = { s: Int ->
            s in 0 until STEPS && s !in others && isBeforeAxis(spec, s) && inWindow(s, windowStart)
        }
        // Walk away from the point until something stops it: a neighbour, the axis, the window.
        var low = step
        while (allowed(low - 1)) low--
        var high = step
        while (allowed(high + 1)) high++
        return low..high
    }

    /**
     * Where a drag puts [origin] (as it was when grabbed, in [base]) when the finger has moved by
     * [deltaSteps] and [deltaLevel]: time on the half-hour, level on 5 %, both held by [dragRange].
     * An imported level that is not a multiple of 5 is kept until the finger moves it by half a snap.
     */
    fun dragTarget(
        base: ProgramSpec,
        channel: Int,
        windowStart: Int,
        origin: KeyPoint,
        deltaSteps: Float,
        deltaLevel: Float,
    ): KeyPoint {
        val range = dragRange(base, channel, origin.step, windowStart)
        val step = (origin.step + deltaSteps).roundToInt().coerceIn(range.first, range.last)
        val level = if (abs(deltaLevel) < LEVEL_SNAP / 2f) origin.level else snapLevel(origin.level + deltaLevel)
        return KeyPoint(step, level)
    }

    // ---- selection, Add, Delete ---------------------------------------------------------------

    /** The selection if it still names a movable point of [channel] inside the window, else none. */
    fun keptSelection(spec: ProgramSpec, channel: Int, windowStart: Int, selected: Int?): Int? =
        selected?.takeIf { s -> movablePointsInWindow(spec, channel, windowStart).any { it.step == s } }

    /**
     * Where « Add a point » puts a point, or `null` when the button is disabled.
     * - With a selection: 30 min after it, at the current level of the curve there; disabled if that
     *   half-hour already has a point, leaves the window or reaches the symmetry axis.
     * - Without: in the middle of the window, at the level of the curve there rounded to 5 %;
     *   disabled if that half-hour already has a point or is on or after the axis.
     * Always disabled on a linked channel.
     */
    fun addTarget(spec: ProgramSpec, levels: List<List<Int>>, channel: Int, windowStart: Int, selected: Int?): KeyPoint? {
        if (spec.isLinked(channel)) return null
        val step = if (selected != null) selected + 1 else windowStart + WINDOW_STEPS / 2
        if (step !in 0 until STEPS || !inWindow(step, windowStart)) return null
        if (spec.points[channel].any { it.step == step } || !isBeforeAxis(spec, step)) return null
        val curve = levels[step][channel]
        return KeyPoint(step, if (selected != null) curve else snapLevel(curve.toFloat()))
    }

    /** Delete is enabled with a selection, on a channel that is not linked. */
    fun canDelete(spec: ProgramSpec, channel: Int, selected: Int?): Boolean = selected != null && !spec.isLinked(channel)

    /** Which help line to show under the graph. */
    fun hint(spec: ProgramSpec, channel: Int, windowStart: Int): Hint = when {
        spec.isLinked(channel) -> Hint.LINKED
        movablePointsInWindow(spec, channel, windowStart).isEmpty() &&
            mirrorPointsInWindow(spec, channel, windowStart).isNotEmpty() -> Hint.HOLLOW
        else -> Hint.DRAG
    }

    enum class Hint { DRAG, HOLLOW, LINKED }
}
