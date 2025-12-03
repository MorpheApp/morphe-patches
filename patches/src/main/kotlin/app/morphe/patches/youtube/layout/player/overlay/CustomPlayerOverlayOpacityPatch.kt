package app.morphe.patches.youtube.layout.player.overlay

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/CustomPlayerOverlayOpacityPatch;"

@Suppress("unused")
val customPlayerOverlayOpacityPatch = bytecodePatch(
    name = "Custom player overlay opacity",
    description = "Adds an option to change the opacity of the video player background when player controls are visible.",
) {
    dependsOn(settingsPatch,
        resourceMappingPatch,
        addResourcesPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        addResources("youtube", "layout.player.overlay.customPlayerOverlayOpacityResourcePatch")

        PreferenceScreen.PLAYER.addPreferences(
            TextPreference("morphe_player_overlay_opacity", inputType = InputType.NUMBER),
        )

        createPlayerOverviewFingerprint.let {
            it.method.apply {
                val viewRegisterIndex = it.instructionMatches.last().index
                val viewRegister = getInstruction<OneRegisterInstruction>(viewRegisterIndex).registerA

                addInstruction(
                    viewRegisterIndex + 1,
                    "invoke-static { v$viewRegister }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->changeOpacity(Landroid/widget/ImageView;)V",
                )
            }
        }
    }
}
