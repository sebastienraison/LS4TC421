package net.rseb.ls4tc421.model

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import ls4tc421.protocol.Program
import ls4tc421.protocol.ProgramName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** « Empty a slot » (D30): the name and the program written, on the JVM. */
class EmptySlotTest {
    /** The translated « Empty %1$02d » of a resource folder, read from the XML as Android would. */
    private fun template(folder: String): String {
        val file = File("src/main/res/$folder/strings.xml") // Gradle runs unit tests from the module directory
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as Element
            if (node.getAttribute("name") == "empty_slot_name") return node.textContent
        }
        throw AssertionError("empty_slot_name missing in $folder")
    }

    private val french = "Vide %1\$02d"
    private val english = "Free %1\$02d"

    @Test fun theTranslationsAreTheOnesOfTheBrief() {
        assertEquals(english, template("values"))
        assertEquals(french, template("values-fr"))
    }

    @Test fun numberedNameIsTheShownNumberOnTwoDigits() {
        // Protocol slots 0, 8, 9, 19 are shown 1, 9, 10, 20.
        assertEquals("Vide 01", EmptySlot.name(0, EmptySlotNaming.NUMBERED, french))
        assertEquals("Vide 09", EmptySlot.name(8, EmptySlotNaming.NUMBERED, french))
        assertEquals("Vide 10", EmptySlot.name(9, EmptySlotNaming.NUMBERED, french))
        assertEquals("Vide 20", EmptySlot.name(19, EmptySlotNaming.NUMBERED, french))
        assertEquals("Free 03", EmptySlot.name(2, EmptySlotNaming.NUMBERED, english))
    }

    @Test fun asNewNameIsOneSpace() {
        for (slot in 0..19) {
            assertEquals(" ", EmptySlot.name(slot, EmptySlotNaming.AS_NEW, french))
            assertEquals(byteArrayOf(0x20).toList(), EmptySlot.program(slot, EmptySlotNaming.AS_NEW, english).name.toByteArray(Charsets.US_ASCII).toList())
        }
    }

    @Test fun theProgramIsAtZeroOnFiveChannelsAndFortyEightSteps() {
        val program: Program = EmptySlot.program(2, EmptySlotNaming.NUMBERED, french)
        assertEquals("Vide 03", program.name)
        assertEquals(48, program.steps.size)
        assertTrue(program.steps.all { it.size == 5 && it.all { level -> level == 0 } })
    }

    @Test fun everyNameOfBothLanguagesFollowsTheRuleForSending() {
        // Breaks if a future translation goes beyond 7 characters or leaves printable ASCII (D23, amended 16/11).
        val names = listOf("values", "values-fr").flatMap { folder ->
            val template = template(folder)
            (0..19).map { slot -> EmptySlot.name(slot, EmptySlotNaming.NUMBERED, template) }
        }
        assertEquals(40, names.size)
        for (name in names) {
            assertNull("$name: ${AppName.check(name)}", AppName.check(name))
            Program(name, EmptySlot.levels) // the library's own check (Validation.name) accepts it
        }
        assertNull(AppName.check(EmptySlot.AS_NEW_NAME))
    }

    @Test fun digitsStayAsciiWhateverTheDefaultLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("Vide 07", EmptySlot.name(6, EmptySlotNaming.NUMBERED, french))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun anUnknownOrMissingSettingIsNumbered() {
        assertEquals(EmptySlotNaming.NUMBERED, EmptySlotNaming.fromKey(null))
        assertEquals(EmptySlotNaming.NUMBERED, EmptySlotNaming.fromKey("removed_value"))
        assertEquals(EmptySlotNaming.AS_NEW, EmptySlotNaming.fromKey("as_new"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSlotOutsideTheTwentyIsRefused() {
        EmptySlot.name(20, EmptySlotNaming.NUMBERED, french)
    }
}
