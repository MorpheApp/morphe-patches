package app.morphe.patches.youtube.layout.hide.endscreencards

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_19_43_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal var layoutCircle = -1L
    private set
internal var layoutIcon = -1L
    private set
internal var layoutVideo = -1L
    private set

private val hideEndScreenCardsResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        resourceMappingPatch,
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_hide_endscreen_cards"),
        )

        fun idOf(name: String) = getResourceId(ResourceType.LAYOUT, "endscreen_element_layout_$name")

        layoutCircle = idOf("circle")
        layoutIcon = idOf("icon")
        layoutVideo = idOf("video")
    }
}

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HideEndScreenCardsPatch;"

@Suppress("unused")
val hideEndScreenCardsPatch = bytecodePatch(
    name = "Hide end screen cards",
    description = "Adds an option to hide suggested video cards at the end of videos.",
) {
    dependsOn(
        sharedExtensionPatch,
        hideEndScreenCardsResourcePatch,
        versionCheckPatch
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
        listOf(
            LayoutCircleFingerprint,
            LayoutIconFingerprint,
            LayoutVideoFingerprint,
        ).forEach { fingerprint ->
            fingerprint.method.apply {
                val insertIndex = fingerprint.instructionMatches.last().index + 1
                val viewRegister = getInstruction<OneRegisterInstruction>(insertIndex - 1).registerA

                addInstruction(
                    insertIndex,
                    "invoke-static { v$viewRegister }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->hideEndScreenCardView(Landroid/view/View;)V",
                )
            }
        }

        if (is_19_43_or_greater) {
            ShowEndscreenCardsFingerprint.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideEndScreenCards()Z
                    move-result v0
                    if-eqz v0, :show
                    return-void
                    :show
                    nop
                """
            )
        }
    }
}
