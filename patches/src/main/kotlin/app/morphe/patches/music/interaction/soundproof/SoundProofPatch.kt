/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.soundproof

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.MediaSessionSetMetadataFingerprint
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.screenInfoPreferenceCategory
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/soundproof/SoundProofPatch;"

private const val SEEK_BAR_PREFERENCE =
    "app.morphe.extension.shared.settings.preference.SeekBarPreference"

@Suppress("unused")
val soundProofPatch = bytecodePatch(
    name = "SoundProof",
    description = "Adds an option to skip tracks of artists reported as AI in the SoundProof community database.",
    // Off by default: it downloads a third-party database and skips tracks on its own.
    default = false
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_soundproof_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_soundproof_skip_ai_artists", summary = true),
                    SwitchPreference("morphe_music_soundproof_toast_on_skip", summary = true),
                    PreferenceCategory(
                        key = "morphe_music_soundproof_threshold",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_music_soundproof_minimum_ai_percent",
                                tag = SEEK_BAR_PREFERENCE,
                                selectable = true
                            ),
                            NonInteractivePreference(
                                key = "morphe_music_soundproof_minimum_votes",
                                tag = SEEK_BAR_PREFERENCE,
                                selectable = true
                            )
                        )
                    ),
                    screenInfoPreferenceCategory(
                        key = "morphe_music_soundproof_about_category",
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_music_soundproof_about",
                                titleKey = null,
                                summaryKey = "morphe_music_soundproof_about_summary"
                            )
                        )
                    )
                )
            )
        )

        // Both the session and the metadata are needed: the artist is read from the metadata,
        // and the skip goes through the session's own transport controls.
        MediaSessionSetMetadataFingerprint.apply {
            // Other patches insert before the same call, which shifts its index.
            clearMatch()

            method.apply {
                val setMetadataIndex = instructionMatches.last().index
                val setMetadataCall = getInstruction<FiveRegisterInstruction>(setMetadataIndex)
                addInstruction(
                    setMetadataIndex,
                    "invoke-static { v${setMetadataCall.registerC}, v${setMetadataCall.registerD} }, " +
                        "$EXTENSION_CLASS->onSetMetadata(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)V"
                )
            }
        }
    }
}
