/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.speed.remember

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_11_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.InitializePlaybackSpeedValuesFingerprint
import app.morphe.patches.youtube.video.information.EXTENSION_PLAYBACK_SPEED_MENU_INTERFACE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.userSelectedPlaybackSpeedHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.speed.custom.customPlaybackSpeedPatch
import app.morphe.patches.youtube.video.speed.custom.EXTENSION_CLASS as CUSTOM_SPEED_EXTENSION_CLASS
import app.morphe.patches.youtube.video.speed.settingsMenuShortsSpeedGroup
import app.morphe.patches.youtube.video.speed.settingsMenuVideoSpeedGroup
import app.morphe.patches.youtube.video.videoid.hookPlayerResponseVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/speed/RememberPlaybackSpeedPatch;"

/**
 * Plays a Short faster while the screen is held, at 2.0x, unless it already plays at 2.0x,
 * and shows "2x" above the player. Logs the gesture with its own id. 21.11+
 */
internal object SpeedmasterStartFingerprint : Fingerprint(
    filters = listOf(
        literal(307700L),
        literal(2.0f),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("F"),
            returnType = "V"
        )
    )
)

internal val rememberPlaybackSpeedPatch = patch {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoIdPatch,
        videoInformationPatch,
        customPlaybackSpeedPatch,
        versionCheckPatch
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
                ListPreference(
                    key = "morphe_playback_audio_pitch_default",
                    // List is shared with video speeds.
                    entriesKey = null,
                    entryValuesKey = null,
                    tag = "app.morphe.extension.youtube.settings.preference.CustomVideoSpeedListPreference"
                ),
                SwitchPreference("morphe_remember_playback_speed_last_selected", summary = true),
                SwitchPreference("morphe_remember_playback_speed_last_selected_toast", summary = true),
                SwitchPreference("morphe_disable_playback_speed_music", summary = true)
            )
        )

        // The Shorts speed belongs with the other Shorts settings.
        settingsMenuShortsSpeedGroup.addAll(
            listOf(
                ListPreference(
                    key = "morphe_shorts_playback_speed_default",
                    // Entries and values are set by the extension code based on the actual speeds available.
                    entriesKey = null,
                    entryValuesKey = null,
                    tag = "app.morphe.extension.youtube.settings.preference.CustomVideoSpeedListPreference"
                ),
                SwitchPreference("morphe_shorts_remember_playback_speed", summary = true)
            )
        )

        onCreateHook(EXTENSION_CLASS, "newVideoStarted")

        userSelectedPlaybackSpeedHook(
            EXTENSION_CLASS,
            "userSelectedPlaybackSpeed",
        )

        hookPlayerResponseVideoId("$EXTENSION_CLASS->preloadMusicVideoFetch(Ljava/lang/String;Z)V")

        // The faster speed of a held Short is not replaced with the Shorts speed.
        // A held Short plays at the tap and hold speed, the same as the regular player, instead of 2.0x.
        if (is_21_11_or_greater) {
            SpeedmasterStartFingerprint.let {
                it.method.apply {
                    // The text above the player shows the speed.
                    val textIndex = indexOfFirstLiteralInstruction(
                        resourceId(ResourceType.STRING, "reel_speedmaster_indicator_text")
                    )
                    if (textIndex >= 0) {
                        val textResultIndex = indexOfFirstInstructionOrThrow(textIndex, Opcode.MOVE_RESULT_OBJECT)
                        val textRegister = getInstruction<OneRegisterInstruction>(textResultIndex).registerA

                        addInstructions(
                            textResultIndex + 1,
                            """
                                invoke-static { v$textRegister }, $CUSTOM_SPEED_EXTENSION_CLASS->getShortsTapAndHoldText(Ljava/lang/String;)Ljava/lang/String;
                                move-result-object v$textRegister
                            """
                        )
                    }

                    addInstructionsAtControlFlowLabel(
                        it.instructionMatches.last().index,
                        "invoke-static { }, $EXTENSION_CLASS->onSpeedmasterStarted()V"
                    )

                    val speedIndex = it.instructionMatches[1].index
                    val speedRegister = getInstruction<OneRegisterInstruction>(speedIndex).registerA

                    addInstructions(
                        speedIndex + 1,
                        """
                            invoke-static { }, $CUSTOM_SPEED_EXTENSION_CLASS->getTapAndHoldSpeed()F
                            move-result v$speedRegister
                        """
                    )
                }
            }
        }

        /*
         * Hook the code that is called when the playback speeds are initialized, and sets the playback speed
         */
        InitializePlaybackSpeedValuesFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setDefaultPlaybackSpeed($EXTENSION_PLAYBACK_SPEED_MENU_INTERFACE)V"
        )
    }
}
