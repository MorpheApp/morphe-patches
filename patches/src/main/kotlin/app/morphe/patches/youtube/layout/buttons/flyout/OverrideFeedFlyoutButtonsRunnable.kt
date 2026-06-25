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
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.insertLiteralOverride
import app.morphe.util.matchSingle
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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
        sharedExtensionPatch,
        resourceMappingPatch
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

        val feedFlyoutButtonsContainerSuperclass = Fingerprint(
            filters = listOf(
                resourceLiteral(ResourceType.DIMEN, "innertube_menu_width_increment_dp"),
                methodCall("Landroid/widget/ListPopupWindow;->show()V")
            ),
            custom = { method, _ ->
                !AccessFlags.STATIC.isSet(method.accessFlags)
            }
        ).matchSingle().classDef.type

        Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf("L", "Landroid/view/View;", "Ljava/lang/Object;", "L"),
            custom = { _, classDef ->
                classDef.superclass == feedFlyoutButtonsContainerSuperclass
            }
        ).method.addInstruction(
            0,
            "invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->extractVideoIdFromFlyoutBuffer(Ljava/lang/Object;)V"
        )

        val feedFlyoutButtonsInitializerMethod = FeedFlyoutButtonsInitializerFingerprint.methodOrNull

        if (feedFlyoutButtonsInitializerMethod != null) {
            FeedFlyoutButtonsInitializerFingerprint.let { fingerprint ->
                val enumClassInstructionIndex = fingerprint.instructionMatches[1].index
                val enumClassInstructionRegister =
                    feedFlyoutButtonsInitializerMethod.getInstruction<OneRegisterInstruction>(
                        enumClassInstructionIndex
                    ).registerA
                val charSequenceCheckIndex = fingerprint.instructionMatches[5].index
                val charSequenceCheckRegister =
                    feedFlyoutButtonsInitializerMethod.getInstruction<OneRegisterInstruction>(
                        charSequenceCheckIndex
                    ).registerA
                val freeRegister = feedFlyoutButtonsInitializerMethod.getFreeRegisterProvider(
                    enumClassInstructionIndex,
                    1,
                    listOf(
                        enumClassInstructionRegister,
                        charSequenceCheckRegister,
                        feedFlyoutButtonsInitializerMethod.getInstruction<OneRegisterInstruction>(
                            fingerprint.instructionMatches[4].index
                        ).registerA
                    )
                ).getFreeRegister()
                val enumIntFieldReference =
                    feedFlyoutButtonsInitializerMethod.getInstruction<BuilderInstruction22c>(
                        fingerprint.instructionMatches[7].index
                    ).reference
                val enumMethodCallReference =
                    feedFlyoutButtonsInitializerMethod.getInstruction<BuilderInstruction35c>(
                        fingerprint.instructionMatches[8].index
                    ).reference
                val runnableObjectInstructionIndex = fingerprint.instructionMatches.last().index
                val runnableObjectInstructionRegister =
                    feedFlyoutButtonsInitializerMethod.getInstruction<BuilderInstruction22c>(
                        runnableObjectInstructionIndex
                    ).registerA

                listOf(
                    """
                        invoke-static { v$runnableObjectInstructionRegister }, $EXTENSION_CLASS->replaceButtonRunnable(Ljava/lang/Runnable;)Ljava/lang/Runnable;
                        move-result-object v$runnableObjectInstructionRegister
                    """,
                    """
                        iget v$freeRegister, v$enumClassInstructionRegister, $enumIntFieldReference
                        invoke-static { v$freeRegister }, $enumMethodCallReference
                        move-result-object v$freeRegister
                        invoke-static { v$freeRegister, v$charSequenceCheckRegister }, $EXTENSION_CLASS->setCurrentHandledButtonInfo(Ljava/lang/Enum;Ljava/lang/CharSequence;)V
                    """,
                ).forEachIndexed { index, patchLogic ->
                    feedFlyoutButtonsInitializerMethod.addInstructions(
                        if (index == 0) runnableObjectInstructionIndex else charSequenceCheckIndex,
                        patchLogic
                    )
                }
            }

            val onItemClickFingerprint = Fingerprint(
                definingClass = feedFlyoutButtonsInitializerMethod.definingClass,
                custom = { method, _ ->
                    method.name == "onItemClick"
                }
            )
            if (onItemClickFingerprint.matchOrNull() != null) {
                // Not all versions use Runnables to execute onClick
                // operations for flyout buttons.
                onItemClickFingerprint.method.addInstructions(
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

                // This literal override allows the execution of flyout
                // buffer method on older YouTube versions.
                FlyoutBufferDisablerLiteralFingerprint.let {
                    it.method.insertLiteralOverride(
                        it.instructionMatches.first().index,
                        "$EXTENSION_CLASS->overrideFlyoutBufferDisabler(Z)Z"
                    )
                }
            }
        }
    }
}
