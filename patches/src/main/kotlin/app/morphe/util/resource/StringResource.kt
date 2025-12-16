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
 * @param formatted If the string is formatted. Defaults to `true`.
 */
class StringResource(
    name: String,
    val value: String
) : BaseResource(name, "string") {
    override fun serialize(ownerDocument: Document, resourceCallback: (BaseResource) -> Unit) =
        super.serialize(ownerDocument, resourceCallback).apply {

            fun String.validateAndroidStringEscaping() : String {
                if (value.startsWith('"') && value.endsWith('"')) {
                    // Raw strings allow unescaped single quote but not double quote.
                    if (!value.substring(1, value.length - 1).contains(Regex("(?<!\\\\)[\"]"))) {
                        return this
                    }
                } else {
                    if (value.contains('\n')) {
                        // Don't throw an exception, otherwise unnoticed mistakes
                        // in Crowdin can cause patching failures.
                        // Incorrectly escaped strings still work but do not display as intended.
                        Logger.getLogger(StringResource.javaClass.name).warning(
                            "String $name is not raw but contains encoded new line characters: $value")
                    }
                    if (!value.contains(Regex("(?<!\\\\)['\"]"))) {
                        return this
                    }
                }

                Logger.getLogger(StringResource.javaClass.name).warning(
                    "String $name cannot contain unescaped quotes in value: $value")

                return this
            }

            textContent = value.validateAndroidStringEscaping()
        }

    override fun toString(): String {
        return "StringResource(value='$value')"
    }

    companion object {
        fun fromNode(node: Node): StringResource {
            val name = node.attributes.getNamedItem("name").textContent
            val value = node.textContent
            return StringResource(name, value)
        }
    }
}
