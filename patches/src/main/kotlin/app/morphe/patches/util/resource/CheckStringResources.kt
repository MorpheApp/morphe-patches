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

import app.morphe.patches.all.misc.resources.localesAll
import app.morphe.patches.util.resource.StringResourceSanitizer.sanitizeAndroidResourceString
import app.morphe.util.inputStreamFromBundledResource
import app.morphe.patcher.patch.readXml

/**
 * Matches format parameters and the escaped percent sign.
 * Width and space flags are intentionally not matched, so literal percent text
 * such as "100% sure" or "%100de" is not treated as a parameter.
 */
private val FORMAT_PARAMETER = Regex("""%%|%(?:(\d+)\$)?((?:\.\d+)?[sdf])""")

/**
 * @return The format parameters of a string, with all parameters in indexed form.
 *         `%s` and `%1$s` are the same parameter, and a parameter used twice is listed once.
 */
private fun getFormatParameters(value: String): Set<String> {
    var ordinaryIndex = 0
    return FORMAT_PARAMETER.findAll(value)
        .filter { it.value != "%%" }
        .map { match ->
            val index = match.groupValues[1].ifEmpty { (++ordinaryIndex).toString() }
            "%$index$" + match.groupValues[2]
        }
        .toSortedSet()
}

/**
 * Checks resource strings for invalid strings that will fail resource compilation,
 * and for translations that use different format parameters than the English string.
 */
@Suppress("unused")
internal fun main(args: Array<String>) {
    var stringsChecked = 0

    val exceptions = mutableListOf<Exception>()

    arrayOf(
        "music",
        "shared",
        "shared-youtube",
        "youtube",
        "reddit",
        "sponsorblock"
    ).forEach { appId ->
        val englishSubPath = "values/$appId/strings.xml"
        val englishParameters = inputStreamFromBundledResource(
            "addresources", englishSubPath
        ).use { stream ->
            if (stream == null) throw IllegalArgumentException("Could not find resource $englishSubPath")
            readXml(stream).use { strings ->
                strings.elements("string").associate { element ->
                    element.attribute("name").orEmpty() to getFormatParameters(element.text)
                }
            }
        }

        localesAll.forEach { locale ->
            val srcFolderName = locale.getSrcLocaleFolderName()
            val srcSubPath = "$srcFolderName/$appId/strings.xml"

            inputStreamFromBundledResource(
                "addresources", srcSubPath
            ).use { stream ->
                if (stream == null) throw IllegalArgumentException("Could not find resource $srcSubPath")
                readXml(stream).use { strings ->
                    strings.elements("string").forEach { element ->
                        val name = element.attribute("name").orEmpty()
                        val value = element.text
                        try {
                            sanitizeAndroidResourceString(
                                key = name,
                                value = value,
                                filePath = srcSubPath,
                                throwException = true
                            )
                        } catch (e: Exception) {
                            exceptions += e
                        }

                        val expectedParameters = englishParameters[name]
                        if (expectedParameters != null) {
                            val parameters = getFormatParameters(value)
                            if (parameters != expectedParameters) {
                                exceptions += IllegalArgumentException(
                                    "$srcSubPath String $name has format parameters $parameters " +
                                            "but English has $expectedParameters: $value"
                                )
                            }
                        }

                        stringsChecked++
                    }
                }
            }
        }
    }

    if (exceptions.isNotEmpty()) {
        val builder = StringBuilder("\n")
        exceptions.forEach { exception ->
            builder.appendLine(exception.message)
        }
        throw IllegalStateException(builder.toString())
    }

    println("Verified $stringsChecked strings, no issues found")
}
