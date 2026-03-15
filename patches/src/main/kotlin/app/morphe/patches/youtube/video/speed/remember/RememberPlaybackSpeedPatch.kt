/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.speed.remember

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.setPlaybackSpeedClassFieldReferenceRef
import app.morphe.patches.youtube.video.information.setPlaybackSpeedContainerClassFieldReferenceRef
import app.morphe.patches.youtube.video.information.setPlaybackSpeedContainerClassFieldReferenceClassTypeRef
import app.morphe.patches.youtube.video.information.setPlaybackSpeedMethodReferenceRef
import app.morphe.patches.youtube.video.information.userSelectedPlaybackSpeedHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.speed.custom.customPlaybackSpeedPatch
import app.morphe.patches.youtube.video.speed.settingsMenuVideoSpeedGroup
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/playback/speed/RememberPlaybackSpeedPatch;"

internal val rememberPlaybackSpeedPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
        customPlaybackSpeedPatch
    )

    execute {
        settingsMenuVideoSpeedGroup.addAll(
            listOf(
                ListPreference(
                    key = "morphe_playback_speed_default",
                    // Entries and values are set by the extension code based on the actual speeds available.
                    entriesKey = null,
                    entryValuesKey = null,
                    tag = "app.morphe.extension.youtube.settings.preference.CustomVideoSpeedListPreference"
                ),
                SwitchPreference("morphe_remember_playback_speed_last_selected"),
                SwitchPreference("morphe_remember_playback_speed_last_selected_toast")
            )
        )

        onCreateHook(EXTENSION_CLASS_DESCRIPTOR, "newVideoStarted")

        userSelectedPlaybackSpeedHook(
            EXTENSION_CLASS_DESCRIPTOR,
            "userSelectedPlaybackSpeed",
        )

        /*
         * Hook the code that is called when the playback speeds are initialized, and sets the playback speed
         */
        InitializePlaybackSpeedValuesFingerprint.method.apply {
            // Infer everything necessary for calling the method setPlaybackSpeed().
            val onItemClickListenerClassFieldReference = getInstruction<ReferenceInstruction>(0).reference

            // Registers are not used at index 0, so they can be freely used.
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->getPlaybackSpeedOverride()F
                    move-result v0
                    
                    # Check if the playback speed is not auto (-2.0f)
                    const/4 v1, 0x0
                    cmpg-float v1, v0, v1
                    if-lez v1, :do_not_override
    
                    # Get the instance of the class which has the container class field below.
                    iget-object v1, p0, $onItemClickListenerClassFieldReference

                    # Get the container class field.
                    iget-object v1, v1, ${setPlaybackSpeedContainerClassFieldReferenceRef.get()}
                    
                    # Required cast for 20.49+
                    check-cast v1, ${setPlaybackSpeedContainerClassFieldReferenceClassTypeRef.get()}
                    
                    # Get the field from its class.
                    iget-object v2, v1, ${setPlaybackSpeedClassFieldReferenceRef.get()}
                    
                    # Invoke setPlaybackSpeed on that class.
                    invoke-virtual {v2, v0}, ${setPlaybackSpeedMethodReferenceRef.get()}
                """,
                ExternalLabel("do_not_override", getInstruction(0)),
            )
        }
    }
}
