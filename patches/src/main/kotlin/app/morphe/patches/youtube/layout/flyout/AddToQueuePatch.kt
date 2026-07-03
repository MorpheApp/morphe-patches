/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.flyout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.hide.general.ContextualMenuItemBuilderFingerprint
import app.morphe.patches.youtube.layout.hide.general.ContextualMenuItemBuilderOnClickFingerprint
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.cloneParameters
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/AddToQueuePatch;"

private const val EXTENSION_FLYOUT_MENU_VIDEO_ID_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/AddToQueuePatch$FlyoutMenuVideoIdInterface;"

private const val EXTENSION_PROTOCOL_BUFFER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/AddToQueuePatch$ProtocolBufferFieldInterface;"


@Suppress("unused")
val addToQueuePatch = bytecodePatch(
    name = "Add to queue",
    description = "Overrides the feed flyout 'Play next in queue' with the Morphe video queue."
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        videoInformationPatch,
        authHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_queue_override_flyout_menu", summary = true)
        )

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


        fun addProtocolVideoIdInterface(messageType: String) {
            // videoId is the only string field in the class initialized to an empty string.
            val videoIdStringField = Fingerprint(
                definingClass = messageType,
                name = "<init>",
                filters = listOf(
                    string(""),
                    fieldAccess(
                        opcode = Opcode.IPUT_OBJECT,
                        definingClass = "this",
                        type = "Ljava/lang/String;",
                        location = MatchAfterWithin(2))
                )
            ).instructionMatches.last().getFieldAccessed()

            mutableClassDefBy(messageType).apply {
                interfaces.add(EXTENSION_FLYOUT_MENU_VIDEO_ID_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getVideoId",
                        listOf(),
                        "Ljava/lang/String;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                        """
                                iget-object v0, p0, $videoIdStringField
                                return-object v0
                            """
                        )
                    }
                )
            }
        }

        // Full watch history list. Needs special treatment because it doesn't use litho.
        addProtocolVideoIdInterface(
            FlyoutMenuItemMessageFingerprint
                .instructionMatches[1]
                .getInstruction<ReferenceInstruction>()
                .getReference<TypeReference>()!!
                .type
        )

        // Playlists in 'You' tab. Doesn't seem required for 21.x but is required for 20.21
        addProtocolVideoIdInterface(
            SingularGeneratedExtensionFingerprint
                .instructionMatches[1]
                .getInstruction<ReferenceInstruction>()
                .getReference<FieldReference>()!!
                .type
        )

        // region Hook flyout menu protocol buffer object.
        FeedFlyoutBufferObjectFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->extractVideoId(Ljava/util/Map;)V"
        )

        FullHistoryFlyoutBufferObjectFingerprint.let {
            it.method.apply {
                val instructionIndex = it.instructionMatches[2].index
                val instructionRegister = getInstruction<OneRegisterInstruction>(
                    instructionIndex
                ).registerA

                addInstruction(
                    instructionIndex + 1,
                    "invoke-static { v$instructionRegister }, $EXTENSION_CLASS->extractVideoId(Ljava/lang/Object;)V"
                )
            }
        }

        // end region

        FeedFlyoutButtonsInitializerFingerprint.let { mainFingerprint ->
            val mainFingerprintMatches = mainFingerprint.instructionMatches
            val getCharSequenceReference = mainFingerprintMatches.first().getInstruction<ReferenceInstruction>().reference
            val enumMethodRegister = mainFingerprintMatches[1].getInstruction<OneRegisterInstruction>().registerA
            val charCheckIndex = mainFingerprintMatches[4].index
            val enumIntField = mainFingerprintMatches[6].getInstruction<ReferenceInstruction>().reference
            val enumMethodCall = mainFingerprintMatches[7].getInstruction<ReferenceInstruction>().reference
            val runnableIndex = mainFingerprintMatches.last().index
            val charCheckRegister = mainFingerprint.method.getInstruction<OneRegisterInstruction>(charCheckIndex).registerA

            mainFingerprint.method.apply {
                val runnableRegister = getInstruction<TwoRegisterInstruction>(runnableIndex).registerA
                addInstructions(
                    runnableIndex,
                    """
                        invoke-static { v$runnableRegister }, $EXTENSION_CLASS->replaceButtonRunnable(Ljava/lang/Runnable;)Ljava/lang/Runnable;
                        move-result-object v$runnableRegister
                    """
                )

                val freeRegister = findFreeRegister(charCheckIndex, charCheckRegister, enumMethodRegister)
                addInstructions(
                    charCheckIndex,
                    """
                        iget v$freeRegister, v$enumMethodRegister, $enumIntField
                        invoke-static { v$freeRegister }, $enumMethodCall
                        move-result-object v$freeRegister
                        invoke-static { v$freeRegister, v$charCheckRegister }, $EXTENSION_CLASS->setCurrentButtonInfo(Ljava/lang/Enum;Ljava/lang/Object;)V
                    """
                )
            }

            if (!is_21_05_or_greater) {
                ContextualMenuItemBuilderFingerprint.let {
                    it.method.cloneParameters().apply {
                        val filterIndexClonedOffset = numberOfParameterRegisters
                        val targetInstructionIndex = it.instructionMatches[3].index + filterIndexClonedOffset
                        val targetInstructionRegister = getInstruction<FiveRegisterInstruction>(
                            targetInstructionIndex
                        ).registerC
                        val secondButtonInfoParameterRegister = getInstruction<FiveRegisterInstruction>(
                            it.instructionMatches[2].index + filterIndexClonedOffset
                        ).registerC

                        addInstructions(
                            targetInstructionIndex,
                                """
                                invoke-static { v$targetInstructionRegister }, $getCharSequenceReference
                                move-result-object p0
                                iget p0, p0, $enumIntField
                                invoke-static { p0 }, $enumMethodCall
                                move-result-object p0
                                invoke-static { p0, v$secondButtonInfoParameterRegister }, $EXTENSION_CLASS->setCurrentButtonInfo(Ljava/lang/Enum;Ljava/lang/Object;)V
                            """
                        )
                    }
                }

                fun getReplaceOnItemClickPatch(
                    targetInstructionRegister: String,
                    freeRegister: String
                ): String = """
                    invoke-static { $targetInstructionRegister }, $EXTENSION_CLASS->replaceOnItemClick(Ljava/lang/Object;)Z
                    move-result $freeRegister
                    if-eqz $freeRegister, :block_item_click
                    return-void
                    :block_item_click
                    nop
                """

                ContextualMenuItemBuilderOnClickFingerprint.let {
                    val enumMethodParameterClassReference = it.instructionMatches.first()
                        .getInstruction<ReferenceInstruction>().reference
                    val enumMethodParameterClassName = it.instructionMatches[1]
                        .getInstruction<ReferenceInstruction>().reference

                    it.method.addInstructions(
                        0,
                        """
                            iget-object v0, p0, $enumMethodParameterClassReference
                            check-cast v0, $enumMethodParameterClassName
                            invoke-static { v0 }, $getCharSequenceReference
                            move-result-object v0
                            iget v0, v0, $enumIntField
                            invoke-static { v0 }, $enumMethodCall
                            move-result-object v0
                            invoke-virtual {v0}, Ljava/lang/Enum;->name()Ljava/lang/String;
                            move-result-object v0
                        """ + getReplaceOnItemClickPatch("v0", "v0")
                    )
                }

                FeedFlyoutButtonsInitializerOnItemClickFingerprint.method.addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p3 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                        move-result-object p2
                    """ + getReplaceOnItemClickPatch("p2", "p2")
                )
            }
        }

        FeedBottomSheetFlyoutFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->" +
                            "setBottomSheetFlyout(Landroid/app/Dialog;)V"
                )
            }
        }

        FeedPopupWindowFlyoutFingerprint.matchAll(2..4).forEach {
            it.method.apply {
                val instructionIndex = it.instructionMatches.last().index
                val instructionRegister = getInstruction<FiveRegisterInstruction>(instructionIndex).registerC

                addInstruction(
                    instructionIndex,
                    "invoke-static { v$instructionRegister }, $EXTENSION_CLASS->" +
                            "setPopupWindowFlyout(Landroid/widget/PopupWindow;)V"
                )
            }
        }
    }
}
