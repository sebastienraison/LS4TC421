package net.rseb.ls4tc421.ui

import net.rseb.ls4tc421.ui.slots.GridMode
import net.rseb.ls4tc421.ui.slots.TileEmphasis
import net.rseb.ls4tc421.ui.slots.tileEmphasis
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sign a slot tile shows: selection, then « in service », then the state. */
class TileEmphasisTest {
    @Test fun theSlotInServiceIsGreenWhenNothingSelectsIt() {
        for (mode in GridMode.entries) {
            assertEquals(mode.name, TileEmphasis.IN_SERVICE, tileEmphasis(active = true, mode = mode, marked = false))
        }
    }

    @Test fun aCheckedTileStaysCheckedEvenInService() {
        assertEquals(TileEmphasis.CHECKED, tileEmphasis(active = true, mode = GridMode.SELECT_MANY, marked = true))
        assertEquals(TileEmphasis.CHECKED, tileEmphasis(active = false, mode = GridMode.SELECT_MANY, marked = true))
    }

    @Test fun aChosenTileStaysChosenEvenInService() {
        assertEquals(TileEmphasis.CHOSEN, tileEmphasis(active = true, mode = GridMode.PICK_ONE, marked = true))
        assertEquals(TileEmphasis.CHOSEN, tileEmphasis(active = false, mode = GridMode.PICK_ONE, marked = true))
    }

    @Test fun otherTilesShowTheirState() {
        for (mode in GridMode.entries) {
            assertEquals(mode.name, TileEmphasis.STATE, tileEmphasis(active = false, mode = mode, marked = false))
        }
        // On the device page, nothing is marked: a stray mark changes nothing.
        assertEquals(TileEmphasis.IN_SERVICE, tileEmphasis(active = true, mode = GridMode.BROWSE, marked = true))
        assertEquals(TileEmphasis.STATE, tileEmphasis(active = false, mode = GridMode.BROWSE, marked = true))
    }
}
