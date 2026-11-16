package net.rseb.ls4tc421.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProgramSpecTest {
    private fun channel(spec: ProgramSpec, ch: Int) = spec.levels().map { it[ch] }

    private fun one(vararg points: Pair<Int, Int>, ch: Int = 0): ProgramSpec =
        ProgramSpec("t").let { spec -> points.fold(spec) { s, (step, level) -> s.withPoint(ch, step, level) } }

    // ---- interpolation ----------------------------------------------------------------------

    @Test fun emptyChannelIsZeroAllDay() {
        assertEquals(List(48) { List(5) { 0 } }, ProgramSpec.empty("t").levels())
    }

    @Test fun singlePointGivesThatLevelAllDay() {
        assertEquals(List(48) { 37 }, channel(one(10 to 37), 0))
    }

    @Test fun linearBetweenPointsAndRounded() {
        val levels = channel(one(10 to 0, 13 to 100), 0)
        assertEquals(0, levels[10])
        assertEquals(33, levels[11]) // 33.33
        assertEquals(67, levels[12]) // 66.67
        assertEquals(100, levels[13])
    }

    @Test fun halvesRoundUp() {
        val levels = channel(one(0 to 0, 2 to 1), 0)
        assertEquals(1, levels[1]) // 0.5 -> 1
        val down = channel(one(0 to 1, 2 to 0), 0)
        assertEquals(1, down[1]) // 0.5 -> 1 as well
    }

    @Test fun curveLoopsOverMidnight() {
        // 22:00 at 100 %, 02:00 at 0 %: 00:00 is halfway.
        val levels = channel(one(44 to 100, 4 to 0), 0)
        assertEquals(100, levels[44])
        assertEquals(50, levels[0])
        assertEquals(75, levels[46])
        assertEquals(25, levels[2])
        assertEquals(0, levels[4])
        // Between 02:00 and 22:00 the curve rises linearly over 40 steps.
        assertEquals(50, levels[24])
    }

    @Test fun pointsAreTheExactValuesAtTheirSteps() {
        val spec = ProgramSpec.fromSteps("aqua", Aquarium.STEPS)
        assertEquals(Aquarium.STEPS, spec.levels())
        assertEquals(Aquarium.STEPS, spec.toProgram().steps)
        assertEquals("aqua", spec.toProgram().name)
    }

    // ---- symmetry ---------------------------------------------------------------------------

    @Test fun aquariumEnteredByItsRiseOnlyGivesBackItsExact48Steps() {
        assertEquals(Aquarium.STEPS, Aquarium.RISE_ONLY.levels())
    }

    @Test fun aquariumWithFewerPointsAlsoMatches() {
        // Only the points where the slope changes, per channel.
        val spec = ProgramSpec(
            "aqua",
            listOf(
                listOf(KeyPoint(17, 0), KeyPoint(18, 20), KeyPoint(19, 40), KeyPoint(20, 100)),
                listOf(KeyPoint(18, 0), KeyPoint(19, 20), KeyPoint(20, 40), KeyPoint(21, 80), KeyPoint(22, 90)),
                listOf(KeyPoint(16, 0), KeyPoint(17, 15), KeyPoint(18, 40), KeyPoint(19, 60), KeyPoint(20, 80), KeyPoint(21, 100)),
                listOf(KeyPoint(16, 0), KeyPoint(17, 5), KeyPoint(18, 20), KeyPoint(19, 40), KeyPoint(20, 80), KeyPoint(21, 90), KeyPoint(22, 100)),
                emptyList(),
            ),
            symmetryAxis = Aquarium.AXIS,
        )
        assertEquals(Aquarium.STEPS, spec.levels())
    }

    @Test fun mirrorOfStepIIsTwiceTheAxisMinusI() {
        val spec = one(10 to 50).withPoint(0, 12, 0).withAxis(30) // axis 07:30 = step 15
        val mirrors = spec.mirrorPoints(0)
        assertEquals(listOf(KeyPoint(18, 0), KeyPoint(20, 50)), mirrors)
    }

    @Test fun axisOnAQuarterHourMirrorsBetweenSteps() {
        val spec = one(10 to 50).withAxis(41) // 10:15: step 10 (05:00) mirrors to step 31 (15:30)
        assertEquals(listOf(KeyPoint(31, 50)), spec.mirrorPoints(0))
    }

    @Test fun pointsAfterTheAxisAreIgnored() {
        val spec = Aquarium.RISE_ONLY.withPoint(0, 30, 3) // 15:00, before 15:30: counts
        assertEquals(3, spec.levels()[30][0])
        val ignored = Aquarium.RISE_ONLY.withPoint(0, 35, 3) // 17:30, after the axis
        assertTrue(ignored.isMirrorSide(35))
        assertEquals(Aquarium.STEPS, ignored.levels())
        assertEquals(Aquarium.RISE_ONLY, ignored.pruned())
    }

    @Test fun pointOnTheAxisIsItsOwnMirror() {
        val spec = one(20 to 0, 31 to 80).withAxis(62)
        assertFalse(spec.isMirrorSide(31))
        assertEquals(listOf(KeyPoint(42, 0)), spec.mirrorPoints(0))
        assertEquals(80, spec.levels()[31][0])
    }

    @Test fun enablingSymmetryPutsTheAxisInTheMiddleOfTheLitPeriodAndDropsTheDescent() {
        val full = ProgramSpec.fromSteps("aqua", Aquarium.STEPS)
        val symmetric = full.withSymmetry(true)
        assertEquals(Aquarium.AXIS, symmetric.symmetryAxis)
        assertEquals(Aquarium.STEPS, symmetric.levels())
        assertTrue(symmetric.points.all { list -> list.none { symmetric.isMirrorSide(it.step) } })
    }

    @Test fun disablingSymmetryKeepsTheCurve() {
        val off = Aquarium.RISE_ONLY.withSymmetry(false)
        assertNull(off.symmetryAxis)
        assertEquals(Aquarium.STEPS, off.levels())
    }

    @Test fun defaultAxisOfALitPeriodCrossingMidnight() {
        // Lit from 20:00 (step 40) to 02:00 (step 4): the middle is 23:00 = quarter 92.
        val spec = one(39 to 0, 40 to 50, 4 to 50, 5 to 0)
        assertEquals(LitPeriod(40, 4), ProgramSpec.litPeriod(spec.levels()))
        assertEquals(92, ProgramSpec.defaultAxis(spec.levels()))
    }

    @Test fun defaultAxisIsNoonWithoutLight() {
        assertEquals(48, ProgramSpec.defaultAxis(ProgramSpec.empty("t").levels()))
    }

    @Test fun cannotMoveAPointOntoAnotherOrToTheMirrorSide() {
        val spec = one(10 to 10, 12 to 20)
        assertSame(spec, spec.movedPoint(0, 10, 12, 50))
        val moved = spec.movedPoint(0, 10, 11, 50)
        assertEquals(listOf(KeyPoint(11, 50), KeyPoint(12, 20)), moved.points[0])
        val symmetric = Aquarium.RISE_ONLY
        assertSame(symmetric, symmetric.movedPoint(0, 22, 40, 100))
    }

    // ---- links ------------------------------------------------------------------------------

    @Test fun linkedChannelFollowsItsSourceStepByStepRounded() {
        val spec = one(10 to 0, 13 to 100).withLink(1, ChannelLink(source = 0, percent = 90))
        val source = channel(spec, 0)
        val linked = channel(spec, 1)
        assertEquals(List(48) { (source[it] * 90 + 50) / 100 }, linked)
        assertEquals(30, linked[11]) // 33 × 0.9 = 29.7
        assertEquals(60, linked[12]) // 67 × 0.9 = 60.3
        assertEquals(90, linked[13])
    }

    @Test fun linkIsComputedAfterSymmetry() {
        val spec = Aquarium.RISE_ONLY.withLink(1, ChannelLink(0, 90))
        assertEquals(Aquarium.STEPS.map { (it[0] * 90 + 50) / 100 }, channel(spec, 1))
    }

    @Test fun linkedChannelOwnPointsAreIgnoredButKept() {
        val spec = one(5 to 40, ch = 1).withLink(1, ChannelLink(0, 50))
        assertEquals(List(48) { 0 }, channel(spec, 1))
        assertEquals(List(48) { 40 }, channel(spec.withLink(1, null), 1))
    }

    @Test fun noChainOfLinks() {
        val spec = ProgramSpec("t").withLink(1, ChannelLink(0, 90))
        expectInvalid { spec.withLink(2, ChannelLink(1, 50)) } // source is itself linked
        expectInvalid { spec.withLink(0, ChannelLink(3, 50)) } // channel 1 is a source
        expectInvalid { spec.withLink(3, ChannelLink(3, 50)) } // itself
        expectInvalid { spec.withLink(3, ChannelLink(0, 101)) }
    }

    // ---- validation -------------------------------------------------------------------------

    @Test fun invalidSpecsAreRefused() {
        expectInvalid { ProgramSpec("") }
        expectInvalid { ProgramSpec("t", List(4) { emptyList() }) }
        expectInvalid { ProgramSpec("t").withPoint(0, 48, 0) }
        expectInvalid { ProgramSpec("t", List(5) { listOf(KeyPoint(2, 0), KeyPoint(2, 5)) }) }
        expectInvalid { ProgramSpec("t", symmetryAxis = 96) }
    }

    /** D23: a name saved before the rule still builds; it only cannot be sent until it is renamed. */
    @Test fun aNameThatCannotBeSentStillBuildsButGivesNoProgram() {
        val old = Aquarium.RISE_ONLY.renamed("court_basique") // 13 characters
        assertFalse(old.sendable)
        assertNull(old.toProgramOrNull())
        expectInvalid { old.toProgram() }
        assertEquals(Aquarium.STEPS, old.levels()) // still computed, shown and edited
        assertFalse(ProgramSpec("été").sendable)
        assertFalse(ProgramSpec("x".repeat(10)).sendable)
        assertFalse(ProgramSpec("x".repeat(8)).sendable) // the app's 7 (D23, 16/11)

        val renamed = old.renamed("court_b") // 7 characters
        assertTrue(renamed.sendable)
        assertEquals("court_b", renamed.toProgramOrNull()?.name)
        assertEquals(Aquarium.STEPS, renamed.toProgram().steps)
    }

    @Test fun levelsAreClampedWhenEditing() {
        assertEquals(100, one(3 to 140).points[0][0].level)
        assertEquals(0, one(3 to -5).points[0][0].level)
    }

    @Test fun timesAreFormattedOnTheClock() {
        assertEquals("00:00", stepTime(0))
        assertEquals("08:30", stepTime(17))
        assertEquals("23:30", stepTime(47))
        assertEquals("15:30", quarterTime(62))
        assertEquals("10:15", quarterTime(41))
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
