/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2182
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_12_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findFieldFromToString
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/SeekbarThumbnailPreviewPatch;"
internal const val EXTENSION_TIMELINE_MARKER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/SeekbarThumbnailPreviewPatch$TimelineMarker;"

val seekbarThumbnailPreviewPatch = bytecodePatch(
    description = "Adds an option to restore the seekbar thumbnail preview."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        resourceMappingPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_seekbar_thumbnail_preview")
        )

        val updatePointMethodRef = SeekbarUpdatePointFingerprint.instructionMatches[1]
            .getInstruction<ReferenceInstruction>().getReference<MethodReference>()!!

        // To show the thumbnail during the seeking straight on seekbar.
        SeekbarHandlerOnTouchFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, Landroid/graphics/Point;
                invoke-direct { v0 }, Landroid/graphics/Point;-><init>()V
                invoke-interface { p0, v0 }, $updatePointMethodRef
                invoke-static { p0, p1, v0 }, $EXTENSION_CLASS->updateThumbnailPreview(Landroid/view/View;Landroid/view/MotionEvent;Landroid/graphics/Point;)V
            """
        )

        // To show the thumbnail during the use of slide to seek feature.
        SlideSeekbarHandlerOnTouchFingerprint.method.apply {
            fun getSeekbarReference(index: Int) = SlideSeekbarGetViewControllerFingerprint
                .instructionMatches[index].getInstruction<ReferenceInstruction>()
                .getReference<FieldReference>()!!

            addInstructions(
                0,
                """
                    iget-object v0, p0, ${getSeekbarReference(0)}
                    iget-object v0, v0, ${getSeekbarReference(1)}
                    iget-object v0, v0, ${getSeekbarReference(3)}
                    new-instance v1, Landroid/graphics/Point;
                    invoke-direct { v1 }, Landroid/graphics/Point;-><init>()V
                    invoke-interface { v0, v1 }, $updatePointMethodRef
                    invoke-static { p1, p2, v1 }, $EXTENSION_CLASS->updateThumbnailPreview(Landroid/view/View;Landroid/view/MotionEvent;Landroid/graphics/Point;)V
                """
            )
        }

        SeekbarFineScrubbingBitmapFingerprint.method.addInstruction(
            1,
            "invoke-static { p1 }, $EXTENSION_CLASS->" +
                    "setFineScrubbingPreviewBitmap(Landroid/graphics/Bitmap;)V"
        )

        if (is_21_12_or_greater) {
            SeekbarBigBoardsUpdateFingerprint
        } else {
            SeekbarBigBoardsUpdateLegacyFingerprint
        }.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->disableBigBoardUpdate()Z
                move-result v0
                if-eqz v0, :allow_big_board_update
                const/4 v0, 0x0
                return v0
                :allow_big_board_update
                nop
            """
        )

        getTimelineMarkersArrayFingerprint(
            TimelineMarkerFingerprint.classDef.type
        ).method.apply {
            val index = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            addInstruction(
                index,
                "invoke-static { v$register }, $EXTENSION_CLASS->" +
                        "setTimelineMarkers([$EXTENSION_TIMELINE_MARKER_INTERFACE)V"
            )
        }

        TimelineMarkerFingerprint.let {
            it.classDef.apply {
                interfaces.add(EXTENSION_TIMELINE_MARKER_INTERFACE)

                val startMillis = it.method.findFieldFromToString("startMillis=")
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getStartMillis",
                        listOf(),
                        "J",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                iget-wide v0, p0, $startMillis
                                return-wide v0
                            """
                        )
                    }
                )

                val endMillis = it.method.findFieldFromToString("endMillis=")
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getEndMillis",
                        listOf(),
                        "J",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                iget-wide v0, p0, $endMillis
                                return-wide v0
                            """
                        )
                    }
                )

                val title = it.method.findFieldFromToString("title=")
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getTitle",
                        listOf(),
                        "Ljava/lang/CharSequence;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                iget-object v0, p0, $title
                                return-object v0
                            """
                        )
                    }
                )
            }
        }
    }
}
