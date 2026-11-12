/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.video.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.string
import app.morphe.patches.music.interaction.crossfade.AudioEffectSessionFingerprint
import app.morphe.patches.music.layout.lyrics.TabbedViewTabSelectedFingerprint
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.speed.playbackSpeedPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/AudioFxProfilesPatch;"

private const val BAR_VIEW_CLASS =
    "app.morphe.extension.music.patches.AudioFxProfilesPatch\$AudioFxBar"

private val audioFxLayoutResourcePatch = patch {
    execute {
        resourceXml("res/layout/player_bottom_sheet.xml").use { layout ->
            val tabs = layout.elementByAttribute("android:id", "@id/bottom_sheet_tabbed_view")
            tabs.parent!!.insertChild(tabs.index, BAR_VIEW_CLASS) {
                setAttribute("android:layout_width", "match_parent")
                setAttribute("android:layout_height", "wrap_content")
            }
        }
    }
}

@Suppress("unused")
val audioFxProfilesPatch = patch(
    name = "Audio FX profiles",
    description = "Adds real-time DSP audio profiles (Nightcore, Slowed + Reverb, Muffled, Lo-Fi, Daycore, Vaporwave, Live Concert, 8D Audio, Bass Boosted) next to Lyrics in the full-screen player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playbackSpeedPatch,
        audioFxLayoutResourcePatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference(
                key = "morphe_music_audio_fx_enabled",
                summary = true,
            )
        )

        // Hook player TabbedView so the FX button appears next to Lyrics in the tab strip.
        TabbedViewTabSelectedFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onTabSelected(Landroid/view/ViewGroup;)V"
        )

        // Hook all audio effect session methods (open/update session ID) in YouTube Music's
        // AudioEffectSession manager class.
        AudioEffectSessionFingerprint.classDef.methods.filter { method ->
            method.returnType == "V" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first().toString() == "I"
        }.forEach { method ->
            method.addInstruction(
                0,
                "invoke-static { p1 }, $EXTENSION_CLASS->onAudioSessionId(I)V"
            )
        }

        // Also fallback-hook AudioTrack.getAudioSessionId() inside AudioTrackAudioOutput if present.
        runCatching {
            Fingerprint(
                classFingerprint = Fingerprint(
                    filters = listOf(string("AudioTrackAudioOutput"))
                ),
                filters = listOf(
                    methodCall(smali = "Landroid/media/AudioTrack;->getAudioSessionId()I")
                )
            ).let { fp ->
                val matchIndex = fp.instructionMatches.first().index
                val moveResultInst = fp.method.implementation!!.instructions.elementAt(matchIndex + 1)
                if (moveResultInst is com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction) {
                    val reg = moveResultInst.registerA
                    fp.method.addInstruction(
                        matchIndex + 2,
                        "invoke-static { v$reg }, $EXTENSION_CLASS->onAudioSessionId(I)V"
                    )
                }
            }
        }
    }
}
