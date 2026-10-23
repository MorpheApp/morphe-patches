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

import org.w3c.dom.Document
import org.w3c.dom.Node
import java.util.logging.Logger

/**
 * A string value.
 * Represents a string in the strings.xml file.
 *
 * @param name The name of the string.
 * @param value The value of the string.
 */
class StringResource(
    name: String,
    val value: String
) : BaseResource(name, "string") {
    override fun serialize(ownerDocument: Document, resourceCallback: (BaseResource) -> Unit) =
        super.serialize(ownerDocument, resourceCallback).apply {
            textContent = StringResourceSanitizer.sanitizeAndroidResourceString(name, value)
        }

    override fun toString(): String {
        return "StringResource(value='$value')"
    }

    companion object {
        @Suppress("unused")
        fun fromNode(node: Node): StringResource {
            val name = node.attributes.getNamedItem("name").textContent
            val value = node.textContent
            return StringResource(name, value)
        }
    }
}

object StringResourceSanitizer {
    private val logger = Logger.getLogger(StringResourceSanitizer::class.java.name)

    // Matches unescaped double quotes.
    private val UNESCAPED_DOUBLE_QUOTE = Regex("(?<!\\\\)\"")

    // Matches unescaped single or double quotes.
    private val UNESCAPED_QUOTE = Regex("(?<!\\\\)['\"]")

    /**
     * @param key String key
     * @param value Text to validate and sanitize
     * @param filePath Path to include in any exception thrown.
     * @param throwException If true, will throw an exception on problems; otherwise, sanitizes.
     * @return sanitized string
     */
    fun sanitizeAndroidResourceString(
        key: String,
        value: String,
        filePath: String? = null,
        throwException: Boolean = false
    ): String {
        var sanitized = value

        // Could check for other invalid strings, but for now just check quotes.
        if (value.startsWith('"') && value.endsWith('"')) {
            // Raw strings allow unescaped single quotes but not double quotes.
            val inner = value.substring(1, value.length - 1)
            if (UNESCAPED_DOUBLE_QUOTE.containsMatchIn(inner)) {
                val message = "$filePath String $key contains unescaped double quotes: $value"
                if (throwException) throw IllegalArgumentException(message)
                logger.warning(message)
                sanitized = "\"" + UNESCAPED_DOUBLE_QUOTE.replace(inner, "") + "\""
            }
        } else {
            if (value.contains('\n')) {
                val message = "$filePath String $key is not raw but contains newline characters: $value"
                if (throwException) throw IllegalArgumentException(message)
                logger.warning(message)
            }

            // Most strings have no quotes, and checking for them first is much faster than the regex.
            if ((value.contains('\'') || value.contains('"')) && UNESCAPED_QUOTE.containsMatchIn(value)) {
                val message = "$filePath String $key contains unescaped quotes: $value"
                if (throwException) throw IllegalArgumentException(message)
                logger.warning(message)
                sanitized = UNESCAPED_QUOTE.replace(value, "")
            }
        }

        return sanitized
    }
}
