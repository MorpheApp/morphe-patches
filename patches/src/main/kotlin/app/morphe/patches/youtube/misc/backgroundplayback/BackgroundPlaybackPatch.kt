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
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
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
import app.morphe.patches.youtube.misc.playservice.is_21_46_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.BackgroundPlaybackManagerShortsFingerprint
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/BackgroundPlaybackPatch;"

private const val EXTENSION_BACKGROUND_PLAYBACK_SETTING_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/BackgroundPlaybackPatch$BackgroundPlaybackSetting;"

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

        fun MutableMethod.addIsPatchEnabledHook() {
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

        fun Fingerprint.addFeatureFlagOverride(extensionMethodName: String) {
            matchAll().forEach { match ->
                match.method.insertLiteralOverride(
                    match.instructionMatches.first().index,
                    "$EXTENSION_CLASS->$extensionMethodName(Z)Z"
                )
            }
        }

        arrayOf(
            BackgroundPlaybackManagerFingerprint to "isBackgroundPlaybackAllowed",
            BackgroundPlaybackManagerShortsFingerprint to "isBackgroundShortsPlaybackAllowed",
        ).forEach { (fingerprint, extensionMethodName) ->
            fingerprint.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->$extensionMethodName(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        // Enable background playback option in YouTube settings.
        val backgroundPlaybackAllowedMatch = if (is_21_23_or_greater) {
            BackgroundPlaybackSettingsFingerprint.instructionMatches.first()
        } else {
            BackgroundPlaybackSettingsLegacyFingerprint.instructionMatches[1]
        }
        backgroundPlaybackAllowedMatch.getMethodCalled().addIsPatchEnabledHook()

        // Prevents playback from resuming if it was interrupted from the notification
        // and the app was subsequently brought to the foreground.
        if (is_21_15_or_greater) {
            // The same flag resumes the video when a Short opened over it is closed, so it is only
            // overridden where the main activity goes to the background and returns to the foreground
            // (https://github.com/MorpheApp/morphe-patches/issues/2506).
            //
            // If the background playback setting of YouTube does not allow background playback,
            // the app pauses the video when it goes to the background, and the flag keeps the value
            // of the account so the video resumes when the app returns to the foreground
            // (https://github.com/MorpheApp/morphe-patches/issues/4639).
            // The extension reads the setting with a method added to the setting class.
            backgroundPlaybackSettingConstructorFingerprint(
                settingClassType = BackgroundPlaybackSettingFingerprint.originalClassDef.type,
                settingStoreType = BackgroundPlaybackSettingFingerprint.instructionMatches.first()
                    .methodReference.definingClass,
                audioManagerType = BackgroundPlaybackSettingAllowedFingerprint.originalMethod
                    .parameterTypes[1].toString()
            ).let {
                // After the super constructor call, before the register of 'this' can be reused.
                it.method.addInstruction(
                    it.instructionMatches.first().index + 1,
                    "invoke-static { p0 }, $EXTENSION_CLASS->" +
                            "setBackgroundPlaybackSetting($EXTENSION_BACKGROUND_PLAYBACK_SETTING_INTERFACE)V"
                )
            }

            BackgroundPlaybackSettingFingerprint.classDef.apply {
                interfaces.add(EXTENSION_BACKGROUND_PLAYBACK_SETTING_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_isBackgroundPlaybackAllowed",
                        listOf(),
                        "Z",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                invoke-virtual { p0 }, ${BackgroundPlaybackSettingFingerprint.method}
                                move-result v0
                                return v0
                            """
                        )
                    }
                )
            }

            val override = "$EXTENSION_CLASS->isAutomaticForegroundPlaybackAllowed(Z)Z"
            val backgroundedClasses = PlayingWhenBackgroundedFingerprint.matchAll()
                .map { it.classDef }
                .distinctBy { it.type }

            if (is_21_30_or_greater) {
                backgroundedClasses.forEach { classDef ->
                    AutomaticForegroundPlaybackResumeFeatureFlagFingerprint.matchAll(classDef).forEach { match ->
                        match.method.insertLiteralOverride(match.instructionMatches.first().index, override)
                    }
                }
            } else {
                // 21.29 and older read the flag with a single accessor method.
                backgroundedClasses.forEach { classDef ->
                    automaticForegroundPlaybackResumeFlagAccessorCallFingerprint(classDef.type)
                        .matchAllMethodIndicesForEach { index ->
                            val register = getInstruction<OneRegisterInstruction>(index + 1).registerA

                            addInstructions(
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
            AutomaticPlaybackPausedInFlyoutFeatureFlagFingerprint.addFeatureFlagOverride(
                "isAutomaticPlaybackPauseInFlyout"
            )
        }

        // Force allowing background play for videos labeled for kids.
        if (is_21_46_or_greater) {
            KidsBackgroundPlaybackPolicyControllerFingerprint
        } else {
            KidsBackgroundPlaybackPolicyControllerLegacyFingerprint
        }.method.addIsPatchEnabledHook()

        // Force allowing background play for Shorts.
        ShortsBackgroundPlaybackFeatureFlagFingerprint.addFeatureFlagOverride("enableFeatureFlag")

        // Fix PiP buttons not working after locking/unlocking device screen.
        if (!is_21_21_or_greater) {
            PipInputConsumerFeatureFlagFingerprint.addFeatureFlagOverride("disableFeatureFlag")
        }

        if (is_20_29_or_greater && !is_21_36_or_greater) {
            // Client flag that interferes with background playback of some video types.
            // Exact purpose is not clear and it's used in ~ 100 locations.
            // Flag cannot be forced off with 21.36+ or the player seekbar is missing.
            //
            // Edit: This override may not be needed and only 45752335L override might be needed.
            NewPlayerTypeEnumFeatureFlagFingerprint.addFeatureFlagOverride("disableFeatureFlag")
        }

        // The playback media session publishes its state, and the playback notification is shown, once the state
        // has its artwork, or after a short wait without other changes, which a playing video can keep from ending.
        // Without a thumbnail, such as when it fails to load, the missing artwork is not a change of the state,
        // so the notification can be missing and the service is not kept in the foreground, which stops the audio
        // in the background after a while. The artwork is now always a change of the state, also when it is missing.
        if (is_20_51_or_greater) {
            MediaSessionArtworkStateFingerprint.let {
                it.method.apply {
                    val noChangeIndex = indexOfFirstInstructionOrThrow(Opcode.CONST_4)
                    val register = getInstruction<OneRegisterInstruction>(noChangeIndex).registerA
                    val change = it.instructionMatches.last().getInstruction<NarrowLiteralInstruction>().narrowLiteral

                    replaceInstruction(noChangeIndex, "const v$register, $change")
                }
            }
        }

        // If NewPlayerTypeEnumFeatureFlagFingerprint is overridden then must also
        // force off new player control flags otherwise player has no buttons visible.
        disableNewPlayerControlsFeatureFlag()
    }
}
