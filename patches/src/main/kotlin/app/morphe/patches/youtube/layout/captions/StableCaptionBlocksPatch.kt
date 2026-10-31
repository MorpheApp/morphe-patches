/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.playservice.is_21_10_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.cloneMutable
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/StableCaptionBlocks;"
private const val EXTENSION_STATE_CLASS =
    $$"Lapp/morphe/extension/youtube/patches/StableCaptionBlocks$State;"
private const val EXTENSION_FRAME_CLASS =
    $$"Lapp/morphe/extension/youtube/patches/StableCaptionBlocks$Frame;"
private const val EXTENSION_CUE_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/StableCaptionBlocks$Cue;"
private const val EXTENSION_ROW_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/StableCaptionBlocks$Row;"
private const val EXTENSION_WINDOW_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/StableCaptionBlocks$Window;"
private const val EXTENSION_TIMELINE_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/StableCaptionBlocks$Timeline;"

internal val stableCaptionBlocksPatch = bytecodePatch(
    description = "Adds an option to show automatic captions in stable two-line blocks.",
) {
    // The window measure method must be patched first, since this inserts instructions before it.
    dependsOn(versionCheckPatch, translatedCaptionPensPatch, captionWindowMeasurePatch)

    execute {
        if (!is_21_10_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "Stable caption blocks requires YouTube 21.10+"
            )
        }

        settingsMenuCaptionGroup.add(
            SwitchPreference("morphe_stable_caption_blocks", summary = true)
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // Cue fields in the order: id, time, visible text, full text, settings.
        val cueFields = CaptionCueToStringFingerprint.instructionMatches.take(5).map {
            it.instruction.getReference<FieldReference>()!!
        }
        val (idField, timeField, visibleTextField, fullTextField, settingsField) = cueFields
        val cueType = idField.definingClass
        val cueConstructor = CaptionCueConstructorFingerprint.method
        val defaultSettingsField = getCaptionDefaultSettingsFingerprint(settingsField.type)
            .instructionMatches.first().instruction.getReference<FieldReference>()!!

        // Cues of a block are copies with a frame span, and with the default settings
        // so blocks always use the default placement.
        mutableClassDefBy(cueType).apply {
            interfaces.add(EXTENSION_CUE_INTERFACE)

            addHelperMethod(
                "patch_getOriginalCaptionVisible",
                "Ljava/lang/CharSequence;",
                smali = """
                    iget-object v0, p0, $visibleTextField
                    return-object v0
                """
            )

            addHelperMethod(
                "patch_getBlockFrame",
                EXTENSION_FRAME_CLASS,
                smali = """
                    iget-object v0, p0, $fullTextField
                    invoke-static { v0 }, $EXTENSION_CLASS->frameOf(Ljava/lang/CharSequence;)$EXTENSION_FRAME_CLASS
                    move-result-object v0
                    return-object v0
                """
            )

            addHelperMethod(
                "patch_copyBlockFrame",
                "Ljava/lang/Object;",
                parameters = listOf(EXTENSION_FRAME_CLASS),
                freeRegisters = 7,
                smali = """
                    # Reuse this cue if it already shows the frame with the default settings.
                    iget-object v6, p0, $settingsField
                    sget-object v0, $defaultSettingsField
                    if-ne v6, v0, :copy_frame
                    invoke-virtual { p0 }, $type->patch_getBlockFrame()$EXTENSION_FRAME_CLASS
                    move-result-object v0
                    if-ne v0, p1, :copy_frame
                    iget-object v0, p0, $visibleTextField
                    invoke-static { v0 }, $EXTENSION_CLASS->frameOf(Ljava/lang/CharSequence;)$EXTENSION_FRAME_CLASS
                    move-result-object v0
                    if-ne v0, p1, :copy_frame
                    return-object p0
                    :copy_frame
                    new-instance v0, $type
                    iget v1, p0, $idField
                    iget-wide v2, p0, $timeField
                    iget-object v4, p0, $visibleTextField
                    iget-object v5, p0, $fullTextField
                    invoke-static { v4, p1 }, $EXTENSION_CLASS->carryFrame(Ljava/lang/CharSequence;$EXTENSION_FRAME_CLASS)Ljava/lang/CharSequence;
                    move-result-object v4
                    invoke-static { v5, p1 }, $EXTENSION_CLASS->carryFrame(Ljava/lang/CharSequence;$EXTENSION_FRAME_CLASS)Ljava/lang/CharSequence;
                    move-result-object v5
                    sget-object v6, $defaultSettingsField
                    invoke-direct/range { v0 .. v6 }, $cueConstructor
                    return-object v0
                """
            )
        }

        // Row used to measure caption text.
        val rowType = CaptionRowLayoutFingerprint.classDef.type
        mutableClassDefBy(rowType).apply {
            interfaces.add(EXTENSION_ROW_INTERFACE)

            addHelperMethod(
                "patch_getCaptionLayout",
                "Landroid/text/StaticLayout;",
                smali = """
                    invoke-virtual { p0 }, ${CaptionRowLayoutFingerprint.method}
                    move-result-object v0
                    return-object v0
                """
            )

            addHelperMethod(
                "patch_setCaptionText",
                "V",
                parameters = listOf("Ljava/lang/CharSequence;"),
                smali = """
                    invoke-virtual { p0, p1 }, ${CaptionRowSetTextFingerprint.method}
                    return-void
                """
            )
        }

        // Fill and outline text layouts of blocks use balanced line breaks.
        CaptionRowLayoutFactoryFingerprint.matchAll().forEach { match ->
            val newInstanceIndex = match.instructionMatches[0].index
            val constructorIndex = match.instructionMatches[1].index
            val register = match.method.getInstruction<OneRegisterInstruction>(newInstanceIndex).registerA

            match.method.apply {
                replaceInstruction(
                    constructorIndex,
                    "invoke-static/range { v${register + 1} .. v${register + 7} }, " +
                            "$EXTENSION_CLASS->createLayout($CAPTION_STATIC_LAYOUT_PARAMETERS)Landroid/text/StaticLayout;"
                )
                addInstruction(constructorIndex + 1, "move-result-object v$register")
                removeInstruction(newInstanceIndex)
            }
        }

        // Window lines are replaced with the block text before the window is measured.
        getCaptionWindowSetterFingerprint(cueType).let {
            val windowFields = listOf(
                "patch_getCaptionCue" to it.instructionMatches[0],
                "patch_getVisibleCaptionLines" to it.instructionMatches[1],
                "patch_getFullCaptionLines" to it.instructionMatches[3],
            ).map { (name, match) -> name to match.instruction.getReference<FieldReference>()!! } +
                    ("patch_getCaptionMeasureRow" to getCaptionWindowConstructorFingerprint(rowType)
                        .instructionMatches.first().instruction.getReference<FieldReference>()!!)

            mutableClassDefBy(SUBTITLE_WINDOW_VIEW_CLASS).apply {
                interfaces.add(EXTENSION_WINDOW_INTERFACE)

                windowFields.forEach { (name, field) ->
                    addHelperMethod(
                        name,
                        if (field.type == "Ljava/util/List;") field.type else "Ljava/lang/Object;",
                        smali = """
                            iget-object v0, p0, $field
                            return-object v0
                        """
                    )
                }
            }
        }

        CaptionWindowMeasureFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->prepare(Landroid/view/View;I)V"
        )

        // Player subclasses can recreate the cue placement.
        getCaptionOverlayPresenterFingerprint(cueType).method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->centerBlocks(Ljava/util/List;)Ljava/util/List;
                move-result-object p1
            """
        )

        // Each parsed track keeps the block state of its source captions.
        CaptionWindowsConstructorFingerprint.let {
            val timelineType = it.classDef.type
            val stateField = "$timelineType->patch_blockState:$EXTENSION_STATE_CLASS"

            mutableClassDefBy(timelineType).apply {
                interfaces.add(EXTENSION_TIMELINE_INTERFACE)

                instanceFields.add(
                    ImmutableField(
                        type,
                        "patch_blockState",
                        EXTENSION_STATE_CLASS,
                        AccessFlags.PRIVATE.value,
                        null,
                        null,
                        null
                    ).toMutable()
                )

                addHelperMethod(
                    "patch_getBlockState",
                    EXTENSION_STATE_CLASS,
                    smali = """
                        iget-object v0, p0, $stateField
                        return-object v0
                    """
                )

                it.method.apply {
                    addInstructions(
                        indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                        """
                            invoke-static {}, $EXTENSION_CLASS->takeState()$EXTENSION_STATE_CLASS
                            move-result-object v0
                            iput-object v0, p0, $stateField
                        """
                    )
                }

                val sampler = getCaptionSamplerFingerprint(cueType).method
                methods.add(sampler.cloneMutable(name = "patch_getOriginalCaptions"))

                sampler.addInstructionsWithLabels(
                    0,
                    """
                        # Method uses many registers, and p0 is not a 4-bit register.
                        move-object/from16 v0, p0
                        iget-object v0, v0, $stateField
                        if-eqz v0, :original
                        invoke-static/range { p0 .. p2 }, $EXTENSION_CLASS->present(${EXTENSION_TIMELINE_INTERFACE}J)Ljava/util/List;
                        move-result-object v0
                        return-object v0
                        :original
                        nop
                    """
                )
            }
        }
    }
}
