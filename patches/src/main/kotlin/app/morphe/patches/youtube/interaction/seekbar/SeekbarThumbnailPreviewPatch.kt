/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2182
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_12_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/SeekbarThumbnailPreviewPatch;"

val seekbarThumbnailPreviewPatch = bytecodePatch(
    description = "Adds an option to restore the seekbar thumbnail preview."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_seekbar_thumbnail_preview")
        )

        SeekbarTrackballPosXAndTimeMillisFingerprint.apply {
            instructionMatches.last().getMethodCalled().addInstruction(
                0,
                "invoke-static { p1 }, $EXTENSION_CLASS->setFineScrubbingTimeMillis(I)V"
            )

            val posXInstructionIndex = instructionMatches.first().index
            val posXInstructionRegister = method.getInstruction<TwoRegisterInstruction>(posXInstructionIndex).registerA

            method.addInstruction(
                posXInstructionIndex + 1,
                "invoke-static { p0, p1, v$posXInstructionRegister }, $EXTENSION_CLASS->" +
                        "updateThumbnailPreview(Landroid/view/View;Landroid/view/MotionEvent;I)V"
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
    }
}
