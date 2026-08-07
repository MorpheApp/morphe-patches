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
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks resource strings for invalid strings that will fail resource compilation.
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
        "reddit"
    ).forEach { appId ->
        localesAll.forEach { locale ->
            val srcFolderName = locale.getSrcLocaleFolderName()
            val srcSubPath = "$srcFolderName/$appId/strings.xml"

            inputStreamFromBundledResource(
                "addresources", srcSubPath
            ).use { stream ->
                if (stream == null) throw IllegalArgumentException("Could not find resource $srcSubPath")
                val document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(stream)

                val nodeList = document.getElementsByTagName("string")
                for (i in 0 until nodeList.length) {
                    val node = nodeList.item(i)
                    if (node.nodeType == Node.ELEMENT_NODE) {
                        val element = node as Element
                        val name = element.getAttribute("name")
                        val value = element.textContent
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
