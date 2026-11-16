package net.rseb.ls4tc421.model

import ls4tc421.protocol.Program
import ls4tc421.protocol.ProgramName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's 7-character rule (D23, amended on 2026-11-16), over the library's 9. */
class AppNameTest {
    @Test fun upToSevenCharactersPass() {
        assertNull(AppName.check("court"))
        assertNull(AppName.check("aquariu"))
        assertNull(AppName.check("Vide 03"))
        assertNull(AppName.check("Free 20"))
    }

    @Test fun eightAndNineAreRefusedByTheAppButNotByTheLibrary() {
        // What the screen showed on 2026-11-16: « tropica », « aquariu », « abcdefg ».
        for (name in listOf("tropical", "aquarium", "abcdefghi")) {
            assertEquals(ProgramName.Problem.TooLong(name.length), AppName.check(name))
            assertNull(ProgramName.check(name))
        }
    }

    @Test fun theLibraryRulesComeFirst() {
        assertEquals(ProgramName.Problem.Empty, AppName.check(""))
        assertEquals(ProgramName.Problem.Refused("é"), AppName.check("été"))
        assertEquals(ProgramName.Problem.TooLong(10), AppName.check("aquarium_1"))
    }

    @Test fun aProgramSavedWithAnEightCharacterNameStaysButIsNotSendable() {
        val spec = ProgramSpec.fromSteps("tropical", List(48) { listOf(0, 0, 0, 0, 0) })
        assertFalse(spec.sendable)
        assertNull(spec.toProgramOrNull())
        val renamed = spec.copy(name = "tropica")
        assertTrue(renamed.sendable)
        assertEquals("tropica", renamed.toProgramOrNull()?.name)
        Program("tropical", spec.levels()) // the library alone still accepts it
    }
}
