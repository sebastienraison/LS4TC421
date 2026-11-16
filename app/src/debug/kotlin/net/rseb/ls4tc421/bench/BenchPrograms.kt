package net.rseb.ls4tc421.bench

import ls4tc421.protocol.Program

/** How a fixed state is labelled. Channels only: the channel-to-colour mapping is what J0c measures. */
sealed interface StateLabel {
    /** « All 100 % », « All 0 % ». */
    data class All(val percent: Int) : StateLabel

    /** « C1 only 100 % ». */
    data class Only(val channel: Int, val percent: Int) : StateLabel

    /** « C4 25 % » (the other channels at 0). */
    data class Single(val channel: Int, val percent: Int) : StateLabel
}

/** A preview state of part A: levels of C1..C5. */
data class FixedState(val label: StateLabel, val levels: List<Int>)

object BenchPrograms {
    /** Name sent with `06 01`. */
    const val PREVIEW_NAME = "test"

    private fun only(channel: Int, percent: Int) = List(5) { if (it == channel - 1) percent else 0 }

    /** Part A, in the order of the brief. */
    val FIXED_STATES: List<FixedState> = buildList {
        add(FixedState(StateLabel.All(100), listOf(100, 100, 100, 100, 0)))
        add(FixedState(StateLabel.All(0), listOf(0, 0, 0, 0, 0)))
        for (channel in 1..5) add(FixedState(StateLabel.Only(channel, 100), only(channel, 100)))
        for (percent in listOf(25, 50, 75)) add(FixedState(StateLabel.Single(4, percent), only(4, percent)))
        for (percent in listOf(25, 50, 75)) add(FixedState(StateLabel.Single(1, percent), only(1, percent)))
        for (percent in listOf(1, 5, 10)) add(FixedState(StateLabel.Single(4, percent), only(4, percent)))
    }

    private fun steps(vararg ranges: Pair<IntRange, List<Int>>): List<List<Int>> {
        val out = arrayOfNulls<List<Int>>(48)
        for ((range, levels) in ranges) for (step in range) {
            check(out[step] == null) { "step $step defined twice" }
            out[step] = levels
        }
        return out.mapIndexed { step, levels -> checkNotNull(levels) { "step $step missing" } }
    }

    /**
     * J0a: the program running on the test aquarium (capture of 2026-11-11), with step 23 (11:30)
     * corrected from C2 80 % to 90 %. Step n starts at n × 30 min.
     */
    val J0A = Program(
        "aquarium",
        steps(
            0..16 to listOf(0, 0, 0, 0, 0),
            17..17 to listOf(0, 0, 15, 5, 0),
            18..18 to listOf(20, 0, 40, 20, 0),
            19..19 to listOf(40, 20, 60, 40, 0),
            20..20 to listOf(100, 40, 80, 80, 0),
            21..21 to listOf(100, 80, 100, 90, 0),
            22..40 to listOf(100, 90, 100, 100, 0),
            41..41 to listOf(100, 80, 100, 90, 0),
            42..42 to listOf(100, 40, 80, 80, 0),
            43..43 to listOf(40, 20, 60, 40, 0),
            44..44 to listOf(20, 0, 40, 20, 0),
            45..45 to listOf(0, 0, 15, 5, 0),
            46..47 to listOf(0, 0, 0, 0, 0),
        ),
    )

    /** J0b: every step at 10 % on C1..C4: if it becomes the running program, the light visibly drops. */
    val J0B = Program("j0b-test", List(48) { listOf(10, 10, 10, 10, 0) })
}

/** The two programs of part B, with their default slots. */
enum class BenchProgram(val program: Program, val defaultSlot: Int) {
    J0A(BenchPrograms.J0A, 0),
    J0B(BenchPrograms.J0B, 19),
}
