@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/ChangeMiniplayerColorPatch;"

@Suppress("unused")
val changeMiniplayerColorPatch = bytecodePatch(
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
            SwitchPreference("morphe_music_change_miniplayer_color", summary = true),
            SwitchPreference("morphe_music_change_navigation_bar_color", summary = true)
        )

        SwitchToggleColorFingerprint.let {
            val colorMathPlayerInvokeVirtualReference = it.instructionMatches.last()
                .getInstruction<ReferenceInstruction>().reference

            val colorMathPlayerIGetReference = it.instructionMatches[4]
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

                // Publish the resolved color so the navigation bar can reuse it.
                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        invoke-static {}, $EXTENSION_CLASS->changeMiniplayerColor()Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :off
                        invoke-virtual { p1 }, $colorMathPlayerInvokeVirtualReference
                        move-result-object v$freeRegister
                        check-cast v$freeRegister, ${colorMathPlayerIGetReference.definingClass}
                        iget v$freeRegister, v$freeRegister, $colorMathPlayerIGetReference
                        invoke-static { v$freeRegister }, $EXTENSION_CLASS->setLastMiniplayerColor(I)V
                        iput v$freeRegister, p0, $colorMathPlayerIPutReference
                        :off
                        nop
                    """
                )
            }
        }

        // Only the 'ytm_color_grey_12' branch (visible tab bar) is patched; the
        // hidden branch uses a different color. The view is published so the
        // extension can repaint without waiting for a relayout.
        NavigationBarTabLayoutFingerprint.let {
            it.method.apply {
                val setBackgroundColorIndex = it.instructionMatches.last().index
                val call = getInstruction<FiveRegisterInstruction>(setBackgroundColorIndex)
                val tabLayoutRegister = call.registerC
                val colorRegister = call.registerD

                addInstructions(
                    setBackgroundColorIndex,
                    """
                        invoke-static { v$tabLayoutRegister, v$colorRegister }, $EXTENSION_CLASS->registerNavigationBar(Landroid/view/View;I)V
                        invoke-static { v$colorRegister }, $EXTENSION_CLASS->overrideNavigationBarColor(I)I
                        move-result v$colorRegister
                    """
                )
            }
        }

        // Hook the watch-while dismiss callback to drop the cached tint.
        // Fingerprint factory is shared with the swipe-to-dismiss patch.
        val musicActivityPeerClass = (
            MusicActivityWidgetFingerprint.instructionMatches[1]
                .getInstruction<ReferenceInstruction>().reference as FieldReference
        ).definingClass

        watchWhileDismissedFingerprint(musicActivityPeerClass).method.addInstructions(
            0,
            "invoke-static { }, $EXTENSION_CLASS->onMiniplayerDismissed()V"
        )
    }
}
