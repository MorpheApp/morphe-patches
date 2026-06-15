package app.morphe.patches.music.misc.sponsorblock

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.information.musicVideoTimeHook
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/sponsorblock/SegmentPlaybackController;"

@Suppress("unused")
val musicSponsorBlockPatch = bytecodePatch(
    name = "SponsorBlock for Music",
    description = "Adds options to enable SponsorBlock in YouTube Music, automatically skipping sponsored segments.",
) {
    dependsOn(
        sharedExtensionPatch,
        musicVideoInformationPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        // ── Core hooks ────────────────────────────────────────────────────────

        // Called ~every 1000ms — drives the skip-segment logic.
        musicVideoTimeHook(EXTENSION_CLASS, "setVideoTime")

        // Called when a new track loads — triggers SponsorBlock API fetch.
        musicVideoIdHook("$EXTENSION_CLASS->setVideoId(Ljava/lang/String;)V")

        // ── Compact-player seekbar segment drawing ────────────────────────────
        //
        // MusicPlaybackControlsTimeBar is the standard compact/mini player seekbar.
        // We hook onMeasure to get the bounding Rect and draw() to paint segments.

        // The seekbar stores its pixel bounds in a Rect field, assigned in onMeasure.
        val rectField = MusicTimeBarOnMeasureFingerprint.method.run {
            val rectIndex = indexOfFirstInstructionReversedOrThrow(
                implementation!!.instructions.size - 1
            ) {
                opcode == Opcode.IGET_OBJECT &&
                        getReference<FieldReference>()?.type == "Landroid/graphics/Rect;"
            }
            getInstruction<ReferenceInstruction>(rectIndex).reference as FieldReference
        }

        MusicTimeBarDrawFingerprint.method.apply {
            // Inject right after super.draw() (index 1). The previous anchor (before drawCircle)
            // only executed while the scrubber thumb was drawn, so the overlay never appeared
            // during normal playback. Here it runs every frame. p1 is the Canvas.
            val freeRegister = findFreeRegister(1)
            addInstructions(
                1,
                """
                    iget-object v$freeRegister, p0, $rectField
                    invoke-static {v$freeRegister}, $EXTENSION_CLASS->setSponsorBarRect(Landroid/graphics/Rect;)V
                    invoke-static {p1}, $EXTENSION_CLASS->drawSponsorTimeBars(Landroid/graphics/Canvas;)V
                """,
            )
        }

        // ── Settings ──────────────────────────────────────────────────────────
        // Each category gets an on/off switch immediately followed by its seekbar color picker.
        fun colorPreference(key: String) = TextPreference(
            key = key,
            tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
            inputType = InputType.TEXT_CAP_CHARACTERS,
        )

        // Dedicated top-level "SponsorBlock" section (own screen + icon), RVX-style.
        PreferenceScreen.SPONSORBLOCK.addPreferences(
            SwitchPreference("morphe_music_sb_enabled"),
            SwitchPreference("morphe_music_sb_toast_on_skip"),
            SwitchPreference("morphe_music_sb_toast_on_connection_error"),

            SwitchPreference("morphe_music_sb_segments_sponsor"),
            colorPreference("morphe_music_sb_sponsor_color"),
            SwitchPreference("morphe_music_sb_segments_selfpromo"),
            colorPreference("morphe_music_sb_selfpromo_color"),
            SwitchPreference("morphe_music_sb_segments_interaction"),
            colorPreference("morphe_music_sb_interaction_color"),
            SwitchPreference("morphe_music_sb_segments_intro"),
            colorPreference("morphe_music_sb_intro_color"),
            SwitchPreference("morphe_music_sb_segments_outro"),
            colorPreference("morphe_music_sb_outro_color"),
            SwitchPreference("morphe_music_sb_segments_preview"),
            colorPreference("morphe_music_sb_preview_color"),
            SwitchPreference("morphe_music_sb_segments_hook"),
            colorPreference("morphe_music_sb_hook_color"),
            SwitchPreference("morphe_music_sb_segments_filler"),
            colorPreference("morphe_music_sb_filler_color"),
            SwitchPreference("morphe_music_sb_segments_nomusic"),
            colorPreference("morphe_music_sb_music_offtopic_color"),
        )
    }
}
