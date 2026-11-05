package app.morphe.patches.util.resource

import app.morphe.patcher.xml.XmlElement

/**
 * Base resource class for all resources.
 *
 * @param name The name of the resource.
 * @param tag The tag of the resource.
 */
@Suppress("MemberVisibilityCanBePrivate")
abstract class BaseResource(
    val name: String,
    val tag: String
) {
    /**
     * Serialize resource element to XML.
     * Overriding methods should invoke super and operate on its return value.
     * @param parent The element to add the resource to, the root of a values file.
     * @param resourceCallback Called when a resource has been processed.
     */
    open fun serialize(parent: XmlElement, resourceCallback: (BaseResource) -> Unit = { }): XmlElement =
        parent.appendChild(tag) {
            setAttribute("name", name)
        }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + tag.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BaseResource

        return name == other.name
    }
}
