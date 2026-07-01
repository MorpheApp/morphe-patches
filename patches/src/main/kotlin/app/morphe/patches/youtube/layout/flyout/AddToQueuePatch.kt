/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.flyout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.hide.general.ContextualMenuItemBuilderFingerprint
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_12_or_greater
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
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.util.logging.Logger

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
            mainFingerprint.method.apply {
                val runnableIndex = mainFingerprint.instructionMatches.last().index
                val runnableRegister = getInstruction<TwoRegisterInstruction>(runnableIndex).registerA
                addInstructions(
                    runnableIndex,
                    """
                        invoke-static { v$runnableRegister }, $EXTENSION_CLASS->replaceButtonRunnable(Ljava/lang/Runnable;)Ljava/lang/Runnable;
                        move-result-object v$runnableRegister
                    """
                )
            }

            val charCheckIndex = mainFingerprint.instructionMatches[4].index
            val enumMethodRegister = mainFingerprint.instructionMatches[1].getInstruction<OneRegisterInstruction>().registerA
            val charCheckRegister = mainFingerprint.method.getInstruction<OneRegisterInstruction>(charCheckIndex).registerA
            val enumIntField = mainFingerprint.instructionMatches[6].getInstruction<ReferenceInstruction>().reference
            val enumMethodCall = mainFingerprint.instructionMatches[7].getInstruction<ReferenceInstruction>().reference

            listOf(
                mainFingerprint,
                ContextualMenuItemBuilderFingerprint,
                Fingerprint(
                    classFingerprint = ContextualMenuItemBuilderFingerprint,
                    name = "onClick",
                    filters = OpcodesFilter.opcodesToFilters(
                        Opcode.IGET_OBJECT,
                        Opcode.CHECK_CAST,
                        Opcode.INVOKE_STATIC,
                        Opcode.MOVE_RESULT_OBJECT,
                        Opcode.IF_EQZ,
                        Opcode.IGET_OBJECT,
                        Opcode.IGET_OBJECT,
                        Opcode.INVOKE_INTERFACE,
                    )
                ),
                Fingerprint(
                    classFingerprint = FeedFlyoutButtonsInitializerFingerprint,
                    name = "onItemClick"
                )
            ).forEachIndexed { index, fingerprint ->
                var methodWithEnoughRegistersSize: MutableMethod? = null
                var targetInstructionIndex = 0
                var freeRegister = ""
                var iGetClassRegister = ""
                var secondButtonInfoParameterRegister = ""
                var headerPatch = ""
                var integrationsMethod = ""

                if (index == 0) {
                    methodWithEnoughRegistersSize = fingerprint.method
                    targetInstructionIndex = charCheckIndex
                    freeRegister = "v${fingerprint.method.findFreeRegister(
                        targetInstructionIndex,
                        charCheckRegister,
                        enumMethodRegister
                    )}"
                    headerPatch = ""
                    iGetClassRegister = "v$enumMethodRegister"
                    secondButtonInfoParameterRegister = "v$charCheckRegister"
                    integrationsMethod = "invoke-static { $freeRegister, $secondButtonInfoParameterRegister }, $EXTENSION_CLASS->setCurrentButtonInfo(Ljava/lang/Enum;Ljava/lang/Object;)V"
                } else if (!is_21_05_or_greater) {
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
                    fun getPostHeaderPatch(targetInstructionRegister: String, freeRegister: String): String = """
                        invoke-static { $targetInstructionRegister }, ${mainFingerprint.instructionMatches[0].getInstruction<ReferenceInstruction>().reference}
                        move-result-object $freeRegister
                    """

                    if (index == 1) {
                        methodWithEnoughRegistersSize = fingerprint.method.cloneParameters()

                        val cloneParametersAdd = fingerprint.method.numberOfParameterRegisters

                        targetInstructionIndex =
                            fingerprint.instructionMatches[3].index + cloneParametersAdd

                        val targetInstructionRegister = "v${
                            methodWithEnoughRegistersSize.getInstruction<BuilderInstruction35c>(
                                targetInstructionIndex
                            ).registerC
                        }"

                        freeRegister = "p0"; iGetClassRegister = freeRegister
                        secondButtonInfoParameterRegister = "v${
                            methodWithEnoughRegistersSize.getInstruction<BuilderInstruction35c>(
                                fingerprint.instructionMatches[2].index + cloneParametersAdd
                            ).registerC
                        }"
                        headerPatch = getPostHeaderPatch(targetInstructionRegister, freeRegister)
                        integrationsMethod = "invoke-static { $freeRegister, $secondButtonInfoParameterRegister }, $EXTENSION_CLASS->setCurrentButtonInfo(Ljava/lang/Enum;Ljava/lang/Object;)V"
                    } else if (index == 2) {
                        methodWithEnoughRegistersSize = fingerprint.method
                        freeRegister = "v0"; iGetClassRegister = freeRegister
                        targetInstructionIndex = 0

                        val enumMethodParameterClassReference = fingerprint.instructionMatches.first().getInstruction<
                                ReferenceInstruction
                        >().reference
                        val enumMethodParameterClassName = fingerprint.instructionMatches[1].getInstruction<
                                ReferenceInstruction
                        >().reference

                        headerPatch = """
                            iget-object v0, p0, $enumMethodParameterClassReference
                            check-cast v0, $enumMethodParameterClassName
                        """ + getPostHeaderPatch("v0", "v0")
                        integrationsMethod = """
                            invoke-virtual {v0}, Ljava/lang/Enum;->name()Ljava/lang/String;
                            move-result-object v0
                        """ + getReplaceOnItemClickPatch("v0", "v0")
                    } else if (index == 3) {
                        fingerprint.method.addInstructionsWithLabels(
                            0,
                            """
                                invoke-static { p3 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                                move-result-object p2
                            """ + getReplaceOnItemClickPatch("p2", "p2")
                        )
                    }
                }

                methodWithEnoughRegistersSize?.addInstructions(
                    targetInstructionIndex,
                    headerPatch + """
                        iget $freeRegister, $iGetClassRegister, $enumIntField
                        invoke-static { $freeRegister }, $enumMethodCall
                        move-result-object $freeRegister
                    """ + integrationsMethod
                )
            }
        }

        FeedBottomSheetFlyoutFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->setBottomSheetFlyout(Landroid/app/Dialog;)V"
                )
            }
        }

        if (is_21_12_or_greater) {
            FeedPopupWindowFlyoutFingerprint.matchAll(2..2).forEach {
                it.method.apply {
                    val instructionIndex = it.instructionMatches.last().index
                    val instructionRegister = getInstruction<FiveRegisterInstruction>(instructionIndex).registerC

                    addInstruction(
                        instructionIndex,
                        "invoke-static { v$instructionRegister }, $EXTENSION_CLASS->setPopupWindowFlyout(Landroid/widget/PopupWindow;)V"
                    )
                }
            }
        }
    }
}
