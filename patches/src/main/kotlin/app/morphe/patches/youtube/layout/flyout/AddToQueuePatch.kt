/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.flyout


import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.hide.general.ContextualMenuItemBuilderFingerprint
import app.morphe.patches.youtube.layout.hide.general.ContextualMenuItemBuilderOnClickFingerprint
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.cloneParameters
import app.morphe.util.findFreeRegister
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/AddToQueuePatch;"

private const val EXTENSION_UTILS_CLASS =
    "Lapp/morphe/extension/youtube/patches/utils/FlyoutUtils;"

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
                SwitchPreference("morphe_queue_add_flyout_menu", summary = true),
                SwitchPreference("morphe_ads_channel_whitelist_flyout_menu", summary = true),
                SwitchPreference("morphe_playback_speed_channel_whitelist_flyout_menu", summary = true)
            )
        )


        FeedFlyoutButtonsInitializerFingerprint.let { mainFingerprint ->
            val mainFingerprintMatches = mainFingerprint.instructionMatches
            val getCharSequenceReference = mainFingerprintMatches.first().getInstruction<ReferenceInstruction>().reference
            val enumMethodRegister = mainFingerprintMatches[1].getInstruction<OneRegisterInstruction>().registerA
            val charCheckIndex = mainFingerprintMatches[4].index
            val enumIntField = mainFingerprintMatches[6].getInstruction<ReferenceInstruction>().reference
            val enumMethodCall = mainFingerprintMatches[7].getInstruction<ReferenceInstruction>().reference
            val runnableIndex = mainFingerprintMatches.last().index
            val charCheckRegister = mainFingerprintMatches.last().getInstruction<OneRegisterInstruction>().registerA

            mainFingerprint.method.apply {
                val runnableRegister = getInstruction<TwoRegisterInstruction>(runnableIndex).registerA
                addInstructions(
                    runnableIndex,
                    """
                        invoke-static { v$runnableRegister }, $EXTENSION_CLASS->replaceButtonRunnable(Ljava/lang/Runnable;)Ljava/lang/Runnable;
                        move-result-object v$runnableRegister
                    """
                )

                val freeRegister = findFreeRegister(charCheckIndex, charCheckRegister, enumMethodRegister)
                addInstructions(
                    charCheckIndex,
                    """
                        iget v$freeRegister, v$enumMethodRegister, $enumIntField
                        invoke-static { v$freeRegister }, $enumMethodCall
                        move-result-object v$freeRegister
                        invoke-static { v$freeRegister, v$charCheckRegister }, $EXTENSION_UTILS_CLASS->setCurrentButtonInfo(Ljava/lang/Enum;Ljava/lang/Object;)V
                    """
                )
            }

            ContextualMenuItemBuilderFingerprint.let {
                it.method.cloneParameters().apply {
                    val targetInstructionIndex = it.instructionMatches[3].index + numberOfParameterRegisters
                    val targetInstructionRegister = it.instructionMatches[3]
                        .getInstruction<FiveRegisterInstruction>().registerC
                    val secondButtonInfoParameterRegister = it.instructionMatches[2]
                        .getInstruction<FiveRegisterInstruction>().registerC

                    addInstructions(
                        targetInstructionIndex,
                            """
                            invoke-static { v$targetInstructionRegister }, $getCharSequenceReference
                            move-result-object p0
                            iget p0, p0, $enumIntField
                            invoke-static { p0 }, $enumMethodCall
                            move-result-object p0
                            invoke-static { p0, v$secondButtonInfoParameterRegister }, $EXTENSION_UTILS_CLASS->setCurrentButtonInfo(Ljava/lang/Enum;Ljava/lang/Object;)V
                        """
                    )
                }
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

    }
}
