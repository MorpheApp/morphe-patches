/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.hookVideoStarted
import app.morphe.patches.youtube.shared.startVideoInformerPatch
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/AutoCaptionsPatch;"

internal val autoCaptionsPatch = patch(
    description = "Adds an option to disable captions from being automatically enabled.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        startVideoInformerPatch,
        videoInformationPatch
    )

    execute {
        settingsMenuCaptionGroup.add(
            ListPreference("morphe_auto_captions_style")
        )

        SubtitleManagerFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->disableAutoCaptions(Z)Z
                        move-result v$register
                    """
                )

                // Read at the start of the method, before the hook above, so its index is not moved by it.
                val reasonIndex = it.instructionMatches.first().index
                val reasonRegister = getInstruction<TwoRegisterInstruction>(reasonIndex).registerA

                addInstruction(
                    reasonIndex + 1,
                    "invoke-static { v$reasonRegister }, $EXTENSION_CLASS->setCaptionSelectionReason(I)V"
                )
            }
        }

        // Same method as the hook above, so it is matched again after it changed the method.
        SubtitleManagerForcedTrackFingerprint.let {
            it.clearMatch()
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->showForcedCaptions(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        onCreateHook(EXTENSION_CLASS, "newVideoStarted")

        hookVideoStarted("$EXTENSION_CLASS->videoInformationLoaded()V")

        NoVolumeCaptionsFeatureFlagFingerprint.matchAll().forEach {
            // 21.30+ inlines the flag lookup and must patch ~4 places.
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->disableMuteAutoCaptions(Z)Z"
            )
        }
    }
}
