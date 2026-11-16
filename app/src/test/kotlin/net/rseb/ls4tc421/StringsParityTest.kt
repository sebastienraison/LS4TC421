package net.rseb.ls4tc421

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** `values/` (English, default) and `values-fr/` (French) must define the same translatable keys. */
class StringsParityTest {
    private fun keys(path: String): Set<String> {
        val file = File("src/main/res/$path/strings.xml") // Gradle runs unit tests from the module directory
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val out = sortedSetOf<String>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.getAttribute("translatable") == "false") continue
            out += "${node.tagName}:${node.getAttribute("name")}"
        }
        return out
    }

    @Test fun englishAndFrenchHaveTheSameKeys() {
        val en = keys("values")
        val fr = keys("values-fr")
        assertTrue(en.isNotEmpty())
        assertEquals("only in English", emptySet<String>(), en - fr)
        assertEquals("only in French", emptySet<String>(), fr - en)
    }
}
