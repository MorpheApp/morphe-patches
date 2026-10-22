/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3332
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.medianotification

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.shared.misc.medianotification.EXTENSION_CLASS
import app.morphe.patches.shared.misc.medianotification.mediaNotificationControlsPatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

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
    additionalPreferences = setOf(
        SwitchPreference("morphe_seek_notification_media_prev_next", summary = true),
        TextPreference("morphe_seek_notification_media_rewind_length", inputType = InputType.NUMBER),
        TextPreference("morphe_seek_notification_media_fast_forward_length", inputType = InputType.NUMBER),
    ),
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
                    :add_action
                    nop
                """
            )
        }

        // Previous and Next can rewind and fast forward instead,
        // using the same seek as the media session rewind and fast forward actions.
        seekInsteadOfSkip(MediaSessionSkipToNextFingerprint, MediaSessionFastForwardFingerprint)
        seekInsteadOfSkip(MediaSessionSkipToPreviousFingerprint, MediaSessionRewindFingerprint)
    }
)

private fun BytecodePatchContext.seekInsteadOfSkip(
    skipFingerprint: Fingerprint,
    seekFingerprint: Fingerprint,
) {
    val skipMethod = skipFingerprint.method
    val seekMethod = seekFingerprint.match(skipFingerprint.classDef).originalMethod

    // The player callback seeks a fixed amount. Use the seek length setting instead.
    MediaSessionSeekRelativeFingerprint(
        skipFingerprint.classDef.superclass!!,
        seekMethod.name
    ).method.apply {
        val index = indexOfFirstInstructionOrThrow { this is WideLiteralInstruction }
        val register = getInstruction<OneRegisterInstruction>(index).registerA

        addInstructions(
            index + 1,
            """
                invoke-static { v$register, v${register + 1} }, $EXTENSION_CLASS->getSeekLength(J)J
                move-result-wide v$register
            """
        )
    }

    skipMethod.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $EXTENSION_CLASS->seekInsteadOfSkip()Z
            move-result v0
            if-eqz v0, :skip
            invoke-virtual { p0 }, $seekMethod
            return-void
            :skip
            nop
        """
    )
}
