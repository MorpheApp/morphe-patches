/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3413
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.audio.silence

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.matchSingle
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/SkipSilencePatch;"

@Suppress("unused")
internal fun skipSilencePatch(
    block: BytecodePatchBuilder.() -> Unit,
    preferenceScreen: BasePreferenceScreen.Screen,
) = bytecodePatch(
    name = "Skip silence",
    description = "Adds an option to automatically skip silent pauses in audio playback."
) {
    block()

    execute {
        preferenceScreen.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_skip_silence", summary = true),
                ListPreference("morphe_skip_silence_minimum_pause")
            )
        )

        ApplySkipSilenceFingerprint.apply {
            val match = instructionMatches[1]
            val booleanRegister = match.getInstruction<TwoRegisterInstruction>().registerA

            method.addInstructions(
                match.index + 1,
                """
                    invoke-static { v$booleanRegister }, $EXTENSION_CLASS->isSkipSilenceEnabled(Z)Z
                    move-result v$booleanRegister
                """
            )
        }

        SetSkipSilenceEnabledFingerprint.matchSingle().method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->isSkipSilenceEnabled(Z)Z
                move-result p1
            """
        )

        SilenceSkippingProcessorConstructorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1, p2 }, $EXTENSION_CLASS->minimumSilenceDurationUs(J)J
                move-result-wide p1
                invoke-static { p3 }, $EXTENSION_CLASS->silenceRetentionRatio(F)F
                move-result p3
                invoke-static { p7 }, $EXTENSION_CLASS->silenceThresholdLevel(S)S
                move-result p7
            """
        )
    }
}
