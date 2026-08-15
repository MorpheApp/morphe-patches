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
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.chapters.chaptersHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_12_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_21_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.SeekbarOnDrawFingerprint
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/SeekbarThumbnailPreviewPatch;"

val seekbarThumbnailPreviewPatch = bytecodePatch(
    description = "Adds an option to restore the seekbar thumbnail preview."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        resourceMappingPatch,
        chaptersHookPatch
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
                invoke-static { p0, p1, v0 }, $EXTENSION_CLASS->updateHandlerThumbnailPreview(Landroid/view/View;Landroid/view/MotionEvent;Landroid/graphics/Point;)V
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
                    invoke-static { p1, p2, v1 }, $EXTENSION_CLASS->updateSlideThumbnailPreview(Landroid/view/View;Landroid/view/MotionEvent;Landroid/graphics/Point;)V
                """
            )
        }

        SeekbarFineScrubbingBitmapFingerprint.method.addInstruction(
            1,
            "invoke-static { p1 }, $EXTENSION_CLASS->" +
                    "setFineScrubbingPreviewBitmap(Landroid/graphics/Bitmap;)V"
        )

        SeekbarOnDrawFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                    "setSeekbarRectangle(Landroid/view/View;)V"
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

        PreciseSeekingRecyclerViewFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->setPreciseSeekingVisible(Landroid/support/v7/widget/RecyclerView;)V"
        )

        if (is_21_21_or_greater) {
            ShortsDisableSeekbarThumbnailsFeatureFlagFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->disableShortsSeekbarThumbnails(Z)Z"
                )
            }
        }
    }
}
