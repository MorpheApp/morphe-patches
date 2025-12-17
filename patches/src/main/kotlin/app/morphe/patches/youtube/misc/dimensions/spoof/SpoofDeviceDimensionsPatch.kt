package app.morphe.patches.youtube.misc.dimensions.spoof

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/spoof/SpoofDeviceDimensionsPatch;"

val spoofDeviceDimensionsPatch = bytecodePatch(
    name = "Spoof device dimensions",
    description = "Adds an option to spoof the device dimensions which can unlock higher video qualities.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_spoof_device_dimensions"),
        )

        DeviceDimensionsModelToStringFingerprint
            .classDef.methods.first { method -> method.name == "<init>" }
            // Override the parameters containing the dimensions.
            .addInstructions(
                1, // Add after super call.
                arrayOf(
                    1 to "MinHeightOrWidth", // p1 = min height
                    2 to "MaxHeightOrWidth", // p2 = max height
                    3 to "MinHeightOrWidth", // p3 = min width
                    4 to "MaxHeightOrWidth", // p4 = max width
                ).map { (parameter, method) ->
                    """
                        invoke-static { p$parameter }, $EXTENSION_CLASS_DESCRIPTOR->get$method(I)I
                        move-result p$parameter
                    """
                }.joinToString("\n") { it },
            )
    }
}
