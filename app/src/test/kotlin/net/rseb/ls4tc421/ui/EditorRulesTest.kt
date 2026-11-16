package net.rseb.ls4tc421.ui

import net.rseb.ls4tc421.model.Aquarium
import net.rseb.ls4tc421.model.ChannelLink
import net.rseb.ls4tc421.model.KeyPoint
import net.rseb.ls4tc421.model.ProgramSpec
import net.rseb.ls4tc421.ui.editor.EditorRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorRulesTest {
    private val aquarium = Aquarium.RISE_ONLY
    private fun points(channel: Int, vararg p: Pair<Int, Int>): ProgramSpec =
        ProgramSpec("t", List(5) { if (it == channel) p.map { (s, l) -> KeyPoint(s, l) } else emptyList() })

    // ---- window ------------------------------------------------------------------------------

    @Test fun theWindowOpensHalfAnHourBeforeTheFirstLitStepOfTheChannel() {
        val levels = aquarium.levels()
        assertEquals(16, EditorRules.initialWindowStart(levels, 2)) // C3 lit from 08:30 (step 17)
        assertEquals(17, EditorRules.initialWindowStart(levels, 0)) // C1 lit from 09:00 (step 18)
        assertEquals(18, EditorRules.initialWindowStart(levels, 1)) // C2 lit from 09:30
    }

    @Test fun anEmptyChannelOpensAtSixAndTheWindowStaysInTheDay() {
        assertEquals(12, EditorRules.initialWindowStart(aquarium.levels(), 4))
        assertEquals(12, EditorRules.initialWindowStart(ProgramSpec.empty("t").levels(), 0))
        // Lit at midnight: the window cannot start before 00:00.
        assertEquals(0, EditorRules.initialWindowStart(points(0, 0 to 50).levels(), 0))
    }

    @Test fun touchingTheOverviewCentresTheWindowWithinTheDay() {
        assertEquals(17, EditorRules.centeredWindowStart(10 * 60f)) // 10:00 → 08:30..11:30
        assertEquals(17, EditorRules.centeredWindowStart(10 * 60f + 10)) // on the half-hour
        assertEquals(18, EditorRules.centeredWindowStart(10 * 60f + 20))
        assertEquals(0, EditorRules.centeredWindowStart(30f)) // not before 00:00
        assertEquals(42, EditorRules.centeredWindowStart(23 * 60 + 50f)) // not after 24:00
    }

    @Test fun draggingTheRectangleMovesTheWindowOnTheHalfHourWithinTheDay() {
        assertEquals(16, EditorRules.draggedWindowStart(16, 10f))
        assertEquals(17, EditorRules.draggedWindowStart(16, 20f))
        assertEquals(10, EditorRules.draggedWindowStart(16, -180f))
        assertEquals(0, EditorRules.draggedWindowStart(16, -24 * 60f))
        assertEquals(42, EditorRules.draggedWindowStart(16, 24 * 60f))
    }

    // ---- grab --------------------------------------------------------------------------------

    /** 50 px per step from the window start, 2 px per level. */
    private fun grab(spec: ProgramSpec, channel: Int, window: Int, x: Float, y: Float) =
        EditorRules.grab(spec, channel, window, x, y, radius = 28f, px = { (it - window) * 50f }, py = { 200f - it * 2f })

    @Test fun aPressGrabsTheNearestMovablePointWithin28Dp() {
        // C3 in the window 08:00..11:00: point 19 (09:30, 60 %) is at (150, 80).
        assertEquals(KeyPoint(19, 60), grab(aquarium, 2, 16, 150f, 80f))
        assertEquals(KeyPoint(19, 60), grab(aquarium, 2, 16, 150f + 27f, 80f))
        assertNull(grab(aquarium, 2, 16, 150f + 28f, 80f))
        // Between 19 (150, 80) and 20 (200, 40): the nearest wins.
        assertEquals(KeyPoint(20, 80), grab(aquarium, 2, 16, 180f, 50f))
        // Nothing near: nothing grabbed (no add, no selection).
        assertNull(grab(aquarium, 2, 16, 100f, 190f))
    }

    @Test fun mirrorsLinkedChannelsAndPointsOutsideTheWindowAreNotGrabbed() {
        // Window 20:00..23:00: only mirrors of C3 (hollow).
        val window = 40
        val mirror = aquarium.mirrorPoints(2).first { it.step == 42 }
        assertNull(grab(aquarium, 2, window, (42 - window) * 50f, 200f - mirror.level * 2f))
        val linked = aquarium.withLink(1, ChannelLink(0, 90))
        assertNull(grab(linked, 1, 16, 150f, 200f - 20 * 2f))
        // Point 23 is not in the window 16..22.
        val spec = points(0, 16 to 0, 23 to 100)
        assertNull(grab(spec, 0, 16, 7 * 50f, 0f))
    }

    // ---- snapping, R1, R2 --------------------------------------------------------------------

    @Test fun levelsSnapTo5AndTimesToTheHalfHour() {
        assertEquals(60, EditorRules.snapLevel(61.4f))
        assertEquals(65, EditorRules.snapLevel(62.5f))
        assertEquals(0, EditorRules.snapLevel(-7f))
        assertEquals(100, EditorRules.snapLevel(104f))
        val spec = points(0, 10 to 40)
        assertEquals(KeyPoint(11, 45), EditorRules.dragTarget(spec, 0, 8, KeyPoint(10, 40), 0.6f, 3f))
        assertEquals(KeyPoint(10, 40), EditorRules.dragTarget(spec, 0, 8, KeyPoint(10, 40), 0.4f, 2f))
    }

    @Test fun anImportedLevelKeepsItsValueUntilThePointIsMoved() {
        val spec = points(0, 10 to 37)
        assertEquals(KeyPoint(10, 37), EditorRules.dragTarget(spec, 0, 8, KeyPoint(10, 37), 0.2f, 2f))
        assertEquals(KeyPoint(11, 37), EditorRules.dragTarget(spec, 0, 8, KeyPoint(10, 37), 1f, -1f)) // time only
        assertEquals(KeyPoint(10, 40), EditorRules.dragTarget(spec, 0, 8, KeyPoint(10, 37), 0f, 3f))
    }

    @Test fun r1APointStops30MinFromItsNeighbours() {
        val spec = points(0, 10 to 0, 13 to 50, 15 to 100)
        assertEquals(11..14, EditorRules.dragRange(spec, 0, 13, 9))
        assertEquals(KeyPoint(11, 50), EditorRules.dragTarget(spec, 0, 9, KeyPoint(13, 50), -5f, 0f))
        assertEquals(KeyPoint(14, 50), EditorRules.dragTarget(spec, 0, 9, KeyPoint(13, 50), 5f, 0f))
        // Two neighbours 30 min apart: it cannot move at all.
        assertEquals(14..14, EditorRules.dragRange(points(0, 13 to 0, 14 to 0, 15 to 0), 0, 14, 10))
    }

    @Test fun r1WithSymmetryAMovablePointStaysBeforeTheAxis() {
        // Axis 15:30 = step 31: the last point may go to 15:00 (step 30), not onto the axis.
        val spec = aquarium.withPoint(2, 27, 100)
        assertEquals(25..30, EditorRules.dragRange(spec, 2, 27, 25))
        assertEquals(KeyPoint(30, 100), EditorRules.dragTarget(spec, 2, 25, KeyPoint(27, 100), 20f, 0f))
        // Axis at 15:15 (odd): 15:00 is before it, 15:30 after.
        val odd = spec.withAxis(61)
        assertEquals(25..30, EditorRules.dragRange(odd, 2, 27, 25))
        assertFalse(EditorRules.isBeforeAxis(spec, 31))
        assertTrue(EditorRules.isBeforeAxis(spec, 30))
    }

    @Test fun r2APointStopsAtTheWindowEdges() {
        val spec = points(0, 20 to 50)
        assertEquals(16..22, EditorRules.dragRange(spec, 0, 20, 16))
        assertEquals(KeyPoint(22, 50), EditorRules.dragTarget(spec, 0, 16, KeyPoint(20, 50), 10f, 0f))
        assertEquals(KeyPoint(16, 50), EditorRules.dragTarget(spec, 0, 16, KeyPoint(20, 50), -10f, 0f))
        // At the end of the day: 23:30 at most (24:00 is not a step).
        assertEquals(42..47, EditorRules.dragRange(points(0, 45 to 0), 0, 45, 42))
    }

    // ---- selection, Add, Delete ---------------------------------------------------------------

    @Test fun theSelectionIsKeptOnlyWhileItIsAMovablePointInTheWindow() {
        assertEquals(19, EditorRules.keptSelection(aquarium, 2, 16, 19))
        assertNull(EditorRules.keptSelection(aquarium, 2, 20, 19)) // left the window
        assertNull(EditorRules.keptSelection(aquarium.withoutPoint(2, 19), 2, 16, 19)) // gone
        assertNull(EditorRules.keptSelection(aquarium.withLink(2, ChannelLink(0, 50)), 2, 16, 19)) // linked
        assertNull(EditorRules.keptSelection(aquarium, 2, 16, null))
    }

    @Test fun addWithASelectionGoes30MinAfterItAtTheCurveLevel() {
        val spec = points(0, 16 to 0, 20 to 100)
        val levels = spec.levels()
        assertEquals(KeyPoint(17, 25), EditorRules.addTarget(spec, levels, 0, 16, 16))
        // That half-hour already has a point.
        assertNull(EditorRules.addTarget(points(0, 16 to 0, 17 to 50), points(0, 16 to 0, 17 to 50).levels(), 0, 16, 16))
        // It would leave the window.
        assertNull(EditorRules.addTarget(spec, levels, 0, 14, 20))
        // It would reach the symmetry axis (15:30).
        val sym = aquarium.withPoint(2, 30, 100)
        assertNull(EditorRules.addTarget(sym, sym.levels(), 2, 25, 30))
        // The curve level is kept as it is (not rounded): the curve does not change.
        val odd = points(0, 16 to 0, 19 to 10)
        assertEquals(KeyPoint(17, 3), EditorRules.addTarget(odd, odd.levels(), 0, 16, 16))
    }

    @Test fun addWithoutASelectionGoesToTheMiddleOfTheWindowRoundedTo5() {
        // Empty channel: 06:00..09:00, the point goes at 07:30, level 0.
        val empty = ProgramSpec.empty("t")
        assertEquals(KeyPoint(15, 0), EditorRules.addTarget(empty, empty.levels(), 0, 12, null))
        // Curve at 17 between 16 (0) and 19 (10): 3 → 5 once rounded.
        val odd = points(0, 16 to 0, 19 to 10)
        assertEquals(KeyPoint(19 - 2, 5), EditorRules.addTarget(odd, odd.levels(), 0, 14, null))
        // That half-hour already has a point.
        assertNull(EditorRules.addTarget(aquarium, aquarium.levels(), 2, 16, null)) // 19 exists
        // After the symmetry axis.
        assertNull(EditorRules.addTarget(aquarium, aquarium.levels(), 2, 40, null))
    }

    @Test fun addAndDeleteAreDisabledOnALinkedChannel() {
        val linked = aquarium.withLink(1, ChannelLink(0, 90))
        assertNull(EditorRules.addTarget(linked, linked.levels(), 1, 12, null))
        assertNull(EditorRules.addTarget(linked, linked.levels(), 1, 16, 17))
        assertFalse(EditorRules.canDelete(linked, 1, 17))
        assertFalse(EditorRules.canDelete(aquarium, 2, null))
        assertTrue(EditorRules.canDelete(aquarium, 2, 19))
    }

    @Test fun theHintTellsHollowPointsAndLinkedChannels() {
        assertEquals(EditorRules.Hint.DRAG, EditorRules.hint(aquarium, 2, 16))
        assertEquals(EditorRules.Hint.HOLLOW, EditorRules.hint(aquarium, 2, 40))
        assertEquals(EditorRules.Hint.DRAG, EditorRules.hint(ProgramSpec.empty("t"), 0, 12))
        assertEquals(EditorRules.Hint.LINKED, EditorRules.hint(aquarium.withLink(1, ChannelLink(0, 90)), 1, 16))
    }
}
