/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3332
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.medianotification

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.medianotification.EXTENSION_CLASS
import app.morphe.patches.shared.misc.medianotification.mediaNotificationControlsPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val mediaNotificationControlsPatchYouTube = mediaNotificationControlsPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE)
    },
    preferenceScreen = PreferenceScreen.PLAYER,
    executeBlock = {
        // Android 12 and lower show the notification actions as the media buttons.
        // Don't add the previous/next actions, otherwise the buttons remain visible but do nothing.
        PlaybackNotificationAddActionFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p3 }, $EXTENSION_CLASS->hideNotificationAction(I)Z
                    move-result v0
                    if-eqz v0, :add_action
                    return-void
                """,
                ExternalLabel("add_action", getInstruction(0))
            )
        }
    }
)
