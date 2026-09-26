/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.flyout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.shared.StartVideoInformerFingerprint
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_UTILS_CLASS =
    "Lapp/morphe/extension/youtube/patches/utils/FlyoutUtils;"

private const val EXTENSION_FLYOUT_MENU_VIDEO_ID_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/utils/FlyoutUtils$FlyoutMenuVideoIdInterface;"

private const val EXTENSION_PROTOCOL_BUFFER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/utils/FlyoutUtils$ProtocolBufferFieldInterface;"

val flyoutPatch = bytecodePatch(
    description = "Provides shared flyout menu hooks.",
) {
    dependsOn(
        sharedExtensionPatch,
        lithoFilterPatch,
        videoInformationPatch,
        elementProtoParserHookPatch,
    )

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
                        location = MatchAfterWithin(2)
                    )
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

        FeedFlyoutBufferObjectFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p2 .. p2 }, $EXTENSION_UTILS_CLASS->" +
                "extractFlyoutIdFromMap(Ljava/util/Map;)V"
        )

        OnClickLithoButtonBufferObjectFingerprint.let {
            val match = it.instructionMatches[3]
            val index = match.index
            val register = match.getInstruction<FiveRegisterInstruction>().registerC

            it.method.addInstruction(
                index + 1,
                "invoke-static { v$register }, $EXTENSION_UTILS_CLASS->" +
                    "extractFlyoutIdFromLithoButton(Ljava/util/Map;)V"
            )
        }

        FullHistoryFlyoutBufferObjectFingerprint.let {
            val match = it.instructionMatches[2]
            val index = match.index
            val register = match.getInstruction<OneRegisterInstruction>().registerA

            it.method.addInstruction(
                index + 1,
                "invoke-static { v$register }, $EXTENSION_UTILS_CLASS->" +
                    "extractFlyoutIdFromObject(Ljava/lang/Object;)V"
            )
        }

        // end region

        FeedBottomSheetFlyoutFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_UTILS_CLASS->" +
                        "setBottomSheetFlyout(Landroid/app/Dialog;)V"
                )
            }
        }

        FeedPopupWindowFlyoutFingerprint.matchAll(2..4).forEach {
            it.method.apply {
                val instructionIndex = it.instructionMatches.last().index
                val instructionRegister =
                    getInstruction<FiveRegisterInstruction>(instructionIndex).registerC

                addInstruction(
                    instructionIndex,
                    "invoke-static { v$instructionRegister }, $EXTENSION_UTILS_CLASS->" +
                        "setPopupWindowFlyout(Landroid/widget/PopupWindow;)V"
                )
            }
        }

        StartVideoInformerFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_UTILS_CLASS->resetVideoMarkedAsForKids()V"
        )

        hookElement("$EXTENSION_UTILS_CLASS->onCommentsLoaded")
        addLithoFilter(
            "Lapp/morphe/extension/youtube/patches/components/ChannelPageFlyoutFilter;"
        )
    }
}
