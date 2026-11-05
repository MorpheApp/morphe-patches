package app.morphe.patches.util.resource

import app.morphe.patcher.xml.XmlElement

/**
 *  An array resource.
 *
 *  @param name The name of the array resource.
 *  @param items The items of the array resource.
 */
@Suppress("MemberVisibilityCanBePrivate")
class ArrayResource(
    name: String,
    val items: List<String>,
) : BaseResource(name, "string-array") {
    override fun serialize(parent: XmlElement, resourceCallback: (BaseResource) -> Unit) =
        super.serialize(parent, resourceCallback).apply {
            items.forEach { item ->
                appendChild("item") { text = item }
            }
        }

    companion object {
        @Suppress("unused")
        fun fromElement(element: XmlElement) =
            ArrayResource(element.attribute("name")!!, element.children.map { it.text })
    }
}
