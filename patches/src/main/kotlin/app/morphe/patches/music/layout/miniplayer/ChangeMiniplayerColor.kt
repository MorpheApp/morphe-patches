@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/music/patches/ChangeMiniplayerColorPatch;"

@Suppress("unused")
val changeMiniplayerColor = bytecodePatch(
    name = "Change miniplayer color",
    description = "Adds an option to change the miniplayer background color to match the fullscreen player."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        resourceMappingPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_change_miniplayer_color"),
        )

        SwitchToggleColorFingerprint.match(MiniPlayerConstructorFingerprint.classDef).let {
            val colorMathPlayerInvokeVirtualReference = it.instructionMatches.last()
                .getInstruction<ReferenceInstruction>().reference

            val colorMathPlayerIGetReference = it.instructionMatches[3]
                .getInstruction<ReferenceInstruction>().reference  as FieldReference

            val colorGreyIndex = MiniPlayerConstructorFingerprint.method.indexOfFirstInstructionReversedOrThrow {
                getReference<MethodReference>()?.name == "getColor"
            }
            val iPutIndex = MiniPlayerConstructorFingerprint.method.indexOfFirstInstructionOrThrow(
                colorGreyIndex, Opcode.IPUT
            )
            val colorMathPlayerIPutReference = MiniPlayerConstructorFingerprint.method
                .getInstruction<ReferenceInstruction>(iPutIndex).reference

            MiniPlayerConstructorFingerprint.classDef.methods.single { method ->
                method.accessFlags == AccessFlags.PUBLIC.value or AccessFlags.FINAL.value &&
                        method.returnType == "V" &&
                        method.parameters == it.originalMethod.parameters
            }.apply {
                val insertIndex = indexOfFirstInstructionReversedOrThrow(Opcode.INVOKE_DIRECT)
                val freeRegister = findFreeRegister(insertIndex)

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->changeMiniplayerColor()Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :off
                        invoke-virtual { p1 }, $colorMathPlayerInvokeVirtualReference
                        move-result-object v$freeRegister
                        check-cast v$freeRegister, ${colorMathPlayerIGetReference.definingClass}
                        iget v$freeRegister, v$freeRegister, $colorMathPlayerIGetReference
                        iput v$freeRegister, p0, $colorMathPlayerIPutReference
                        :off
                        nop
                    """
                )
            }
        }
    }
}
