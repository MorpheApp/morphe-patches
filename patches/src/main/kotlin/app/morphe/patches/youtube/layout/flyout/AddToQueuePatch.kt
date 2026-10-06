/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.flyout

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.hide.general.ContextualMenuItemBuilderOnClickFingerprint
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/AddToQueuePatch;"

@Suppress("unused")
val addToQueuePatch = bytecodePatch(
    name = "Add to queue",
    description = "Overrides the feed flyout 'Play next in queue' with the Morphe video queue."
) {
    dependsOn(
        flyoutPatch,
        settingsPatch,
        sharedExtensionPatch,
        elementProtoParserHookPatch,
        authHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_queue_override_flyout_menu", summary = true),
                SwitchPreference("morphe_queue_add_flyout_menu", summary = true)
            )
        )

        // Flyout patch adds instructions to this method, so the prior match indexes are stale.
        FeedFlyoutButtonsInitializerFingerprint.clearMatch()
        FeedFlyoutButtonsInitializerFingerprint.let { mainFingerprint ->
            val mainFingerprintMatches = mainFingerprint.instructionMatches
            val getCharSequenceReference = mainFingerprintMatches.first().getInstruction<ReferenceInstruction>().reference
            val enumIntField = mainFingerprintMatches[6].getInstruction<ReferenceInstruction>().reference
            val enumMethodCall = mainFingerprintMatches[7].getInstruction<ReferenceInstruction>().reference
            val runnableIndex = mainFingerprintMatches.last().index

            mainFingerprint.method.apply {
                val runnableRegister = getInstruction<TwoRegisterInstruction>(runnableIndex).registerA
                addInstructions(
                    runnableIndex,
                    """
                        invoke-static { v$runnableRegister }, $EXTENSION_CLASS->replaceButtonRunnable(Ljava/lang/Runnable;)Ljava/lang/Runnable;
                        move-result-object v$runnableRegister
                    """
                )
            }

            fun getReplaceOnItemClickPatch(
                targetInstructionRegister: String,
                freeRegister: String
            ): String = """
                invoke-static { $targetInstructionRegister }, $EXTENSION_CLASS->replaceOnItemClick(Ljava/lang/Object;)Z
                move-result $freeRegister
                if-eqz $freeRegister, :block_item_click
                return-void
                :block_item_click
                nop
            """

            ContextualMenuItemBuilderOnClickFingerprint.let {
                val enumMethodParameterClassReference = it.instructionMatches.first()
                    .getInstruction<ReferenceInstruction>().reference
                val enumMethodParameterClassName = it.instructionMatches[1]
                    .getInstruction<ReferenceInstruction>().reference

                it.method.addInstructions(
                    0,
                    """
                        iget-object v0, p0, $enumMethodParameterClassReference
                        check-cast v0, $enumMethodParameterClassName
                        invoke-static { v0 }, $getCharSequenceReference
                        move-result-object v0
                        iget v0, v0, $enumIntField
                        invoke-static { v0 }, $enumMethodCall
                        move-result-object v0
                        invoke-virtual {v0}, Ljava/lang/Enum;->name()Ljava/lang/String;
                        move-result-object v0
                    """ + getReplaceOnItemClickPatch("v0", "v0")
                )
            }

            if (!is_21_05_or_greater) {
                FeedFlyoutButtonsInitializerOnItemClickFingerprint.method.addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p3 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                        move-result-object p2
                    """ + getReplaceOnItemClickPatch("p2", "p2")
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
