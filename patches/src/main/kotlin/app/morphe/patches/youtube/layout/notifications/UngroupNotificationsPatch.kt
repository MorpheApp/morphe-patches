/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4896
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.notifications

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.videoid.hookBackgroundPlayVideoId
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/UngroupNotificationsPatch;"

@Suppress("unused")
val ungroupNotificationsPatch = patch(
    name = "Ungroup notifications",
    description = "Adds an option to show every video of the notifications that YouTube groups by " +
            "channel as its own notification.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoIdPatch,
        videoInformationPatch,
        playerTypeHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_ungroup_notifications", summary = true)
        )

        hookVideoId("$EXTENSION_CLASS->onVideoLoaded(Ljava/lang/String;)V")
        hookBackgroundPlayVideoId("$EXTENSION_CLASS->onVideoLoaded(Ljava/lang/String;)V")

        listOf(
            NetworkResponseBytesConstructorFingerprint to "[B",
            NetworkResponseBuffersConstructorFingerprint to "Ljava/lang/Iterable;",
        ).forEach { (fingerprint, bodyType) ->
            fingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p2 }, $EXTENSION_CLASS->ungroupNotifications($bodyType)$bodyType
                    move-result-object p2
                """
            )
        }
    }
}
