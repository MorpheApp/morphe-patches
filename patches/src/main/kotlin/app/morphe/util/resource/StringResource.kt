package app.morphe.util.resource

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
            textContent = sanitizeAndroidResourceString(name, value)
        }

    override fun toString(): String {
        return "StringResource(value='$value')"
    }

    companion object {
        // Matches unescaped double quotes.
        private val UNESCAPED_DOUBLE_QUOTE = Regex("(?<!\\\\)\"")

        // Matches unescaped single or double quotes.
        private val UNESCAPED_QUOTE = Regex("(?<!\\\\)['\"]")

        /**
         * @param key String key
         * @param value Text to validate and sanitize
         * @param booleanThrowException If true, will throw an exception on problems; otherwise, sanitizes.
         * @return sanitized string
         */
        internal fun sanitizeAndroidResourceString(
            key: String,
            value: String,
            booleanThrowException: Boolean = false
        ): String {
            val logger = Logger.getLogger(StringResource::class.java.name)
            var sanitized = value

            // Could check for other invalid strings, but for now just check quotes.
            if (value.startsWith('"') && value.endsWith('"')) {
                // Raw strings allow unescaped single quotes but not double quotes.
                val inner = value.substring(1, value.length - 1)
                if (UNESCAPED_DOUBLE_QUOTE.containsMatchIn(inner)) {
                    val message = "String $key contains unescaped double quotes: $value"
                    if (booleanThrowException) throw IllegalArgumentException(message)
                    logger.warning(message)
                    sanitized = "\"" + UNESCAPED_DOUBLE_QUOTE.replace(inner, "") + "\""
                }
            } else {
                if (value.contains('\n')) {
                    val message = "String $key is not raw but contains newline characters: $value"
                    if (booleanThrowException) throw IllegalArgumentException(message)
                    logger.warning(message)
                }

                if (UNESCAPED_QUOTE.containsMatchIn(value)) {
                    val message = "String $key contains unescaped quotes: $value"
                    if (booleanThrowException) throw IllegalArgumentException(message)
                    logger.warning(message)
                    sanitized = UNESCAPED_QUOTE.replace(value, "")
                }
            }

            return sanitized
        }

        fun fromNode(node: Node): StringResource {
            val name = node.attributes.getNamedItem("name").textContent
            val value = node.textContent
            return StringResource(name, value)
        }
    }
}
