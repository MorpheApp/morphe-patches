/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

package app.morphe.patches.util.resource

import java.io.File

/**
 * Matches a string resource element. Groups: leading whitespace, opening tag start
 * (up to and including the name attribute value), name, inner text (null if self closing),
 * trailing line break.
 *
 * Editing the raw text of a strings.xml is much faster than parsing it as a DOM,
 * especially on Android where XML parsing is slow.
 */
private val STRING_RESOURCE = Regex(
    "([ \\t]*)(<string\\s[^>]*?name=\"([^\"]*)\"[^>]*?)(?:/>|>(.*?)</string>)(\\r?\\n)?",
    RegexOption.DOT_MATCHES_ALL
)

private const val RESOURCES_CLOSING_TAG = "</resources>"

private fun String.mayContainStringResource(name: String) = contains("name=\"$name\"")

/**
 * Escapes text so it can be used as the content of an XML element.
 */
internal fun escapeXmlText(text: String) = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * Removes all string resources with the given name.
 */
internal fun String.removeStringResource(name: String): String {
    if (!mayContainStringResource(name)) return this

    return STRING_RESOURCE.replace(this) { match ->
        if (match.groupValues[3] == name) "" else match.value
    }
}

/**
 * Sets the text of the string resource with the given name, and declares it if missing.
 *
 * @param value The unescaped text.
 */
internal fun String.setStringResource(name: String, value: String): String {
    val escapedValue = escapeXmlText(value)

    if (mayContainStringResource(name)) {
        var found = false
        val replaced = STRING_RESOURCE.replace(this) { match ->
            if (match.groupValues[3] != name) return@replace match.value

            found = true
            "${match.groupValues[1]}${match.groupValues[2]}>$escapedValue</string>${match.groupValues[5]}"
        }
        if (found) return replaced
    }

    val closingIndex = lastIndexOf(RESOURCES_CLOSING_TAG)
    if (closingIndex < 0) throw IllegalStateException("No closing resources tag")

    return substring(0, closingIndex).trimEnd() +
            "\n    <string name=\"$name\">$escapedValue</string>\n" +
            substring(closingIndex)
}

/**
 * Transforms the raw text of the string resources with any of the given names.
 */
internal fun String.transformStringResources(
    names: Set<String>,
    transform: (rawValue: String) -> String
): String = STRING_RESOURCE.replace(this) { match ->
    val rawValue = match.groups[4]?.value
    if (rawValue == null || match.groupValues[3] !in names) return@replace match.value

    "${match.groupValues[1]}${match.groupValues[2]}>${transform(rawValue)}</string>${match.groupValues[5]}"
}

/**
 * Rewrites the file with the result of [edit], if it changed anything.
 */
internal fun File.editText(edit: (text: String) -> String) {
    val text = readText()
    val edited = edit(text)
    if (edited != text) writeText(edited)
}
