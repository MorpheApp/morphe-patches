/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.StartVideoInformerFingerprint
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PreferredCaptionLanguagePatch;"

internal val preferredCaptionLanguagePatch = bytecodePatch(
    description = "Adds an option to automatically select captions in your preferred language (provider subtitles first, then auto-translated).",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch
    )

    execute {
        settingsMenuCaptionGroup.add(
            ListPreference("morphe_preferred_caption_language")
        )

        val defaultMethod = DefaultCaptionTrackFingerprint.method
        val subtitleManagerClass = DefaultCaptionTrackFingerprint.classDef

        val callingMethods = subtitleManagerClass.methods.filter { method ->
            method.implementation?.instructions?.any { insn ->
                insn.opcode == Opcode.INVOKE_VIRTUAL &&
                insn.getReference<MethodReference>()?.let { ref ->
                    ref.name == defaultMethod.name &&
                    ref.definingClass == subtitleManagerClass.type &&
                    ref.returnType == defaultMethod.returnType &&
                    ref.parameterTypes == defaultMethod.parameterTypes
                } == true
            } == true
        }
        if (callingMethods.size != 1) {
            throw PatchException("Expected exactly 1 method calling ${defaultMethod.name} in ${subtitleManagerClass.type}, found ${callingMethods.size}")
        }
        val targetMethod = callingMethods.first()
        val callCount = targetMethod.implementation?.instructions?.count { insn ->
            insn.opcode == Opcode.INVOKE_VIRTUAL &&
            insn.getReference<MethodReference>()?.let { ref ->
                ref.name == defaultMethod.name &&
                ref.definingClass == subtitleManagerClass.type &&
                ref.returnType == defaultMethod.returnType &&
                ref.parameterTypes == defaultMethod.parameterTypes
            } == true
        } ?: 0
        if (callCount != 1) {
            throw PatchException("Expected exactly 1 call to ${defaultMethod.name} in ${targetMethod.name}, found $callCount")
        }
        val invokeIndex = targetMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_VIRTUAL &&
            getReference<MethodReference>()?.let { ref ->
                ref.name == defaultMethod.name &&
                ref.definingClass == subtitleManagerClass.type &&
                ref.returnType == defaultMethod.returnType &&
                ref.parameterTypes == defaultMethod.parameterTypes
            } == true
        }
        val invokeInsn = targetMethod.getInstruction<FiveRegisterInstruction>(invokeIndex)
        val moveResultIndex = invokeIndex + 1
        val moveResultInsn = targetMethod.getInstruction<OneRegisterInstruction>(moveResultIndex)
        if (moveResultInsn.opcode != Opcode.MOVE_RESULT_OBJECT) {
            throw PatchException("Expected MOVE_RESULT_OBJECT after ${defaultMethod.name} at index $invokeIndex, found ${moveResultInsn.opcode}")
        }
        val receiverRegister = invokeInsn.registerC
        val resultRegister = moveResultInsn.registerA
        if (receiverRegister > 15 || resultRegister > 15) {
            throw PatchException("Register out of range for invoke-static: receiver=$receiverRegister, result=$resultRegister")
        }
        val trackType = defaultMethod.returnType
        targetMethod.addInstructions(
            moveResultIndex + 1,
            """
                invoke-static { v$receiverRegister, v$resultRegister }, $EXTENSION_CLASS->getPreferredCaptionTrack(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$resultRegister
                check-cast v$resultRegister, $trackType
            """
        )

        SetSubtitleTrackFingerprint.method.apply {
            val trackType = parameterTypes[0]
            addInstructions(
                0,
                """
                    invoke-static { p0, p1, p2 }, $EXTENSION_CLASS->onSetSubtitleTrack(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p1
                    check-cast p1, $trackType
                """
            )
        }

        onCreateHook(EXTENSION_CLASS, "newVideoStarted")

        StartVideoInformerFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS->videoInformationLoaded()V"
        )
    }
}
