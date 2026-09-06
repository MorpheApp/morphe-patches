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
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.fix.bitmap.fixRecycledBitmapPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_29_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_49_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_04_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_21_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_36_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.BackgroundPlaybackManagerShortsFingerprint
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import app.morphe.util.matchSingle
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/BackgroundPlaybackPatch;"

val backgroundPlaybackPatch = bytecodePatch(
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
        resourceMappingPatch
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
            fingerprint.matchSingle().method.apply {
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
        BackgroundPlaybackSettingsFingerprint.originalMethod.apply {
            val booleanCalls = instructions.filter {
                it.getReference<MethodReference>()?.returnType == "Z"
            }

            booleanCalls[1].getReference<MethodReference>()!!
                .getMutableMethod().addBackgroundPlaybackIsPatchEnabledHook()
        }

        // Prevents playback from resuming if it was interrupted from the notification
        // and the app was subsequently brought to the foreground.
        if (is_21_15_or_greater) {
            AutomaticForegroundPlaybackResumeFeatureFlagFingerprint.matchAll().forEach {
                it.apply {
                    method.insertLiteralOverride(
                        instructionMatches.first().index,
                        "$EXTENSION_CLASS->isAutomaticForegroundPlaybackAllowed(Z)Z"
                    )
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
            NewPlayerTypeEnumFeatureFlagFingerprint.matchAll().forEach {
                it.method.addBackgroundPlaybackFeatureFlagHook(it.instructionMatches.first().index, false)
            }
        }

        if (is_21_04_or_greater && !is_21_36_or_greater) {
            // If NewPlayerTypeEnumFeatureFlagFingerprint is present and forced off then this flag
            // must also be disabled, otherwise the player is a black screen with no buttons and no playback.
            NewPlayerOverlaysFeatureFlagFingerprint.matchAll().forEach {
                it.method.addBackgroundPlaybackFeatureFlagHook(it.instructionMatches.first().index, false)
            }
        }
    }
}
