/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
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
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/EnableSwipeToDismissMiniplayerPatch;"

@Suppress("unused")
val enableSwipeToDismissMiniplayerPatch = bytecodePatch(
    name = "Enable swipe to dismiss miniplayer",
    description = "Adds an option to enable dismissing the miniplayer by swiping down on it."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_enable_swipe_to_dismiss_miniplayer", summary = true)
        )

        val swipeToDismissSGetObjectReference = InteractionLoggingEnumFingerprint.method.let { m ->
            val stringIndex = m.indexOfFirstInstructionOrThrow { getReference<StringReference>()?.string == "INTERACTION_LOGGING_GESTURE_TYPE_SWIPE" }
            val sPutObjectIndex = m.indexOfFirstInstructionOrThrow(stringIndex, Opcode.SPUT_OBJECT)
            m.getInstruction<ReferenceInstruction>(sPutObjectIndex).reference
        }

        val musicActivityWidgetMethod = MusicActivityWidgetFingerprint.method
        val swipeToDismissWidgetIndex = musicActivityWidgetMethod.indexOfFirstLiteralInstructionOrThrow(79500L)

        fun getSwipeToDismissReference(targetOpcode: Opcode, reversed: Boolean): Reference {
            val targetIndex = if (reversed)
                musicActivityWidgetMethod.indexOfFirstInstructionReversedOrThrow(swipeToDismissWidgetIndex) {
                    opcode == targetOpcode
                }
            else
                musicActivityWidgetMethod.indexOfFirstInstructionOrThrow(swipeToDismissWidgetIndex, targetOpcode)

            return musicActivityWidgetMethod.getInstruction<ReferenceInstruction>(targetIndex).reference
        }

        val swipeToDismissIGetObjectReference = getSwipeToDismissReference(Opcode.IGET_OBJECT, true)
        val swipeToDismissInvokeInterfacePrimaryReference = getSwipeToDismissReference(Opcode.INVOKE_INTERFACE, true)
        val swipeToDismissCheckCastReference = getSwipeToDismissReference(Opcode.CHECK_CAST, true)
        val swipeToDismissNewInstanceReference = getSwipeToDismissReference(Opcode.NEW_INSTANCE, true)
        val swipeToDismissInvokeStaticReference = getSwipeToDismissReference(Opcode.INVOKE_STATIC, false)
        val swipeToDismissInvokeDirectReference = getSwipeToDismissReference(Opcode.INVOKE_DIRECT, false)
        val swipeToDismissInvokeInterfaceSecondaryReference = getSwipeToDismissReference(Opcode.INVOKE_INTERFACE, false)
        val dismissBehaviorMethodRef = HandleSignInEventFingerprint.method.let { m ->
            val returnIndex = m.indexOfFirstInstructionOrThrow(Opcode.RETURN_VOID)
            val invokeIndex = m.indexOfFirstInstructionReversedOrThrow(returnIndex, Opcode.INVOKE_VIRTUAL)

            m.getInstruction<ReferenceInstruction>(invokeIndex).reference as MethodReference
        }

        val dismissBehaviorMethod = mutableClassDefBy(dismissBehaviorMethodRef.definingClass).methods.single {
            it.name == dismissBehaviorMethodRef.name && it.parameters == dismissBehaviorMethodRef.parameterTypes && it.returnType == dismissBehaviorMethodRef.returnType
        }

        dismissBehaviorMethod.apply {
            val insertIndex = indexOfFirstInstructionOrThrow {
                getReference<FieldReference>()?.type == "Ljava/util/concurrent/atomic/AtomicBoolean;"
            }
            val primaryRegister = getInstruction<TwoRegisterInstruction>(insertIndex).registerB
            val freeRegister = findFreeRegister(insertIndex, primaryRegister)
            val totalRegs = implementation!!.registerCount
            val clobberRegs = (0 until totalRegs).filter { it != primaryRegister }

            if (clobberRegs.size < 3) {
                throw IllegalStateException("Method lacks sufficient registers for injection (total: ${totalRegs})")
            }

            val secondaryRegister = clobberRegs[0]
            val tertiaryRegister = clobberRegs[1]
            val nullRegister = clobberRegs[2]

            addInstructionsAtControlFlowLabel(
                insertIndex, """
                    invoke-static {}, $EXTENSION_CLASS->enableSwipeToDismissMiniplayer()Z
                    move-result v$freeRegister
                    if-nez v$freeRegister, :dismiss
                    
                    # We are safe to aggressively clobber inside here
                    iget-object v$primaryRegister, v$primaryRegister, $swipeToDismissIGetObjectReference
                    invoke-interface {v$primaryRegister}, $swipeToDismissInvokeInterfacePrimaryReference
                    move-result-object v$primaryRegister
                    check-cast v$primaryRegister, $swipeToDismissCheckCastReference
                    
                    sget-object v$secondaryRegister, $swipeToDismissSGetObjectReference
                    new-instance v$tertiaryRegister, $swipeToDismissNewInstanceReference
                    
                    const v$nullRegister, 0x878b
                    invoke-static {v$nullRegister}, $swipeToDismissInvokeStaticReference
                    move-result-object v$nullRegister
                    invoke-direct {v$tertiaryRegister, v$nullRegister}, $swipeToDismissInvokeDirectReference
                    
                    const/4 v$nullRegister, 0x0
                    invoke-interface {v$primaryRegister, v$secondaryRegister, v$tertiaryRegister, v$nullRegister}, $swipeToDismissInvokeInterfaceSecondaryReference
                    return-void
                    
                    :dismiss
                    nop
                """
            )
        }

        MiniPlayerDefaultTextFingerprint.method.apply {
            if (parameters.isEmpty()) {
                addInstructions(0, """
                    invoke-static {}, $EXTENSION_CLASS->enableSwipeToDismissMiniplayer()Z
                    move-result v0
                    if-eqz v0, :continue_exec
                    return-void
                    :continue_exec
                """)
            } else {
                val insertIndex = indexOfFirstInstructionOrThrow(Opcode.IF_NE)
                val insertRegister = getInstruction<TwoRegisterInstruction>(insertIndex).registerB

                addInstructions(insertIndex, """
                    invoke-static {v$insertRegister}, $EXTENSION_CLASS->enableSwipeToDismissMiniplayer(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$insertRegister
                """)
            }
        }

        val targetMethod = MiniPlayerDefaultViewVisibilityFingerprint.classDef.methods.first {
            it.parameters == listOf("Landroid/view/View;", "I")
        }

        targetMethod.apply {
            val bottomSheetBehaviorIndex = indexOfFirstInstructionOrThrow {
                val reference = getReference<MethodReference>()
                opcode == Opcode.INVOKE_VIRTUAL &&
                        reference?.definingClass == "Lcom/google/android/material/bottomsheet/BottomSheetBehavior;" &&
                        reference.parameterTypes.firstOrNull() == "Z"
            }

            val invokeInstruction = getInstruction<FiveRegisterInstruction>(bottomSheetBehaviorIndex)
            val invokeReference = (invokeInstruction as ReferenceInstruction).reference as MethodReference
            val registerC = invokeInstruction.registerC
            val registerD = invokeInstruction.registerD
            replaceInstruction(bottomSheetBehaviorIndex, BuilderInstruction10x(Opcode.NOP))

            addInstructionsAtControlFlowLabel(
                bottomSheetBehaviorIndex, """
                    invoke-static {}, $EXTENSION_CLASS->enableSwipeToDismissMiniplayer()Z
                    move-result v$registerD
                    if-nez v$registerD, :skip_invoke
                    invoke-virtual {v$registerC, v$registerD}, $invokeReference
                    :skip_invoke
                    nop
                """
            )
        }
    }
}