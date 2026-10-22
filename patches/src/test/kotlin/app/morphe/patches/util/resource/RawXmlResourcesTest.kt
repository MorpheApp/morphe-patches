package app.morphe.patches.util.resource

import kotlin.test.Test
import kotlin.test.assertEquals

class RawXmlResourcesTest {
    private val strings = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- comment <string name="skipped">x</string> -->
    <string name="app_name">Old</string>
    <string name="other" translatable="false">a &amp; b</string>
    <string name="empty" />
    <string-array name="arr">
        <item>one</item>
    </string-array>
</resources>
"""

    @Test
    fun scansNamedElements() {
        val found = mutableListOf<Triple<String, String, String?>>()
        forEachRawResourceElement(strings) { found += Triple(it.tag, it.name, it.inner?.trim()) }

        assertEquals(
            listOf(
                Triple("string", "app_name", "Old"),
                Triple("string", "other", "a &amp; b"),
                Triple("string", "empty", null),
                Triple("string-array", "arr", "<item>one</item>")
            ),
            found
        )
    }

    @Test
    fun setsAndRemovesStrings() {
        val set = strings.setStringResource("app_name", "A & B")
        assertEquals(true, set.contains("<string name=\"app_name\">A &amp; B</string>"))

        val added = strings.setStringResource("new_name", "New")
        assertEquals(true, added.contains("    <string name=\"new_name\">New</string>\n</resources>"))

        val removed = strings.removeStringResource("app_name")
        assertEquals(false, removed.contains("app_name"))
        assertEquals(true, removed.contains("name=\"other\""))
    }

    @Test
    fun transformsAndAppends() {
        val styles = "<resources>\n    <style name=\"S\">\n        <item name=\"a\">1</item>\n    </style>\n</resources>\n"
        val result = styles.transformRawResourceElements { style ->
            style.inner!!.transformRawResourceElements { item -> if (item.name == "a") "2" else null }
        }
        assertEquals(styles.replace(">1<", ">2<"), result)

        assertEquals(
            "<resources>\n    <color name=\"c\">#FFF</color>\n</resources>\n",
            "<resources>\n</resources>\n".appendResourceElements("    <color name=\"c\">#FFF</color>\n")
        )
    }
}
