/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.remember.shufflestate

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_8_03_or_greater
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
import app.morphe.util.toPublicAccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
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
            fingerprint.method.apply {
                val startIndex = fingerprint.instructionMatches.first().index

                val indexAndRegister = if (is_8_03_or_greater) {
                    val index = indexOfFirstInstructionReversedOrThrow(startIndex,
                        methodCall(opcode = Opcode.INVOKE_VIRTUAL, returnType = enumClass)
                    )
                    val register = getInstruction<OneRegisterInstruction>(index + 1).registerA
                    Pair(index + 2, register)
                } else {
                    val index = indexOfFirstInstructionReversedOrThrow(startIndex,
                        methodCall(opcode = Opcode.INVOKE_DIRECT, returnType = "Ljava/lang/String;")
                    )
                    val register = getInstruction<FiveRegisterInstruction>(index).registerD
                    Pair(index, register)
                }

                val enumIndex = indexAndRegister.first
                val enumRegister = indexAndRegister.second

                addInstruction(
                    enumIndex,
                    "invoke-static { v$enumRegister }, $EXTENSION_CLASS->saveShuffleState(Ljava/lang/Enum;)V"
                )

                val shuffleClassIndex = indexOfFirstInstructionReversedOrThrow(
                    enumIndex, Opcode.CHECK_CAST
                )
                val shuffleClass = getInstruction(shuffleClassIndex).getReference<TypeReference>()!!.type
                val shuffleMutableClass = this@execute.mutableClassDefBy(shuffleClass)

                shuffleMutableClass.methods.filter { it.name == "<init>" }.forEach { constructor ->
                    val superInitIndex = constructor.indexOfFirstInstructionOrThrow(
                        methodCall(
                            opcode = Opcode.INVOKE_DIRECT,
                            name = "<init>"
                        )
                    )
                    constructor.addInstructions(
                        superInitIndex + 1,
                        """
                            sput-object p0, $EXTENSION_CLASS->shuffleClass:$shuffleClass
                        """
                    )
                }

                this@execute.addStaticFieldToExtension(
                    className = EXTENSION_CLASS,
                    methodName = "shuffleTracks",
                    fieldName = "shuffleClass",
                    objectClass = shuffleClass,
                    smaliInstructions = """
                        if-eqz v0, :ignore
                        
                        sget-object v1, $enumClass->b:$enumClass
                        invoke-virtual { v0, v1 }, $shuffleClass->shuffleTracks($enumClass)V
                        
                        :ignore
                        return-void
                    """
                )

                val ordinalFilter = methodCall(opcode = Opcode.INVOKE_VIRTUAL, definingClass = enumClass, name = "ordinal")
                val postFilter = methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "post")

                val shuffleMethod = shuffleMutableClass.methods.firstOrNull { method ->
                    method.returnType == "V" &&
                            method.indexOfFirstInstruction(ordinalFilter) >= 0 &&
                            method.indexOfFirstInstruction(postFilter) >= 0
                } ?: throw PatchException("Internal shuffle method not found in $shuffleClass")

                val clonedMethod = shuffleMethod.cloneMutable(
                    accessFlags = shuffleMethod.accessFlags.toPublicAccessFlags(),
                    name = "shuffleTracks",
                    additionalRegisters = if (is_8_03_or_greater) 1 else 0,
                    parameters = listOf(
                        ImmutableMethodParameter(enumClass, emptySet(), "enumClass")
                    )
                )

                if (is_8_03_or_greater) {
                    val ordinalIndex = clonedMethod.indexOfFirstInstruction(ordinalFilter)
                    val register = clonedMethod.getInstruction<FiveRegisterInstruction>(ordinalIndex).registerC

                    clonedMethod.addInstruction(
                        ordinalIndex,
                        "move-object/from16 v$register, p1"
                    )
                }

                shuffleMutableClass.methods.add(clonedMethod)
            }
        }

        MusicPlaybackControlsFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->overrideShuffleButton(Z)Z
                move-result p1
            """
        )

        musicVideoIdHook("$EXTENSION_CLASS->applySavedShuffleState(Ljava/lang/String;)V")
    }
}
