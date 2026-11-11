/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.flyout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.EXTENSION_CLASS as NATIVE_QUEUE_EXTENSION_CLASS
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.nativePlaylistQueuePatch
import app.morphe.patches.youtube.layout.hide.general.ContextualMenuItemBuilderOnClickFingerprint
import app.morphe.patches.youtube.layout.playlistautoplay.navigationIntentHook
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.loadvideo.loadVideoHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE
import app.morphe.patches.youtube.video.information.playerStatusOverrideHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/AddToQueuePatch;"

private const val EXTENSION_LOCAL_QUEUE_CLASS =
    "Lapp/morphe/extension/youtube/patches/LocalQueuePatch;"

@Suppress("unused")
val addToQueuePatch = patch(
    name = "Add to queue",
    description = "Adds a video queue to the feed flyout menu, using either the Morphe queue " +
            "or a native queue that uses YouTube's playlist interface."
) {
    dependsOn(
        flyoutPatch,
        settingsPatch,
        sharedExtensionPatch,
        elementProtoParserHookPatch,
        authHookPatch,
        loadVideoHookPatch,
        videoInformationPatch,
        nativePlaylistQueuePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            noTitleUnsortedPreferenceCategory(
                // The native queue requires 21.16+.
                if (is_21_15_or_greater) {
                    ListPreference("morphe_queue_type")
                } else {
                    ListPreference(
                        key = "morphe_queue_type",
                        entriesKey = "morphe_queue_type_legacy_entries",
                        entryValuesKey = "morphe_queue_type_legacy_entry_values"
                    )
                },
                SwitchPreference("morphe_native_queue_fetch_on_scroll", summary = true)
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
                val replacement = if (is_21_15_or_greater) {
                    // The original item register may now hold its Runnable. Reload
                    // the same source from the factory parameter instead of guessing its lifetime.
                    check(AccessFlags.STATIC.isSet(accessFlags)) { "Queue item factory must be static" }
                    val itemType = (getCharSequenceReference as MethodReference).parameterTypes.single().toString()
                    val ownerType = parameterTypes.single().toString()
                    val itemField = classDefBy(ownerType).fields.single { it.type == itemType }
                    val itemRegister = findFreeRegister(runnableIndex, runnableRegister)
                    check(itemRegister <= 15) { "Queue item reload requires a low scratch register" }
                    """
                        move-object/from16 v$itemRegister, p0
                        iget-object v$itemRegister, v$itemRegister, $itemField
                        invoke-static {v$runnableRegister, v$itemRegister}, $EXTENSION_CLASS->replaceButtonRunnable(Ljava/lang/Runnable;Ljava/lang/Object;)Ljava/lang/Runnable;
                        move-result-object v$runnableRegister
                    """
                } else {
                    """
                        invoke-static {v$runnableRegister}, $EXTENSION_CLASS->replaceButtonRunnable(Ljava/lang/Runnable;)Ljava/lang/Runnable;
                        move-result-object v$runnableRegister
                    """
                }
                addInstructions(runnableIndex, replacement)
            }

            fun getReplaceOnItemClickPatch(
                targetInstructionRegister: String,
                freeRegister: String,
                method: String = "replaceOnItemClick",
                continuation: String = "block_item_click"
            ): String = """
                invoke-static { $targetInstructionRegister }, $EXTENSION_CLASS->$method(Ljava/lang/Object;)Z
                move-result $freeRegister
                if-eqz $freeRegister, :$continuation
                return-void
                :$continuation
                nop
            """

            ContextualMenuItemBuilderOnClickFingerprint.let {
                val enumMethodParameterClassReference = it.instructionMatches.first()
                    .getInstruction<ReferenceInstruction>().reference
                val enumMethodParameterClassName = it.instructionMatches[1]
                    .getInstruction<ReferenceInstruction>().reference

                // The class also handles other kinds of menu items, with another type of data.
                it.method.addInstructionsWithLabels(
                    0,
                    """
                        iget-object v0, p0, $enumMethodParameterClassReference
                        instance-of v1, v0, $enumMethodParameterClassName
                        if-eqz v1, :block_item_click
                        check-cast v0, $enumMethodParameterClassName
                    """ + (if (is_21_15_or_greater) {
                        // Preserve the checked item for the native icon conversion below.
                        getReplaceOnItemClickPatch("v0", "v1", "replacePlaylistItemClick", "native_playlist_item")
                    } else "") + """
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

        navigationIntentHook(EXTENSION_LOCAL_QUEUE_CLASS, "shouldCancelNavigation", includePlayerButtons = true)

        playerStatusOverrideHook(EXTENSION_LOCAL_QUEUE_CLASS, "shouldCancelEndOfVideo")

        // The sheet must be gone before the player enters Picture-in-Picture, or the PiP window is zoomed.
        Fingerprint(
            definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
            name = "onUserLeaveHint",
            returnType = "V",
            parameters = listOf()
        ).method.apply {
            addInstruction(0, "invoke-static { }, $EXTENSION_LOCAL_QUEUE_CLASS->onUserLeaveHint()V")
            if (is_21_15_or_greater) {
                // The native queue cancels its pending playlist page loads when leaving the app,
                // unless a Next/Previous is waiting on them during background playback.
                addInstruction(0, "invoke-static { }, $NATIVE_QUEUE_EXTENSION_CLASS->onUserLeaveHint()V")
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
