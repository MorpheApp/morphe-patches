package app.morphe.patches.youtube.interaction.doubletap

import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val doubleTapLengthPatch = patch(
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

        resourceXml("res/values/arrays.xml").use { arrays ->
            val values = arrays.resource("double_tap_length_values")
            values.removeChildren()

            val entries = arrays.resource("double_tap_length_entries")
            entries.removeChildren()

            additionalDoubleTapLengths.forEach { length ->
                // ARSCLib gets confused if the existing array string values are replaced
                // with literal numbers. Use untranslatable strings to avoid the issue.
                val item = entries.appendChild("item") { text = "@string/morphe_double_tap_length_${length}s" }
                values.appendCopy(item)
            }
        }
    }
}
