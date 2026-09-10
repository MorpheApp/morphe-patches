/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.remember.shufflestate

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addStaticFieldToExtension
import app.morphe.util.cloneMutable
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.numberOfParameterRegistersLogical
import app.morphe.util.toPublicAccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/RememberShuffleStatePatch;"

@Suppress("unused")
val rememberShuffleStatePatch = bytecodePatch(
    name = "Remember shuffle state",
    description = "Adds an option to remember the shuffle state when playing a new track or playlist."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        musicVideoInformationPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_remember_shuffle_state")
        )

        val enumClass = ShuffleEnumFingerprint.method.definingClass

        ShuffleOnClickFingerprint.let { fingerprint ->
            val onClickMethod = fingerprint.method
            val shuffleButtonUiClass = onClickMethod.definingClass

            val startIndex = fingerprint.instructionMatches.first().index

            val enumMethodIndex = onClickMethod.indexOfFirstInstructionReversedOrThrow(
                startIndex,
                methodCall(opcode = Opcode.INVOKE_VIRTUAL, returnType = enumClass)
            )

            val enumRegister = onClickMethod.getInstruction<OneRegisterInstruction>(enumMethodIndex + 1).registerA
            val enumIndex = enumMethodIndex + 2

            val enumMethodRef = onClickMethod.getInstruction(enumMethodIndex).getReference<MethodReference>()!!
            val getStateMethodName = enumMethodRef.name
            val shuffleClass = enumMethodRef.definingClass

            onClickMethod.addInstruction(
                enumIndex,
                "invoke-static { v$enumRegister }, $EXTENSION_CLASS->saveShuffleState(Ljava/lang/Enum;)V"
            )

            val invokeInterfaceInst = onClickMethod.getInstruction<ReferenceInstruction>(
                onClickMethod.indexOfFirstInstructionOrThrow(
                    Opcode.INVOKE_INTERFACE
                )
            )

            val providerMethodRef = invokeInterfaceInst.reference as MethodReference
            val providerClass = providerMethodRef.definingClass
            val providerMethodName = providerMethodRef.name

            val shuffleButtonUiMutableClass = mutableClassDefBy(shuffleButtonUiClass)
            val shuffleButtonUiInit = shuffleButtonUiMutableClass.methods.first { it.name == "<init>" }

            val returnIndex = shuffleButtonUiInit.indexOfFirstInstruction(Opcode.RETURN_VOID)
            shuffleButtonUiInit.addInstructions(
                returnIndex,
                """
                    invoke-interface {p2}, $providerClass->$providerMethodName()Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, $shuffleClass
                    sput-object v0, $EXTENSION_CLASS->shuffleClass:$shuffleClass
                """
            )

            addStaticFieldToExtension(
                className = EXTENSION_CLASS,
                methodName = "shuffleTracks",
                fieldName = "shuffleClass",
                objectClass = shuffleClass,
                smaliInstructions = """
                    if-eqz v0, :ignore
                    
                    invoke-virtual { v0 }, $shuffleClass->$getStateMethodName()$enumClass
                    move-result-object v1
                    
                    invoke-virtual { v1 }, Ljava/lang/Enum;->ordinal()I
                    move-result v1
                    
                    add-int/lit8 v1, v1, -0x1
                    if-eqz v1, :ignore
                    
                    sget-object v1, $enumClass->b:$enumClass
                    invoke-virtual { v0, v1 }, $shuffleClass->shuffleTracks($enumClass)V
                    
                    :ignore
                    return-void
                """
            )

            val shuffleMethodFingerprint = Fingerprint(
                definingClass = shuffleClass,
                returnType = "V",
                filters = listOf(
                    methodCall(
                        opcode = Opcode.INVOKE_VIRTUAL,
                        definingClass = enumClass,
                        name = "ordinal"
                    ),
                    methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "post")
                )
            )

            val clonedMethod = shuffleMethodFingerprint.method.let {
                it.cloneMutable(
                    accessFlags = it.accessFlags.toPublicAccessFlags(),
                    name = "shuffleTracks",
                    additionalRegisters = 1,
                    parameters = listOf(
                        ImmutableMethodParameter(enumClass, emptySet(), "enumClass")
                    )
                )
            }

            val ordinalIndex =  shuffleMethodFingerprint.instructionMatches.first().index +
                    clonedMethod.numberOfParameterRegistersLogical // Cloning adjusts match indexes.
            val ordinalRegister = clonedMethod.getInstruction<FiveRegisterInstruction>(ordinalIndex).registerC

            clonedMethod.addInstruction(
                ordinalIndex,
                "move-object/from16 v$ordinalRegister, p1"
            )

            mutableClassDefBy(shuffleClass).methods.add(clonedMethod)
        }

        musicVideoIdHook("$EXTENSION_CLASS->applySavedShuffleState(Ljava/lang/String;)V")
    }
}
