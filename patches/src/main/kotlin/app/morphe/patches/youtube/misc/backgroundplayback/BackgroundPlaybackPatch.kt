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
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
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
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.util.MethodUtil

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
            // When the background playback setting of YouTube does not allow background playback,
            // the app pauses the video when it goes to the background, and the flag keeps the value
            // of the account so the video resumes when the app returns to the foreground
            // (https://github.com/MorpheApp/morphe-patches/issues/4639).
            BackgroundPlaybackSettingAllowedFingerprint.let { fingerprint ->
                val settingAllowedMethod = fingerprint.method
                val audioManagerType = settingAllowedMethod.parameterTypes[1]

                fingerprint.classDef.apply {
                    // The instance method that reads the setting of the account and calls the static method.
                    val settingMethod = methods.single { method ->
                        method.returnType == "Z" && method.parameters.isEmpty() &&
                                method.indexOfFirstInstruction {
                                    MethodUtil.methodSignaturesMatch(
                                        getReference<MethodReference>() ?: return@indexOfFirstInstruction false,
                                        settingAllowedMethod
                                    )
                                } >= 0
                    }

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
                                    invoke-virtual { p0 }, $settingMethod
                                    move-result v0
                                    return v0
                                """
                            )
                        }
                    )

                    // The field the method reads the audio manager from.
                    val audioManagerField = settingMethod.let { method ->
                        val castIndex = method.indexOfFirstInstructionOrThrow {
                            opcode == Opcode.CHECK_CAST && getReference<TypeReference>()?.type == audioManagerType
                        }
                        val castRegister = method.getInstruction<OneRegisterInstruction>(castIndex).registerA
                        val fieldIndex = method.indexOfFirstInstructionReversedOrThrow(castIndex) {
                            opcode == Opcode.IGET_OBJECT && (this as TwoRegisterInstruction).registerA == castRegister
                        }
                        method.getInstruction<ReferenceInstruction>(fieldIndex).reference as FieldReference
                    }

                    // The class is merged with other classes, so more than one constructor can take
                    // the audio manager. Use the one that saves it in the field the method reads.
                    methods.single { method ->
                        if (!MethodUtil.isConstructor(method)) return@single false
                        val parameterIndex = method.parameterTypes.indexOf(audioManagerType)
                        if (parameterIndex < 0) return@single false

                        fun wordCount(types: List<CharSequence>) = types.sumOf { type ->
                            if (type == "J" || type == "D") 2 else 1
                        }
                        // The parameters are the last registers of the method.
                        val parameterRegister = method.implementation!!.registerCount -
                                wordCount(method.parameterTypes) +
                                wordCount(method.parameterTypes.take(parameterIndex))

                        method.indexOfFirstInstruction {
                            if (opcode != Opcode.IPUT_OBJECT ||
                                (this as TwoRegisterInstruction).registerA != parameterRegister
                            ) return@indexOfFirstInstruction false
                            val field = getReference<FieldReference>()
                                ?: return@indexOfFirstInstruction false
                            field.definingClass == audioManagerField.definingClass &&
                                    field.name == audioManagerField.name
                        } >= 0
                    }.apply {
                        // Right after the super constructor call, before 'this' register can be reused.
                        val superIndex = indexOfFirstInstructionOrThrow {
                            opcode == Opcode.INVOKE_DIRECT && getReference<MethodReference>()?.name == "<init>"
                        }
                        val thisRegister = getInstruction<FiveRegisterInstruction>(superIndex).registerC
                        addInstruction(
                            superIndex + 1,
                            "invoke-static { v$thisRegister }, $EXTENSION_CLASS->" +
                                    "setBackgroundPlaybackSetting($EXTENSION_BACKGROUND_PLAYBACK_SETTING_INTERFACE)V"
                        )
                    }
                }
            }

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
