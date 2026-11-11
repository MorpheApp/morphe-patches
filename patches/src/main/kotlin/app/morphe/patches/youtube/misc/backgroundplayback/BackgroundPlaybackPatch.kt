/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.backgroundplayback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.fix.bitmap.fixRecycledBitmapPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.disableNewPlayerControlsFeatureFlag
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_29_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_49_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_21_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_23_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_36_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.BackgroundPlaybackManagerShortsFingerprint
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/BackgroundPlaybackPatch;"

val backgroundPlaybackPatch = patch(
    name = "Remove background playback restrictions",
    description = "Removes restrictions on background playback, including playing kids videos in the background.",
) {
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
        videoInformationPatch,
        settingsPatch,
        versionCheckPatch,
        fixRecycledBitmapPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_shorts_disable_background_playback")
        )

        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_remove_background_playback_restrictions"),
            ListPreference("morphe_auto_pause_on_screen_lock")
        )

        onCreateHook(EXTENSION_CLASS, "initialize")

        arrayOf(
            BackgroundPlaybackManagerFingerprint to "isBackgroundPlaybackAllowed",
            BackgroundPlaybackManagerShortsFingerprint to "isBackgroundShortsPlaybackAllowed",
        ).forEach { (fingerprint, integrationsMethod) ->
            fingerprint.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->$integrationsMethod(Z)Z
                            move-result v$register 
                        """
                    )
                }
            }
        }

        fun MutableMethod.addBackgroundPlaybackIsPatchEnabledHook() {
            val returnInstruction = if (returnType == "Z") "return v0" else "return-void"

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->isPatchEnabled()Z
                    move-result v0
                    if-eqz v0, :disabled
                    $returnInstruction
                    :disabled
                    nop
                """
            )
        }

        fun MutableMethod.addBackgroundPlaybackFeatureFlagHook(index: Int, enable: Boolean) {
            val methodName = if (enable) "enableFeatureFlag" else "disableFeatureFlag"
            insertLiteralOverride(
                index,
                "$EXTENSION_CLASS->$methodName(Z)Z"
            )
        }


        fun Fingerprint.addBackgroundPlaybackFeatureFlagHook(enable: Boolean) {
            method.addBackgroundPlaybackFeatureFlagHook(
                this.instructionMatches.first().index,
                enable
            )
        }

        // Enable background playback option in YouTube settings
        val backgroundPlaybackAllowedMatch = if (is_21_23_or_greater) {
            BackgroundPlaybackSettingsFingerprint.instructionMatches.first()
        } else {
            BackgroundPlaybackSettingsLegacyFingerprint.instructionMatches[1]
        }
        backgroundPlaybackAllowedMatch.getMethodCalled().addBackgroundPlaybackIsPatchEnabledHook()

        // Prevents playback from resuming if it was interrupted from the notification
        // and the app was subsequently brought to the foreground. The same flag resumes the video
        // when a Short opened over it is closed, so it is only overridden where the main activity
        // goes to the background and returns to the foreground, and the class that resumes
        // the video keeps the value of the account
        // (https://github.com/MorpheApp/morphe-patches/issues/2506).
        if (is_21_15_or_greater) {
            val override = "$EXTENSION_CLASS->isAutomaticForegroundPlaybackAllowed(Z)Z"
            // 21.29 and older read the flag with a single accessor method.
            val flagAccessor = if (is_21_30_or_greater) {
                null
            } else {
                AutomaticForegroundPlaybackResumeFeatureFlagFingerprint.method
            }

            PlayingWhenBackgroundedFingerprint.matchAll().map { it.classDef.type }.toSet().forEach { type ->
                mutableClassDefBy(type).methods.forEach { method ->
                    if (flagAccessor == null) {
                        if (method.containsLiteralInstruction(AUTOMATIC_FOREGROUND_PLAYBACK_RESUME_FLAG)) {
                            method.insertLiteralOverride(AUTOMATIC_FOREGROUND_PLAYBACK_RESUME_FLAG, override)
                        }
                        return@forEach
                    }

                    method.findInstructionIndicesReversed {
                        MethodUtil.methodSignaturesMatch(
                            getReference<MethodReference>() ?: return@findInstructionIndicesReversed false,
                            flagAccessor
                        )
                    }.forEach { index ->
                        val register = method.getInstruction<OneRegisterInstruction>(index + 1).registerA
                        method.addInstructions(
                            index + 2,
                            """
                                invoke-static { v$register }, $override
                                move-result v$register
                            """
                        )
                    }
                }
            }
        }

        // Prevents playback from pausing when the overlay video settings is invoked.
        if (is_20_49_or_greater) {
            AutomaticPlaybackPausedInFlyoutFeatureFlagFingerprint.matchAll().forEach {
                it.apply {
                    method.insertLiteralOverride(
                        instructionMatches.first().index,
                        "$EXTENSION_CLASS->isAutomaticPlaybackPauseInFlyout(Z)Z"
                    )
                }
            }
        }

        // Force allowing background play for videos labeled for kids.
        KidsBackgroundPlaybackPolicyControllerFingerprint.method.addBackgroundPlaybackIsPatchEnabledHook()

        // Force allowing background play for Shorts.
        ShortsBackgroundPlaybackFeatureFlagFingerprint.matchAll().forEach {
            it.method.addBackgroundPlaybackFeatureFlagHook(it.instructionMatches.first().index, true)
        }

        // Fix PiP buttons not working after locking/unlocking device screen.
        if (!is_21_21_or_greater) {
            PipInputConsumerFeatureFlagFingerprint.addBackgroundPlaybackFeatureFlagHook(false)
        }

        if (is_20_29_or_greater && !is_21_36_or_greater) {
            // Client flag that interferes with background playback of some video types.
            // Exact purpose is not clear and it's used in ~ 100 locations.
            // Flag cannot be forced off with 21.36+ or the player seekbar is missing.
            //
            // Edit: This override may not be needed and only 45752335L override might be needed.
            NewPlayerTypeEnumFeatureFlagFingerprint.matchAll().forEach {
                it.method.addBackgroundPlaybackFeatureFlagHook(it.instructionMatches.first().index, false)
            }
        }

        // The playback media session publishes its state, and the playback notification is shown, once the state
        // has its artwork, or after a short wait without other changes, which a playing video can keep from ending.
        // Without a thumbnail, such as when it fails to load, the missing artwork is not a change of the state,
        // so the notification can be missing and the service is not kept in the foreground, which stops the audio
        // in the background after a while. The artwork is now always a change of the state, also when it is missing.
        if (is_20_51_or_greater) {
            MediaSessionArtworkStateFingerprint.let {
                it.method.apply {
                    val noChangeIndex = it.instructionMatches.first { match ->
                        match.instruction.opcode == Opcode.CONST_4
                    }.index
                    val register = getInstruction<OneRegisterInstruction>(noChangeIndex).registerA
                    val change = getInstruction<NarrowLiteralInstruction>(
                        it.instructionMatches.last().index
                    ).narrowLiteral
                    replaceInstruction(noChangeIndex, "const v$register, $change")
                }
            }
        }


        // If NewPlayerTypeEnumFeatureFlagFingerprint is overridden then must also
        // force off new player control flags otherwise player has no buttons visible.
        disableNewPlayerControlsFeatureFlag()
    }
}
