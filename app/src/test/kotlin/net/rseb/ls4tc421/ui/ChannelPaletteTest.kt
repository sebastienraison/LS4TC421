package net.rseb.ls4tc421.ui

import androidx.compose.ui.graphics.toArgb
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.ui.theme.ChannelPalette
import net.rseb.ls4tc421.ui.theme.isUnusedChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ChannelPaletteTest {
    @Test fun thePaletteHasFourteenEntriesWithUniqueIds() {
        val ids = ChannelPalette.colors.map { it.id }
        assertEquals(14, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun theDefaultsAreTheFiveFormerColours() {
        assertEquals(listOf("violet", "ochre", "pink", "brown", "slate"), ChannelPalette.defaults.map { it.id })
        // Unchanged hues, the same in both themes.
        assertEquals(
            listOf(0xFF8A63D2, 0xFFC08A1E, 0xFFD2609E, 0xFF8B6248, 0xFF6E8296).map { it.toInt() },
            ChannelPalette.defaults.map { it.light.toArgb() },
        )
        ChannelPalette.defaults.forEach { assertEquals(it.light, it.dark) }
    }

    @Test fun missingOrUnknownIdsGiveTheChannelsDefault() {
        assertEquals(ChannelPalette.defaults, ChannelPalette.resolve(emptyList()))
        assertEquals(ChannelPalette.defaults, ChannelPalette.resolve(List(5) { null }))
        assertEquals(
            listOf("red", "ochre", "pink", "white", "slate"),
            ChannelPalette.resolve(listOf("red", "no_such_colour", null, "white", "")).map { it.id },
        )
    }

    @Test fun unusedIsTransparentAndTheOnlyOneSo() {
        val unused = ChannelPalette.find(ChannelPalette.UNUSED)!!
        assertTrue(unused.light.isUnusedChannel && unused.dark.isUnusedChannel)
        assertEquals(listOf(ChannelPalette.UNUSED), ChannelPalette.colors.filter { it.light.isUnusedChannel || it.dark.isUnusedChannel }.map { it.id })
    }

    @Test fun anUnusedChannelWithLightIsFlaggedAndOnlyThatOne() {
        val dark = List(48) { List(5) { 0 } }
        val lit = List(48) { step -> List(5) { channel -> if (channel == 4 && step == 20) 1 else 0 } }
        val unused = listOf(false, false, false, true, true)
        assertEquals(List(5) { false }, ChannelPalette.unusedButLit(unused, dark))
        assertEquals(listOf(false, false, false, false, true), ChannelPalette.unusedButLit(unused, lit))
        assertEquals(List(5) { false }, ChannelPalette.unusedButLit(List(5) { false }, lit))
    }

    @Test fun everyColourHasALabelInEnglishAndFrench() {
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val en = stringNames("values")
        val fr = stringNames("values-fr")
        ChannelPalette.colors.forEach { color ->
            val name = names[color.label]
            assertEquals("label of ${color.id}", "colour_${color.id}", name)
            assertTrue("$name in values/", name in en)
            assertTrue("$name in values-fr/", name in fr)
        }
    }

    private fun stringNames(dir: String): Set<String> {
        val file = File("src/main/res/$dir/strings.xml") // Gradle runs unit tests from the module directory
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.getElementsByTagName("string")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("name") }.toSet()
    }
}
