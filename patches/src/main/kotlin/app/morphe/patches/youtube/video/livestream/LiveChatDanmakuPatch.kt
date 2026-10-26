/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.livestream

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/livestream/LiveChatDanmakuPatch;"

/**
 * Player overlay container, following the same pattern as the swipe-controls
 * overlay fingerprint: a public final method returning ViewGroup LayoutParams,
 * creating a new instance, with a String field holding the overlay name.
 *
 * The danmaku overlay is attached through this hook. If a target YouTube
 * version renames or restructures this container, this fingerprint is the
 * first place to re-verify (see DANMAKU_README.md).
 */
private object LiveChatDanmakuPlayerOverlayFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/ViewGroup\$LayoutParams;",
    parameters = listOf(),
    filters = listOf(
        opcode(Opcode.NEW_INSTANCE),
        literal(-1),
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this"
        ),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("I", "I", "Z")
        )
    ),
    custom = { _, classDef ->
        classDef.fields.any { field -> field.type == "Ljava/lang/String;" }
    }
)

@Suppress("unused")
val liveChatDanmakuPatch = bytecodePatch(
    name = "Live chat danmaku",
    description = "Adds an option to show live stream comments as Niconico-style " +
        "danmaku scrolling across the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            // Keep the preferences organized together.
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_live_chat_danmaku", summary = true),
                SwitchPreference("morphe_live_chat_danmaku_show_author", summary = true),
                SwitchPreference("morphe_live_chat_danmaku_superchat_highlight", summary = true),
                SwitchPreference("morphe_live_chat_danmaku_fullscreen_only", summary = true),
            ),
            NonInteractivePreference(
                key = "morphe_live_chat_danmaku_crossing_seconds",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            NonInteractivePreference(
                key = "morphe_live_chat_danmaku_opacity",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            NonInteractivePreference(
                key = "morphe_live_chat_danmaku_columns",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            NonInteractivePreference(
                key = "morphe_live_chat_danmaku_max_rows",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
        )

        // Hook called when a new video starts playing (player controller created).
        onCreateHook(EXTENSION_CLASS, "newVideoStarted")

        // Hook called approximately once per second with the current playback time.
        // Used to detect that a live stream is still playing and to stop the
        // danmaku feed when playback ends.
        videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")

        // Hook the player overlay container so the danmaku layer can be
        // attached on top of the video. p0 is the overlay container view and
        // the String field is its overlay name.
        LiveChatDanmakuPlayerOverlayFingerprint.let {
            val overlayNameField = it.classDef.fields.first { field ->
                field.type == "Ljava/lang/String;"
            }

            it.method.addInstructions(
                0,
                """
                    iget-object v0, p0, $overlayNameField
                    invoke-static { p0, v0 }, $EXTENSION_CLASS->setPlayerOverlay(Landroid/view/View;Ljava/lang/String;)V
                """
            )
        }
    }
}
