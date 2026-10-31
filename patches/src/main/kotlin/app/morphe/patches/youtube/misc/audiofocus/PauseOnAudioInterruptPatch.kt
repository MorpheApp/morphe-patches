/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/1320
 *
 * Adapted from ReVanced's Pause on audio interrupt patch (PR #6464).
 * https://github.com/PatrickMitchellUI/YT-Revanced-patches/blob/946f1c2498ce118f9a092c2a7c6af377d165f698/patches/src/main/kotlin/app/revanced/patches/youtube/misc/audiofocus/PauseOnAudioInterruptPatch.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.audiofocus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PauseOnAudioInterruptPatch;"

@Suppress("unused")
val pauseOnAudioInterruptPatch = bytecodePatch(
    name = "Pause on audio interrupt",
    description = "Adds an option to pause playback instead of lowering volume when other audio plays.",
) {
    dependsOn(sharedExtensionPatch, settingsPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            SwitchPreference("morphe_pause_on_audio_interrupt", summary = true)
        )

        AudioFocusRequestBuilderFingerprint.method.apply {
            // Builder.build() reads its sole boolean (willPauseWhenDucked) for the
            // request constructor. Override the value, without depending on an
            // obfuscated field name or modifying the reusable builder itself.
            // Fail rather than silently patch a different flag if this shape changes.
            val pauseFlagReads = implementation!!.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.IGET_BOOLEAN &&
                    instruction.getReference<FieldReference>()?.definingClass == definingClass
            }
            check(pauseFlagReads.size == 1) {
                "Expected exactly one pause-when-ducked field read in AudioFocusRequestCompat.Builder.build()"
            }
            val (index, instruction) = pauseFlagReads.single()
            val register = (instruction as TwoRegisterInstruction).registerA

            addInstructions(
                index + 1,
                """
                    invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->overridePauseWhenDucked(Z)Z
                    move-result v$register
                """
            )
        }

        // Let YouTube handle transient pause and regain, including background
        // playback. Leave permanent losses and gains to the original listener.
        val listener = AudioFocusChangeListenerFingerprint.matchOrNull()
            ?: AudioFocusChangeListenerLegacyFingerprint.match()
        listener.method.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->overrideAudioFocusChange(I)I
                move-result p1
            """
        )
    }
}
