package net.rseb.ls4tc421.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.rseb.ls4tc421.data.SlotState
import net.rseb.ls4tc421.link.UploadPhase
import net.rseb.ls4tc421.model.Aquarium
import net.rseb.ls4tc421.ui.slots.EmptyingPanel
import net.rseb.ls4tc421.ui.slots.EmptyingRun
import net.rseb.ls4tc421.ui.slots.GridMode
import net.rseb.ls4tc421.ui.slots.SlotGrid
import net.rseb.ls4tc421.ui.slots.SlotLegend
import net.rseb.ls4tc421.ui.slots.slotTiles
import net.rseb.ls4tc421.ui.theme.LsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The slot grid and the emptying panel render, with the texts of the mock-ups (D31), in French. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "fr")
class SlotComposeTest {
    @get:Rule val compose = createComposeRule()

    private val states: List<SlotState> = List(20) { slot ->
        when (slot) {
            0 -> SlotState.Written(0, 7L, "aquarium", 100L, Aquarium.STEPS)
            2 -> SlotState.Emptied(2, "Vide 03", 200L)
            3 -> SlotState.Emptied(3, " ", 250L)
            4 -> SlotState.DeclaredEmpty(4, 300L)
            else -> SlotState.Unknown(slot)
        }
    }

    private fun show(selection: Set<Int>, activeSlot: Int?, activeName: String?, run: EmptyingRun = EmptyingRun.Idle, onTap: (Int) -> Unit = {}) {
        compose.setContent {
            LsTheme(darkTheme = true) {
                Column {
                    SlotGrid(slotTiles(states, activeSlot), onTap = onTap, mode = GridMode.SELECT_MANY, marked = selection)
                    SlotLegend()
                    EmptyingPanel(selection, run, activeSlot, activeName, {}, {}, {}, {})
                }
            }
        }
    }

    @Test fun gridShowsNamesEmptyAndUnknown() {
        val tapped = mutableListOf<Int>()
        show(setOf(3, 5, 6), activeSlot = 0, activeName = "aquarium", onTap = { tapped += it })
        compose.onNodeWithText("aquarium").assertExists()
        compose.onNodeWithText("Vide 03").assertExists()
        compose.onAllNodesWithText("vide").assertCountEquals(2) // emptied as new, declared empty
        compose.onAllNodesWithText("?").assertCountEquals(16)
        compose.onNodeWithText("écrit par l’app").assertExists()
        compose.onNodeWithText("déclaré vide").assertExists()
        compose.onNodeWithText("inconnu").assertExists()
        compose.onNodeWithText("Vide 03").performClick()
        assertEquals(listOf(2), tapped)
    }

    @Test fun threeChecked_inServiceNotChecked_mockUp6() {
        show(setOf(3, 5, 6), activeSlot = 0, activeName = "aquarium")
        compose.onNodeWithText(
            "Les emplacements 4, 6 et 7 reçoivent un programme à 0 %. Leur contenu actuel est perdu. Environ 30 secondes.",
        ).assertExists()
        compose.onNodeWithText("Vider 3 emplacements").assertExists()
    }

    @Test fun allChecked_inServiceChecked_mockUp7() {
        show((0..19).toSet(), activeSlot = 0, activeName = "aquarium")
        compose.onNodeWithText(
            "La lumière s’éteindra : « aquarium », en service, est coché. Les 20 emplacements reçoivent un programme à 0 %. " +
                "Environ 3 minutes, près du boîtier.",
        ).assertExists()
        compose.onNodeWithText("Vider 20 emplacements").assertExists()
    }

    @Test fun noDeclaration_genericWarning() {
        show(setOf(7), activeSlot = null, activeName = null)
        compose.onNodeWithText(
            "L’emplacement 8 reçoit un programme à 0 %. Son contenu actuel est perdu. " +
                "Si c’est celui en service sur le boîtier, la lumière s’éteint. Environ 10 secondes.",
        ).assertExists()
    }

    @Test fun progressAndDone() {
        show(setOf(6), activeSlot = null, activeName = null, run = EmptyingRun.Writing(6, 3, 20, UploadPhase.Sending(10, 50)))
        compose.onNodeWithText("Emplacement 7 · 3 sur 20").assertExists()
    }

    @Test fun done() {
        show(setOf(6), activeSlot = null, activeName = null, run = EmptyingRun.Done(3))
        compose.onNodeWithText("3 emplacements vidés.").assertExists()
    }
}
