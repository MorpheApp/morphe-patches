package app.morphe.patches.youtube.interaction.hapticfeedback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.fiveRegisters
import app.morphe.util.getReference
import app.morphe.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/DisableHapticFeedbackPatch;"

@Suppress("unused")
val disableHapticFeedbackPatch = bytecodePatch(
    name = "Disable haptic feedback",
    description = "Adds an option to disable haptic feedback in the player for various actions.",
) {
    dependsOn(settingsPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                "morphe_disable_haptic_feedback",
                preferences = setOf(
                    SwitchPreference("morphe_disable_haptic_feedback_chapters"),
                    SwitchPreference("morphe_disable_haptic_feedback_precise_seeking"),
                    SwitchPreference("morphe_disable_haptic_feedback_seek_undo"),
                    SwitchPreference("morphe_disable_haptic_feedback_tap_and_hold"),
                    SwitchPreference("morphe_disable_haptic_feedback_zoom"),
                )
            )
        )

        arrayOf(
            MarkerHapticsFingerprint to "disableChapterVibrate",
            ScrubbingHapticsFingerprint to "disablePreciseSeekingVibrate",
            SeekUndoHapticsFingerprint to "disableSeekUndoVibrate",
            ZoomHapticsFingerprint to "disableZoomVibrate"
        ).forEach { (fingerprint, methodName) ->
            fingerprint.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS->$methodName()Z
                    move-result v0
                    if-eqz v0, :vibrate
                    return-void
                    :vibrate
                    nop
                """
            )
        }

        val vibratorField = TapAndHoldHapticsHandlerFingerprint.instructionMatches.last()
            .instruction.getReference<FieldReference>()!!

        val tapAndHoldHapticsFingerprint = Fingerprint(
            name = "run",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(),
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IGET_OBJECT,
                    reference = vibratorField,
                ),
                checkCast("Landroid/os/Vibrator;"),
                string("Failed to easy seek haptics vibrate.")
            )
        )

        tapAndHoldHapticsFingerprint.let {
            // clearMatch() is used because it can be the same method as [TapAndHoldSpeedFingerprint].
            it.clearMatch()
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->disableTapAndHoldVibrate(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$register
                    """
                )
            }
        }

        arrayOf(
            methodCall(
                definingClass = "Landroid/os/Vibrator;",
                name = "vibrate",
                parameters = listOf("Landroid/os/VibrationEffect;"),
                returnType = "V"
            ),
            methodCall(
                definingClass = "Landroid/os/Vibrator;",
                name = "vibrate",
                parameters = listOf("J"),
                returnType = "V"
            )
        ).forEach { filter ->
            Fingerprint(
                filters = listOf(filter),
                custom = { _, classDef ->
                    classDef.type != EXTENSION_CLASS
                }
            ).matchAllMethodIndicesForEach { index ->
                val instruction = getInstruction<FiveRegisterInstruction>(index)
                val registers = fiveRegisters(index)

                replaceInstruction(
                    index,
                    "invoke-static { $registers }, $EXTENSION_CLASS->vibrate(Landroid/os/Vibrator;" +
                            filter.parameters!!.joinToString("") + ")V"
                )
            }
        }
    }
}