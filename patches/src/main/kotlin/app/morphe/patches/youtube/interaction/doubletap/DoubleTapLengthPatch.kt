package app.morphe.patches.youtube.interaction.doubletap

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.removeFromParent
import org.w3c.dom.Element

@Suppress("unused")
val doubleTapLengthPatch = resourcePatch(
    name = "Double tap to seek",
    description = "Adds additional double-tap to seek values to the YouTube settings menu."
) {
    dependsOn(
        sharedExtensionPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val additionalDoubleTapLengths = arrayOf(
            3,
            5,
            10,
            15,
            20,
            30,
            60,
            120,
            180,
            240,
        )

        document("res/values/arrays.xml").use { document ->
            fun Element.removeAllChildren() {
                // noinspection CheckResult
                val children = childNodes // Calling childNodes creates a new list.
                for (i in children.length - 1 downTo 0) {
                    children.item(i).removeFromParent()
                }
            }

            val childNodes = document.childNodes
            val values = childNodes.findElementByAttributeValueOrThrow(
                attributeName = "name",
                value = "double_tap_length_values"
            )
            values.removeAllChildren()

            val entries = childNodes.findElementByAttributeValueOrThrow(
                attributeName = "name",
                value = "double_tap_length_entries"
            )
            entries.removeAllChildren()

            additionalDoubleTapLengths.forEach { length ->
                val item = document.createElement("item")
                // ARSCLib gets confused if the existing array string values are replaced
                // with literal numbers. Use untranslatable strings to avoid the issue.
                item.textContent = "@string/morphe_double_tap_length_${length}s"
                entries.appendChild(item)
                values.appendChild(item.cloneNode(true))
            }
        }
    }
}
