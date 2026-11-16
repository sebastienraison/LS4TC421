package net.rseb.ls4tc421.model

import java.util.Locale
import ls4tc421.protocol.Limits
import ls4tc421.protocol.Program

/** Number of channels, steps per day and quarter-hours per day. */
const val CHANNELS = Limits.CHANNELS
const val STEPS = Limits.STEPS_PER_PROGRAM
const val QUARTERS = STEPS * 2

/** A key point of one channel: at step [step] (0..47, step n starts at n × 30 min), level [level] %. */
data class KeyPoint(val step: Int, val level: Int)

/** « This channel follows channel [source] (0-based) at [percent] % ». */
data class ChannelLink(val source: Int, val percent: Int)

/**
 * A program as the user edits it. The key points are the source; the 48 steps × 5 channels are
 * always computed ([levels]), never entered.
 *
 * - [points]: for each of the 5 channels, its key points (sorted by step, one per step).
 * - [symmetryAxis]: `null` = no symmetry; otherwise the axis in quarter-hours since midnight (0..95).
 *   With symmetry on, only the points of the half-day before the axis count, and each has its mirror
 *   after the axis: step `i` gives step `2 × axis − i`. Points after the axis are ignored.
 * - [links]: for each channel, `null` or the channel it follows at a percentage. A linked channel's own
 *   points are ignored (kept, so that unlinking gives them back). A linked channel cannot be a source.
 *
 * The constructor validates the points, the symmetry and the links, so that the steps can always be
 * computed. The name only has to be non-empty: the rule for sending it (D23, [AppName]: printable
 * ASCII, 1 to 7 characters) is not enforced here, because programs saved before that rule may break
 * it and must still load, open, be edited, renamed and exported. Such a program cannot be converted
 * to a [Program] for sending ([toProgramOrNull] returns `null`) until it is renamed; see [sendable].
 */
