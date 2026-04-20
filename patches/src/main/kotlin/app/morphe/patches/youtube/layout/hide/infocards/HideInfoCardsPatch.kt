package app.morphe.patches.youtube.layout.hide.infocards

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findFreeRegister
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/HideInfoCardsPatch;"
private const val EXTENSION_FILTER = "Lapp/morphe/extension/youtube/patches/components/InfoCardsFilter;"

@Suppress("unused")
val hideInfoCardsPatch = bytecodePatch(
    name = "Hide info cards",
    description = "Adds an option to hide info cards that creators add in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        lithoFilterPatch,
        settingsPatch,
        resourceMappingPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_hide_info_cards"),
        )

        // Edit: This old non-litho code may be obsolete and no longer used by any supported versions.
        InfoCardsIncognitoFingerprint.method.apply {
            // TODO: Add Instruction Filter indexOfFirstInstructionOrThrow method
            val filter = methodCall(smali = "Landroid/view/View;->setVisibility(I)V")
            val invokeInstructionIndex = indexOfFirstInstructionOrThrow {
                filter.matches(this@apply, this)
            }
            val register = getInstruction<FiveRegisterInstruction>(invokeInstructionIndex).registerC

            addInstruction(
                invokeInstructionIndex,
                "invoke-static { v$register }, $EXTENSION_CLASS->" +
                        "hideInfoCardsIncognito(Landroid/view/View;)V",
            )
        }

        // Edit: This old non-litho code may be obsolete and no longer used by any supported versions.
        InfoCardsMethodCallFingerprint.let {
            val invokeInterfaceIndex = it.instructionMatches[2].index

            it.method.apply {
                val free = findFreeRegister(invokeInterfaceIndex)

                addInstructionsWithLabels(
                    invokeInterfaceIndex,
                    """
                        invoke-static {}, $EXTENSION_CLASS->hideInfoCardsMethodCall()Z
                        move-result v$free
                        if-nez v$free, :hide_info_cards
                    """,
                    ExternalLabel(
                        "hide_info_cards",
                        getInstruction(invokeInterfaceIndex + 1)
                    )
                )
            }
        }

        // Info cards can also appear as Litho components.
        addLithoFilter(EXTENSION_FILTER)
    }
}
