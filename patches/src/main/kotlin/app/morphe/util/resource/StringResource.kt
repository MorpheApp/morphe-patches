package app.morphe.util.resource

import app.morphe.patches.all.misc.resources.StringResourceSanitizer.sanitizeAndroidResourceString
import org.w3c.dom.Document
import org.w3c.dom.Node

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
        fun fromNode(node: Node): StringResource {
            val name = node.attributes.getNamedItem("name").textContent
            val value = node.textContent
            return StringResource(name, value)
        }
    }
}