data class ProgramSpec(
    val name: String,
    val points: List<List<KeyPoint>> = List(CHANNELS) { emptyList() },
    val symmetryAxis: Int? = null,
    val links: List<ChannelLink?> = List(CHANNELS) { null },
) {
    init {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(points.size == CHANNELS) { "exactly $CHANNELS channels expected" }
        points.forEachIndexed { channel, list ->
            list.forEach { point ->
                require(point.step in 0 until STEPS) { "channel ${channel + 1}: step ${point.step} outside 0..${STEPS - 1}" }
                require(point.level in 0..Limits.MAX_LEVEL) { "channel ${channel + 1}: level ${point.level} outside 0..100" }
            }
            require(list.zipWithNext().all { (a, b) -> a.step < b.step }) {
                "channel ${channel + 1}: points must be sorted by step, one per step"
            }
        }
        require(symmetryAxis == null || symmetryAxis in 0 until QUARTERS) { "symmetry axis outside 0..${QUARTERS - 1}" }
        require(links.size == CHANNELS) { "exactly $CHANNELS links expected" }
        links.forEachIndexed { channel, link ->
            if (link == null) return@forEachIndexed
            require(link.source in 0 until CHANNELS && link.source != channel) { "channel ${channel + 1}: invalid source" }
            require(links[link.source] == null) { "channel ${channel + 1}: its source is itself linked" }
            require(link.percent in 0..100) { "channel ${channel + 1}: percent outside 0..100" }
        }
    }

    val symmetric: Boolean get() = symmetryAxis != null

    fun isLinked(channel: Int): Boolean = links[channel] != null

    /** True if channel [channel] is the source of another channel. */
    fun isSource(channel: Int): Boolean = links.any { it?.source == channel }

    /** With symmetry on: the points of [step] are derived from the other side of the axis. */
    fun isMirrorSide(step: Int): Boolean {
        val axis = symmetryAxis ?: return false
        return Math.floorMod(axis - 2 * step, QUARTERS) > QUARTERS / 2
    }

    /** The points that count for [channel]: its own (on the source side) plus their mirrors. */
    fun effectivePoints(channel: Int): List<KeyPoint> {
        val own = points[channel]
        val axis = symmetryAxis ?: return own
        val source = own.filterNot { isMirrorSide(it.step) }
        val mirrors = source.mapNotNull { point ->
            val mirror = Math.floorMod(axis - point.step, STEPS)
            if (mirror == point.step) null else KeyPoint(mirror, point.level)
        }
        return (source + mirrors).sortedBy { it.step }
    }

    /** The mirror points of [channel] (shown, not editable). Empty without symmetry. */
    fun mirrorPoints(channel: Int): List<KeyPoint> =
        if (symmetryAxis == null) emptyList() else effectivePoints(channel) - points[channel].toSet()

    /** The 48 steps × 5 channels: `levels()[step][channel]`. */
    fun levels(): List<List<Int>> {
        val byChannel = Array(CHANNELS) { channel -> interpolate(effectivePoints(channel)) }
        links.forEachIndexed { channel, link ->
            if (link != null) {
                val source = byChannel[link.source]
                byChannel[channel] = IntArray(STEPS) { roundDiv(source[it] * link.percent, 100) }
            }
        }
        return List(STEPS) { step -> List(CHANNELS) { channel -> byChannel[channel][step] } }
    }

    /** True if the name follows the rule for sending (D23): the program can be sent and previewed. */
    val sendable: Boolean get() = AppName.isValid(name)

    /**
     * The program, checked by the library only (up to 9 characters, what the controller accepts). The
     * app sends through [toProgramOrNull], which also applies its own 7-character rule ([sendable]).
     */
    fun toProgram(): Program = Program(name, levels())

    /** The program to send, or `null` if the name is not [sendable] (rename it first). */
    fun toProgramOrNull(): Program? = if (sendable) toProgram() else null

    // ---- edits (each returns a new, valid spec) -----------------------------------------------

    fun renamed(newName: String): ProgramSpec = copy(name = newName)

    /** Adds or replaces the point at [step] of [channel]. */
    fun withPoint(channel: Int, step: Int, level: Int): ProgramSpec =
        withChannel(channel, points[channel].filterNot { it.step == step } + KeyPoint(step, level.coerceIn(0, 100)))

    fun withoutPoint(channel: Int, step: Int): ProgramSpec =
        withChannel(channel, points[channel].filterNot { it.step == step })

    /**
     * Moves the point at [fromStep] to [toStep] with [level]. Refused (same spec returned) if another
     * point already sits at [toStep], or if [toStep] is on the mirror side.
     */
    fun movedPoint(channel: Int, fromStep: Int, toStep: Int, level: Int): ProgramSpec {
        if (toStep != fromStep && points[channel].any { it.step == toStep }) return this
        if (isMirrorSide(toStep)) return this
        return withChannel(channel, points[channel].filterNot { it.step == fromStep } + KeyPoint(toStep, level.coerceIn(0, 100)))
    }

    /**
     * Symmetry on: the axis goes to the middle of the lit period, and the points after it, now
     * ignored, are removed. Symmetry off: the mirrors become ordinary points, so the curve stays the same.
     */
    fun withSymmetry(on: Boolean): ProgramSpec {
        if (on == symmetric) return this
        return if (on) {
            val axis = defaultAxis(levels())
            copy(symmetryAxis = axis).pruned()
        } else {
            copy(points = List(CHANNELS) { effectivePoints(it) }, symmetryAxis = null)
        }
    }

    /** Moves the axis. Points that end up after it are ignored, and removed by [pruned]. */
    fun withAxis(axis: Int): ProgramSpec = copy(symmetryAxis = Math.floorMod(axis, QUARTERS))

    /** Sets or removes the link of [channel]. Throws if it would make a chain. */
    fun withLink(channel: Int, link: ChannelLink?): ProgramSpec =
        copy(links = links.toMutableList().also { it[channel] = link })

    /** Without the points that symmetry ignores. Saved and exported specs are pruned. */
    fun pruned(): ProgramSpec =
        if (symmetryAxis == null) this else copy(points = List(CHANNELS) { ch -> points[ch].filterNot { isMirrorSide(it.step) } })

    private fun withChannel(channel: Int, list: List<KeyPoint>): ProgramSpec =
        copy(points = points.toMutableList().also { it[channel] = list.sortedBy { p -> p.step } })

    companion object {
        /** An empty program: every channel at 0 all day. */
        fun empty(name: String) = ProgramSpec(name)

        /** A program whose key points are its 48 steps (import of a file that has only the steps). */
        fun fromSteps(name: String, steps: List<List<Int>>): ProgramSpec {
            require(steps.size == STEPS) { "exactly $STEPS steps expected" }
            require(steps.all { it.size == CHANNELS }) { "exactly $CHANNELS levels per step expected" }
            return ProgramSpec(name, List(CHANNELS) { ch -> List(STEPS) { KeyPoint(it, steps[it][ch]) } })
        }

        /**
         * Linear interpolation between key points, rounded to the nearest integer, looping over
         * midnight (after the last point the curve goes back to the first). No point = 0 all day;
         * one point = that level all day.
         */
        fun interpolate(points: List<KeyPoint>): IntArray {
            if (points.isEmpty()) return IntArray(STEPS)
            if (points.size == 1) return IntArray(STEPS) { points[0].level }
            val sorted = points.sortedBy { it.step }
            return IntArray(STEPS) { step ->
                val nextIndex = sorted.indexOfFirst { it.step > step }.let { if (it < 0) 0 else it }
                val next = sorted[nextIndex]
                val previous = sorted[Math.floorMod(nextIndex - 1, sorted.size)]
                if (previous.step == step) return@IntArray previous.level
                val span = Math.floorMod(next.step - previous.step, STEPS).let { if (it == 0) STEPS else it }
                val done = Math.floorMod(step - previous.step, STEPS)
                val numerator = previous.level * span + (next.level - previous.level) * done
                roundDiv(numerator, span)
            }
        }

        /** Rounds `a / b` to the nearest integer, halves up (a ≥ 0, b > 0). */
        internal fun roundDiv(a: Int, b: Int): Int = (2 * a + b) / (2 * b)

        /**
         * The lit period of [levels]: from the first to the last step where a channel is above 0,
         * as a circular range (the end may be before the start when it crosses midnight). `null` if
         * nothing is lit, or if everything is (no dark step to anchor it).
         */
        fun litPeriod(levels: List<List<Int>>): LitPeriod? {
            val lit = BooleanArray(STEPS) { step -> levels[step].any { it > 0 } }
            if (lit.none { it } || lit.all { it }) return null
            // Longest circular run of dark steps: the lit period is what surrounds it.
            var bestStart = -1
            var bestLength = 0
            for (start in 0 until STEPS) {
                if (lit[start] || !lit[Math.floorMod(start - 1, STEPS)]) continue
                var length = 0
                while (!lit[(start + length) % STEPS]) length++
                if (length > bestLength) {
                    bestLength = length
                    bestStart = start
                }
            }
            return LitPeriod(first = (bestStart + bestLength) % STEPS, last = Math.floorMod(bestStart - 1, STEPS))
        }

        /** Middle of the lit period, in quarter-hours; 12:00 when there is none. */
        fun defaultAxis(levels: List<List<Int>>): Int {
            val period = litPeriod(levels) ?: return QUARTERS / 2
            val last = if (period.last >= period.first) period.last else period.last + STEPS
            return Math.floorMod(period.first + last, QUARTERS)
        }
    }
}

/** Steps [first]..[last], circular. */
data class LitPeriod(val first: Int, val last: Int) {
    /** Number of steps from [first] to [last] included. */
    val length: Int get() = Math.floorMod(last - first, STEPS) + 1
}

/** « 08:30 » for a step. */
fun stepTime(step: Int): String = "%02d:%02d".format(Locale.ROOT, step / 2, (step % 2) * 30)

/** « 15:15 » for a quarter-hour. */
fun quarterTime(quarter: Int): String = "%02d:%02d".format(Locale.ROOT, quarter / 4, (quarter % 4) * 15)
