/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_26_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/AutoCaptionsPatch;"

internal val autoCaptionsPatch = bytecodePatch(
    description = "Adds an option to disable captions from being automatically enabled.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        videoInformationPatch
    )

    execute {
        settingsMenuCaptionGroup.add(
            if (is_20_26_or_greater) {
                ListPreference("morphe_auto_captions_style")
            } else {
                ListPreference(
                    key = "morphe_auto_captions_style",
                    entriesKey = "morphe_auto_captions_style_legacy_entries",
                    entryValuesKey = "morphe_auto_captions_style_legacy_entry_values"
                )
            }
        )

        SubtitleManagerFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->disableAutoCaptions(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        onCreateHook(EXTENSION_CLASS_DESCRIPTOR, "newVideoStarted")

        StartVideoInformerFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->videoInformationLoaded()V"
        )

        if (is_20_26_or_greater) {
            NoVolumeCaptionsFeatureFlagFingerprint.method.apply {
                addInstructions(
                    0,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->disableMuteAutoCaptions()Z
                        move-result v0
                        return v0
                        nop
                    """
                )
            }
        }
    }
}
