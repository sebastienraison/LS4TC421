package net.rseb.ls4tc421.ui

import net.rseb.ls4tc421.data.SlotState
import net.rseb.ls4tc421.model.Aquarium
import net.rseb.ls4tc421.ui.slots.Approx
import net.rseb.ls4tc421.ui.slots.InService
import net.rseb.ls4tc421.ui.slots.LightWarning
import net.rseb.ls4tc421.ui.slots.SlotTile
import net.rseb.ls4tc421.ui.slots.TileLook
import net.rseb.ls4tc421.ui.slots.emptyingDuration
import net.rseb.ls4tc421.ui.slots.inService
import net.rseb.ls4tc421.ui.slots.lightWarning
import net.rseb.ls4tc421.ui.slots.slotTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device page's model of the slots (D31): the 20 tiles, the « In service » card, emptying texts. */
class SlotTilesTest {
    /** The mock-ups' example: 1 « aquarium » written, 3 « Vide 03 » emptied, 4 emptied as new, 5 declared empty. */
    private val states: List<SlotState> = List(20) { slot ->
        when (slot) {
            0 -> SlotState.Written(0, 7L, "aquarium", 100L, Aquarium.STEPS)
            2 -> SlotState.Emptied(2, "Vide 03", 200L)
            3 -> SlotState.Emptied(3, " ", 250L)
            4 -> SlotState.DeclaredEmpty(4, 300L)
            else -> SlotState.Unknown(slot)
        }
    }

    @Test fun tilesFollowTheFourStatesAndTheActiveSlot() {
        val tiles = slotTiles(states, activeSlot = 0)
        assertEquals(20, tiles.size)
        assertEquals((0..19).toList(), tiles.map { it.slot })
        assertEquals(SlotTile(0, TileLook.WRITTEN, "aquarium", active = true), tiles[0])
        assertEquals(SlotTile(1, TileLook.UNKNOWN, null, active = false), tiles[1])
        assertEquals(SlotTile(2, TileLook.EMPTIED, "Vide 03", active = false), tiles[2])
        // Emptied « as new » (one space): no name, shown as « empty ».
        assertEquals(SlotTile(3, TileLook.EMPTIED, null, active = false), tiles[3])
        assertTrue(tiles[3].showsEmpty)
        assertFalse(tiles[2].showsEmpty)
        assertEquals(SlotTile(4, TileLook.DECLARED_EMPTY, null, active = false), tiles[4])
        assertTrue(tiles[4].showsEmpty)
        assertTrue(tiles.drop(5).all { it.look == TileLook.UNKNOWN && !it.active && it.name == null })
        assertEquals(listOf(0), tiles.filter { it.active }.map { it.slot })
    }

    @Test fun noDeclaration_noActiveTile() {
        assertTrue(slotTiles(states, activeSlot = null).none { it.active })
    }

    @Test fun inService_declaredWritten_hasItsNameAndReplay() {
        val card = inService(states, activeSlot = 0) as InService.Declared
        assertEquals(0, card.slot)
        assertEquals("aquarium", card.name)
        assertEquals(Aquarium.STEPS, card.replay)
    }

    @Test fun inService_withoutDeclaration() {
        assertEquals(InService.NotDeclared, inService(states, activeSlot = null))
    }

    @Test fun inService_replayOnlyWhenTheAppWroteTheCurve() {
        val emptied = inService(states, activeSlot = 2) as InService.Declared
        assertEquals("Vide 03", emptied.name)
        assertNull(emptied.replay)
        val unknown = inService(states, activeSlot = 7) as InService.Declared
        assertNull(unknown.name)
        assertNull(unknown.replay)
        assertEquals(SlotState.Unknown(7), unknown.state)
        assertNull((inService(states, activeSlot = 3) as InService.Declared).name) // as new
        assertNull((inService(states, activeSlot = 4) as InService.Declared).replay)
        assertNotNull((inService(states, activeSlot = 0) as InService.Declared).replay)
    }

    @Test fun announcedDuration_aboutNineSecondsPerSlot() {
        assertEquals(Approx.Seconds(10), emptyingDuration(1)) // 9 s
        assertEquals(Approx.Seconds(30), emptyingDuration(3)) // 27 s, as on the mock-up
        assertEquals(Approx.Minutes(3), emptyingDuration(20)) // 180 s, as on the mock-up
        assertEquals(Approx.Seconds(50), emptyingDuration(6)) // 54 s
        assertEquals(Approx.Minutes(1), emptyingDuration(7)) // 63 s
    }

    @Test fun lightWarning_onlyWhenTheSlotInServiceIsChecked() {
        assertEquals(LightWarning.ACTIVE_SELECTED, lightWarning(setOf(0, 3), activeSlot = 0))
        assertEquals(LightWarning.NONE, lightWarning(setOf(3, 5, 6), activeSlot = 0))
        assertEquals(LightWarning.GENERIC, lightWarning(setOf(3), activeSlot = null))
        assertEquals(LightWarning.ACTIVE_SELECTED, lightWarning((0..19).toSet(), activeSlot = 12))
    }
}
