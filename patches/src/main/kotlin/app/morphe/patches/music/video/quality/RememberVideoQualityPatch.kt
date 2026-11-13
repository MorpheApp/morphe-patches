/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4757
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.video.quality

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterfaceMethod
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.video.information.VideoQualityFingerprint
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/RememberVideoQualityPatch;"
private const val EXTENSION_VIDEO_QUALITY_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RememberVideoQualityPatch$VideoQualityInterface;"
private const val EXTENSION_VIDEO_QUALITY_MENU_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RememberVideoQualityPatch$VideoQualityMenuInterface;"

@Suppress("unused")
val rememberVideoQualityPatch = patch(
    name = "Remember video quality",
    description = "Adds options to set default video qualities and remember the last selected video quality."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        musicVideoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            noTitleUnsortedPreferenceCategory(
                ListPreference(
                    key = "morphe_video_quality_default_wifi",
                    entriesKey = "morphe_video_quality_default_entries",
                    entryValuesKey = "morphe_video_quality_default_entry_values"
                ),
                ListPreference(
                    key = "morphe_video_quality_default_mobile",
                    entriesKey = "morphe_video_quality_default_entries",
                    entryValuesKey = "morphe_video_quality_default_entry_values"
                ),
                SwitchPreference("morphe_remember_video_quality_last_selected", summary = true),
                SwitchPreference("morphe_remember_video_quality_last_selected_toast", summary = true)
            )
        )

        val itemOnClickFingerprint = VideoQualityItemOnClickFingerprint
        val setQualitiesFingerprint = VideoQualityMenuSetQualitiesFingerprint

        // Add an interface to the quality, to get its resolution in the extension.
        val resolutionField = itemOnClickFingerprint.instructionMatches[0].fieldReference
        VideoQualityFingerprint.classDef.apply {
            addPatchInterfaceMethod(
                EXTENSION_VIDEO_QUALITY_INTERFACE,
                "patch_getResolution",
                """
                    iget v0, p0, $resolutionField
                    return v0
                """
            )
        }

        // Add an interface to the quality menu, to set a quality from the extension.
        // The quality is set by its resolution with the listener of the menu,
        // the same way the menu sets the quality selected by the user.
        val setQualityMethod = itemOnClickFingerprint.instructionMatches[1].methodReference
        setQualitiesFingerprint.classDef.apply {
            val listenerField = fields.single { field ->
                field.type == setQualityMethod.definingClass
            }

            addPatchInterfaceMethod(
                EXTENSION_VIDEO_QUALITY_MENU_INTERFACE,
                "patch_setQuality",
                """
                    iget-object v0, p0, $listenerField
                    if-eqz v0, :no_listener
                    invoke-interface { v0, p1 }, $setQualityMethod
                    :no_listener
                    return-void
                """
            )
        }

        // Set the default quality when the qualities of a new video are set in the quality menu.
        setQualitiesFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1, p0, p2 }, $EXTENSION_CLASS->setVideoQuality([${EXTENSION_VIDEO_QUALITY_INTERFACE}${EXTENSION_VIDEO_QUALITY_MENU_INTERFACE}I)I
                move-result p2
            """
        )

        // Remember the quality selected by the user.
        itemOnClickFingerprint.let {
            it.method.apply {
                val resolutionMatch = it.instructionMatches[0]
                val resolutionRegister = resolutionMatch.getInstruction<TwoRegisterInstruction>().registerA

                addInstruction(
                    resolutionMatch.index + 1,
                    "invoke-static/range { v$resolutionRegister .. v$resolutionRegister }, " +
                            "$EXTENSION_CLASS->userChangedQuality(I)V"
                )
            }
        }

        musicVideoIdHook("$EXTENSION_CLASS->newVideoStarted(Ljava/lang/String;)V")
    }
}
