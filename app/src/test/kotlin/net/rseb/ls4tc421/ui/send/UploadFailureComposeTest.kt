package net.rseb.ls4tc421.ui.send

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import ls4tc421.protocol.WriteStage
import net.rseb.ls4tc421.link.UploadFailure
import net.rseb.ls4tc421.link.UploadPhase
import net.rseb.ls4tc421.link.UploadRequest
import net.rseb.ls4tc421.model.Aquarium
import net.rseb.ls4tc421.ui.slots.EmptyingPanel
import net.rseb.ls4tc421.ui.slots.EmptyingRun
import net.rseb.ls4tc421.ui.theme.LsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val RESTART_HINT = "Si le boîtier ne répond plus, redémarrez-le"

/**
 * The two closing frames are named apart (D32), and a failure of the `04 0A` alone says that the
 * write is in place, without the restart advice; in French. The failures shown here are the ones the
 * simulator produces in `SlotScreensTest` (emptying of slots 5 and 10, send of « aqua » to slot 4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "fr")
class UploadFailureComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun emptyingPanel(run: EmptyingRun) {
        compose.setContent {
            LsTheme(darkTheme = true) { EmptyingPanel(setOf(4, 9), run, null, null, {}, {}, {}, {}) }
        }
    }

    @Test fun endProgramAndFinishHaveTheirOwnNames() {
        compose.setContent {
            LsTheme(darkTheme = true) {
                Column {
                    Text(stageName(WriteStage.EndProgram))
                    Text(stageName(WriteStage.Finish))
                }
            }
        }
        compose.onNodeWithText("La trame de fin du programme").assertExists()
        compose.onNodeWithText("La trame de fin d’échange").assertExists()
    }

    @Test fun emptying_onlyTheCloseFailed_slotsEmptiedNoRestartAdvice() {
        emptyingPanel(EmptyingRun.Failed(9, 2, 2, UploadPhase.Failed(WriteStage.Finish, 1, 1, UploadFailure.TimedOut(2))))
        compose.onNodeWithText(
            "Les 2 emplacements sont vidés. Seule la fin de l’échange n’a pas été confirmée par le boîtier.",
        ).assertExists()
        compose.onNodeWithText(RESTART_HINT, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Reprendre").assertExists()
    }

    @Test fun emptying_oneSlot_onlyTheCloseFailed_saysItInTheSingular() {
        emptyingPanel(EmptyingRun.Failed(4, 1, 1, UploadPhase.Failed(WriteStage.Finish, 1, 1, UploadFailure.TimedOut(2))))
        compose.onNodeWithText(
            "L’emplacement est vidé. Seule la fin de l’échange n’a pas été confirmée par le boîtier.",
        ).assertExists()
        compose.onNodeWithText("Les 1 emplacements", substring = true).assertDoesNotExist()
        compose.onNodeWithText(RESTART_HINT, substring = true).assertDoesNotExist()
    }

    @Test fun emptying_failureAtAStep_keepsTheFailureTextAndTheRestartAdvice() {
        emptyingPanel(EmptyingRun.Failed(9, 2, 2, UploadPhase.Failed(WriteStage.Step(8), 10, 50, UploadFailure.TimedOut(2))))
        compose.onNodeWithText("aucun accusé après 2 essais.", substring = true).assertExists()
        compose.onNodeWithText(RESTART_HINT, substring = true).assertExists()
        compose.onNodeWithText("Seule la fin de l’échange", substring = true).assertDoesNotExist()
    }

    @Test fun emptying_failureAtTheEndOfAProgram_keepsTheFailureTextAndTheRestartAdvice() {
        emptyingPanel(EmptyingRun.Failed(4, 1, 2, UploadPhase.Failed(WriteStage.EndProgram, 50, 50, UploadFailure.TimedOut(2))))
        compose.onNodeWithText("La trame de fin du programme (trame 50 sur 50) :", substring = true).assertExists()
        compose.onNodeWithText(RESTART_HINT, substring = true).assertExists()
    }

    private val sent = UploadRequest(deviceId = 1, programId = 1, program = Aquarium.RISE_ONLY.toProgram(), slot = 3)

    @Test fun send_onlyTheCloseFailed_programWrittenNoRestartAdvice() {
        compose.setContent {
            LsTheme(darkTheme = true) {
                SendFailed(UploadPhase.Failed(WriteStage.Finish, 51, 51, UploadFailure.TimedOut(2)), sent, {}, {})
            }
        }
        compose.onNodeWithText(
            "« aqua » est écrit dans l’emplacement 4. Seule la fin de l’échange n’a pas été confirmée par le boîtier.",
        ).assertExists()
        compose.onNodeWithText(RESTART_HINT, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Reprendre").assertExists()
    }

    @Test fun send_failureAtAStep_keepsTheFailureTextAndTheRestartAdvice() {
        compose.setContent {
            LsTheme(darkTheme = true) {
                SendFailed(UploadPhase.Failed(WriteStage.Step(8), 10, 51, UploadFailure.TimedOut(2)), sent, {}, {})
            }
        }
        compose.onNodeWithText("aucun accusé après 2 essais.", substring = true).assertExists()
        compose.onNodeWithText(RESTART_HINT, substring = true).assertExists()
        compose.onNodeWithText("Seule la fin de l’échange", substring = true).assertDoesNotExist()
    }
}
