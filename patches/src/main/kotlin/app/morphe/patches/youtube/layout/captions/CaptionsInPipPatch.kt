/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3907
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_29_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_02_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.PlayerTypeEnumFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionsInPipPatch;"

internal val captionsInPipPatch = bytecodePatch(
    description = "Adds an option to show captions in picture-in-picture mode.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
    )

    execute {
        settingsMenuCaptionGroup.add(
            SwitchPreference("morphe_captions_in_pip")
        )

        val overlayNameMethodName = CaptionsOverlayNameFingerprint.originalMethod.name

        // 20.29+ gives overlays a player state that includes whether picture-in-picture is active.
        // Report it as inactive to the captions overlay.
        if (is_20_29_or_greater) {
            val stateType = PlayerOverlayStateToStringFingerprint.originalClassDef.type
            val fieldNames = PlayerOverlayStateToStringFingerprint.instructionMatches.first()
                .instruction.getReference<StringReference>()!!.string.split(';')
            // Argument position of isPipActive when creating the player state, after the state itself.
            val pipArgument = fieldNames.indexOf("isPipActive") + 1
            if (pipArgument == 0) throw PatchException("Unexpected player state fields: $fieldNames")

            getPlayerOverlayVisibilityFingerprint(stateType).matchAll().forEach {
                it.method.apply {
                    val stateIndex = it.instructionMatches.first().index
                    val pipRegister = getInstruction(stateIndex).registersUsed[pipArgument]
                    val overlayCall = getInstruction<FiveRegisterInstruction>(stateIndex + 1)
                    val overlayType = overlayCall.getReference<MethodReference>()!!.definingClass

                    // Placed on the branch target where isPipActive is set, so it runs for both values.
                    // Reuses the isPipActive register because some of these methods have no free registers.
                    addInstructionsAtControlFlowLabel(
                        stateIndex,
                        """
                            if-eqz v$pipRegister, :not_pip
                            invoke-interface { v${overlayCall.registerC} }, $overlayType->$overlayNameMethodName()Ljava/lang/String;
                            move-result-object v$pipRegister
                            invoke-static/range { v$pipRegister .. v$pipRegister }, $EXTENSION_CLASS->isPipActive(Ljava/lang/String;)Z
                            move-result v$pipRegister
                            :not_pip
                            nop
                        """
                    )
                }
            }
        }

        // Before 21.05, overlays are also given the player type, which can be picture-in-picture.
        // Show the captions overlay anyway.
        if (!is_21_05_or_greater) {
            getPlayerTypeOverlayVisibilityFingerprint(PlayerTypeEnumFingerprint.originalClassDef.type)
                .matchAll()
                .forEach {
                    it.method.apply {
                        val callIndex = it.instructionMatches.first().index
                        val overlayCall = getInstruction<FiveRegisterInstruction>(callIndex)
                        val overlayType = overlayCall.getReference<MethodReference>()!!.definingClass
                        val resultRegister = getInstruction<OneRegisterInstruction>(callIndex + 1).registerA
                        val showRegister = findFreeRegister(callIndex, overlayCall.registerC, overlayCall.registerD)

                        addInstruction(callIndex + 2, "or-int/2addr v$resultRegister, v$showRegister")
                        addInstructionsAtControlFlowLabel(
                            callIndex,
                            """
                                invoke-interface { v${overlayCall.registerC} }, $overlayType->$overlayNameMethodName()Ljava/lang/String;
                                move-result-object v$showRegister
                                invoke-static { v$showRegister, v${overlayCall.registerD} }, $EXTENSION_CLASS->showInPip(Ljava/lang/String;Ljava/lang/Enum;)Z
                                move-result v$showRegister
                            """
                        )
                    }
                }
        }

        // The current caption track can be set again when entering or leaving picture-in-picture,
        // which clears the caption on screen until the next one. Only set the track if it changed.
        val trackType = (
            if (is_21_02_or_greater) CaptionTrackIsDisableOptionFingerprint
            else CaptionTrackIsDisableOptionLegacyFingerprint
        ).originalClassDef.type

        getCaptionTrackEventFingerprint(trackType).match().let {
            val presenterType = it.classDef.type
            val setTrackIndex = it.instructionMatches.last().index
            val setTrackCall = it.method.getInstruction<FiveRegisterInstruction>(setTrackIndex)
            val currentTrackField = it.classDef.fields.single { field -> field.type == trackType }.name
            val setTrackIfChangedMethod = "patch_setCaptionTrackIfChanged"

            it.classDef.addHelperMethod(
                setTrackIfChangedMethod,
                "V",
                listOf(trackType),
                smali = """
                    iget-object v0, p0, $presenterType->$currentTrackField:$trackType
                    invoke-static { p1, v0 }, $EXTENSION_CLASS->isCurrentTrack(Ljava/lang/Object;Ljava/lang/Object;)Z
                    move-result v0
                    if-nez v0, :current
                    invoke-virtual { p0, p1 }, ${setTrackCall.getReference<MethodReference>()}
                    :current
                    return-void
                """
            )

            it.method.replaceInstruction(
                setTrackIndex,
                "invoke-virtual { v${setTrackCall.registerC}, v${setTrackCall.registerD} }, " +
                        "$presenterType->$setTrackIfChangedMethod($trackType)V"
            )
        }
    }
}
