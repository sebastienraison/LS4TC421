package net.rseb.ls4tc421.model

/**
 * The « aquarium » program of the debug bench (BenchPrograms.J0A), copied here as test data because
 * that file exists only in debug builds. Step n starts at n × 30 min. No personal data: a light curve.
 */
object Aquarium {
    private fun steps(vararg ranges: Pair<IntRange, List<Int>>): List<List<Int>> {
        val out = arrayOfNulls<List<Int>>(48)
        for ((range, levels) in ranges) for (step in range) out[step] = levels
        return out.map { it!! }
    }

    val STEPS: List<List<Int>> = steps(
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
    )

    /** 15:30 in quarter-hours. */
    const val AXIS = 62

    /** The rise only (steps 16 to 22), symmetry around 15:30. */
    val RISE_ONLY: ProgramSpec = ProgramSpec(
        name = "aqua",
        points = List(5) { channel -> (16..22).map { KeyPoint(it, STEPS[it][channel]) } },
        symmetryAxis = AXIS,
    )
}
