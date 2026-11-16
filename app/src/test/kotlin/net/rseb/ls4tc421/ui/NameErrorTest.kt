package net.rseb.ls4tc421.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import net.rseb.ls4tc421.ui.programs.nameError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The name check at entry (D23), with the English texts: empty, too long, refused character, valid. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NameErrorTest {
    @get:Rule val compose = createComposeRule()

    private fun errors(vararg names: String): List<String?> {
        var out: List<String?> = emptyList()
        compose.setContent { out = names.map { nameError(it) } }
        compose.waitForIdle()
        return out
    }

    @Test fun nameErrorFollowsTheControllerRule() {
        val result = errors("", "   ", "court_ba", "court_basique", "été", "a☀", "court_b", "  a b~ ", "~")
        assertEquals("Enter a name.", result[0])
        assertEquals("Enter a name.", result[1]) // trimmed, as it is saved
        assertEquals("8 characters: the controller’s screen shows 7 at most.", result[2])
        assertEquals("13 characters: the controller’s screen shows 7 at most.", result[3])
        assertEquals(
            "Letters without accents, digits, spaces and common symbols only: the controller cannot display “é”.",
            result[4],
        )
        assertEquals(
            "Letters without accents, digits, spaces and common symbols only: the controller cannot display “☀”.",
            result[5],
        )
        assertNull(result[6])
        assertNull(result[7])
        assertNull(result[8])
    }
}
