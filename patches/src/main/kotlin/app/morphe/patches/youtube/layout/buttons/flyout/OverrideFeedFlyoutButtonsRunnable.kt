/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.buttons.flyout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findFreeRegister
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/OverrideFeedFlyoutButtonRunnablePatch;"

private const val EXTENSION_PROTOCOL_BUFFER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/OverrideFeedFlyoutButtonRunnablePatch$ProtocolBufferFieldInterface;"


@Suppress("unused")
val overrideFeedFlyoutButtonsRunnable = bytecodePatch(
    name = "Override feed flyout buttons runnable",
    description = "In combination with other patches, this allows replacing the runnable (used for the onClick method) of buttons in the feed flyout."
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // Add interface method to get protocol buffer.
        InteractiveStickerRendererGetEditViewFingerprint.let {
            val bufferField = it.instructionMatches.last().getFieldAccessed()

            mutableClassDefBy(bufferField.definingClass).apply {
                interfaces.add(EXTENSION_PROTOCOL_BUFFER_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getBuffer",
                        listOf(),
                        "[B",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                iget-object v0, p0, $bufferField
                                return-object v0      
                            """
                        )
                    }
                )
            }
        }

        // Hook flyout menu protocol buffer.
        FeedFlyoutButtonsContainerFingerprint.matchAll().forEach {
            it.method.addInstruction(
                0,
                "invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->extractVideoIdFromFlyoutBuffer(Ljava/lang/Object;)V"
            )
        }

        FeedFlyoutButtonsInitializerFingerprint.let {
            it.method.apply {
                val runnableObjectInstructionIndex = it.instructionMatches.last().index
                val runnableObjectInstructionRegister = it.instructionMatches.last()
                    .getInstruction<TwoRegisterInstruction>().registerA
                addInstructions(
                    runnableObjectInstructionIndex,
                    """
                        invoke-static { v$runnableObjectInstructionRegister }, $EXTENSION_CLASS->replaceButtonRunnable(Ljava/lang/Runnable;)Ljava/lang/Runnable;
                        move-result-object v$runnableObjectInstructionRegister
                    """
                )

                val charSequenceCheckIndex = it.instructionMatches[5].index
                val charSequenceCheckRegister = getInstruction<OneRegisterInstruction>(
                    charSequenceCheckIndex
                ).registerA
                val enumClassInstructionRegister = it.instructionMatches[1]
                    .getInstruction<OneRegisterInstruction>().registerA
                val freeRegister = findFreeRegister(
                    charSequenceCheckIndex,
                    charSequenceCheckRegister,
                    enumClassInstructionRegister
                )

                val enumIntFieldReference = it.instructionMatches[7]
                    .getInstruction<ReferenceInstruction>().reference
                val enumMethodCallReference = it.instructionMatches[8]
                    .getInstruction<ReferenceInstruction>().reference

                addInstructions(
                    charSequenceCheckIndex,
                    """
                        iget v$freeRegister, v$enumClassInstructionRegister, $enumIntFieldReference
                        invoke-static { v$freeRegister }, $enumMethodCallReference
                        move-result-object v$freeRegister
                        invoke-static { v$freeRegister, v$charSequenceCheckRegister }, $EXTENSION_CLASS->setCurrentHandledButtonInfo(Ljava/lang/Enum;Ljava/lang/CharSequence;)V
                    """
                )
            }
        }

        // Old versions like 20.21.37 need to replace on item click
        Fingerprint(
            definingClass = FeedFlyoutButtonsInitializerFingerprint.method.definingClass,
            name = "onItemClick"
        ).method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p3 }, $EXTENSION_CLASS->replaceOnItemClick(I)Z
                move-result p2
                if-eqz p2, :block_item_click
                return-void
                :block_item_click
                nop
            """
        )

        // Turn off feature flag to allow th execution of flyout
        // buffer method on older YouTube versions.
        FlyoutBufferDisablerLiteralFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->overrideFlyoutBufferDisabler(Z)Z"
            )
        }
    }
}
