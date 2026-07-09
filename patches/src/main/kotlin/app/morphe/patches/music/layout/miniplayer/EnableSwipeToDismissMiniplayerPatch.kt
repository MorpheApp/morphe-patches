/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_9_00_or_greater
import app.morphe.patches.music.misc.playservice.is_9_03_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/EnableSwipeToDismissMiniplayerPatch;"

// #1671: notify the crossfade manager when the miniplayer is swipe-dismissed, so it
// suppresses the phantom crossfade the dismiss's stopVideo(5) would otherwise trigger.
// CrossfadeManager lives in the same extension module (merged via sharedExtensionPatch),
// so this call is safe even when the "Track crossfade" bytecode hooks aren't applied —
// onQueueDismissed() simply early-returns when crossfade is inactive.
private const val CROSSFADE_MANAGER_CLASS = "Lapp/morphe/extension/music/patches/CrossfadeManager;"

@Suppress("unused")
val enableSwipeToDismissMiniplayerPatch = bytecodePatch(
    name = "Enable swipe to dismiss miniplayer",
    description = "Adds an option to enable dismissing the miniplayer by swiping down on it."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_enable_swipe_to_dismiss_miniplayer", summary = true)
        )

        // region Dismiss miniplayer by swiping down

        val interactionLoggingSwipeField = InteractionLoggingEnumFingerprint
            .instructionMatches[1]
            .instruction
            .getReference<FieldReference>()!!

        val widgetReferences = mutableListOf<Reference>()
        val widgetInstructionMatches = MusicActivityWidgetFingerprint.instructionMatches

        setOf(1, 2, 3, 5, 7, 8, 9).forEach { i ->
            widgetReferences.add(
                widgetInstructionMatches[i].getInstruction<ReferenceInstruction>().reference
            )
        }

        val musicActivityPeerClass = (widgetReferences[0] as FieldReference).definingClass

        watchWhileDismissedFingerprint(musicActivityPeerClass).let {
            val helperMethod = ImmutableMethod(
                it.classDef.type,
                "patch_swipeToDismissMiniplayer",
                listOf(
                    ImmutableMethodParameter(musicActivityPeerClass, null, null)
                ),
                "V",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(5),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, ${widgetReferences[0]}
                        invoke-interface { v0 }, ${widgetReferences[1]}
                        move-result-object v0
                        check-cast v0, ${widgetReferences[2]}
                        sget-object v1, $interactionLoggingSwipeField
                        new-instance v2, ${widgetReferences[3]}
                        const v3, 0x878b
                        invoke-static { v3 }, ${widgetReferences[4]}
                        move-result-object v3
                        invoke-direct { v2, v3 }, ${widgetReferences[5]}
                        const/4 v3, 0x0
                        invoke-interface { v0, v1, v2, v3 }, ${widgetReferences[6]}
                        return-void
                    """
                )
            }
            it.classDef.methods.add(helperMethod)

            it.method.apply {
                val insertIndex = it.instructionMatches.first().index
                val peerRegister = getInstruction<TwoRegisterInstruction>(insertIndex).registerB
                val freeRegister = findFreeRegister(insertIndex, peerRegister)

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        invoke-static { }, $EXTENSION_CLASS->enableSwipeToDismissMiniplayer()Z
                        move-result v$freeRegister
                        if-nez v$freeRegister, :dismiss
                        invoke-static { v$peerRegister }, $helperMethod
                        return-void
                        :dismiss
                        invoke-static { }, $CROSSFADE_MANAGER_CLASS->onQueueDismissed()V
                    """
                )
            }
        }

        // endregion

        // region Hide cold start miniplayer text (R.string.mini_player_default_text)

        (if (is_9_03_or_greater) {
            Fingerprint(
                accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                parameters = listOf("Ljava/lang/Object;"),
                returnType = "V",
                filters = listOf(
                    opcode(Opcode.IF_NE),
                    methodCall(
                        opcode = Opcode.INVOKE_VIRTUAL,
                        smali = MiniPlayerDefaultTextFingerprint.method.toString()
                    )
                )
            )
        } else {
            MiniPlayerDefaultTextLegacyFingerprint
        }).let {
            it.method.apply {
                val insertIndex = it.instructionMatches.first().index
                val insertRegister = getInstruction<TwoRegisterInstruction>(insertIndex).registerB

                addInstructions(
                    insertIndex,
                    """
                        invoke-static { v$insertRegister }, $EXTENSION_CLASS->enableSwipeToDismissMiniplayer(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$insertRegister
                    """
                )
            }
        }

        // endregion

        // region Hide warm start miniplayer text (R.string.mini_player_default_text)

        val (watchWhileLayoutClass, warmStartMiniplayerClass) =
            with(WatchWhileLayoutFingerprint) {
                // It might be the same method as [MppWatchWhileLayoutFingerprint], use clearMatch.
                clearMatch()

                Pair(
                    method.definingClass,
                    instructionMatches.last().instruction.getReference<TypeReference>()!!.type
                )
            }

        Fingerprint(
            definingClass = warmStartMiniplayerClass,
            parameters = listOf("Landroid/view/View;", "I"),
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IGET_OBJECT,
                    definingClass = watchWhileLayoutClass
                ),
                methodCall(
                    opcode = Opcode.INVOKE_VIRTUAL,
                    definingClass = "Lcom/google/android/material/bottomsheet/BottomSheetBehavior;",
                    parameters = listOf("Z"),
                    returnType = "V",
                    location = MatchAfterWithin(5)
                )
            )
        ).let {
            it.method.apply {
                val insertIndex = it.instructionMatches.first().index
                val jumpIndex = it.instructionMatches.last().index + 1
                val freeRegister = findFreeRegister(insertIndex)

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        invoke-static { }, $EXTENSION_CLASS->enableSwipeToDismissMiniplayer()Z
                        move-result v$freeRegister
                        if-nez v$freeRegister, :skip
                    """, ExternalLabel("skip", getInstruction(jumpIndex))
                )
            }
        }

        // endregion

        // region Disable player page motion event

        if (is_9_00_or_greater) {
            PlayerPageBehaviorFingerprint.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->enableSwipeToDismissMiniplayer()Z
                    move-result v0
                    if-eqz v0, :ignore
                    return-void
                    :ignore
                    nop
                """
            )
        }

        // endregion

    }
}