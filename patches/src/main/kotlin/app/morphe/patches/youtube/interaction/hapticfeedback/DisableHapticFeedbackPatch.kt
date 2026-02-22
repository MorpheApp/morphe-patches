package app.morphe.patches.youtube.interaction.hapticfeedback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patches.all.misc.transformation.IMethodCall
import app.morphe.patches.all.misc.transformation.filterMapInstruction35c
import app.morphe.patches.all.misc.transformation.transformInstructionsPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS_DESCRIPTOR_PREFIX =
    "Lapp/morphe/extension/youtube/patches/DisableHapticFeedbackPatch"

private const val EXTENSION_CLASS_DESCRIPTOR = "$EXTENSION_CLASS_DESCRIPTOR_PREFIX;"

@Suppress("unused")
val disableHapticFeedbackPatch = bytecodePatch(
    name = "Disable haptic feedback",
    description = "Adds an option to disable haptic feedback in the player for various actions.",
) {
    dependsOn(
        settingsPatch,
        transformInstructionsPatch(
            filterMap = { classDef, _, instruction, instructionIndex ->
                filterMapInstruction35c<MethodCall>(
                    EXTENSION_CLASS_DESCRIPTOR_PREFIX,
                    classDef,
                    instruction,
                    instructionIndex,
                )
            },
            transform = { method, entry ->
                val (methodType, instruction, instructionIndex) = entry
                methodType.replaceInvokeVirtualWithExtension(
                    EXTENSION_CLASS_DESCRIPTOR,
                    method,
                    instruction,
                    instructionIndex,
                )
            },
        ),
    )

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
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->$methodName()Z
                    move-result v0
                    if-eqz v0, :vibrate
                    return-void
                    :vibrate
                    nop
                """
            )
        }

        val vibratorField = TapAndHoldHapticsHandlerFingerprint.match()
            .instructionMatches.last().instruction.getReference<FieldReference>()!!

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
                        invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->disableTapAndHoldVibrate(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$register
                    """
                )
            }
        }
    }
}

// Information about method calls we want to replace
@Suppress("unused")
private enum class MethodCall(
    override val definedClassName: String,
    override val methodName: String,
    override val methodParams: Array<String>,
    override val returnType: String,
) : IMethodCall {
    VibrationEffect(
        "Landroid/os/Vibrator;",
        "vibrate",
        arrayOf("Landroid/os/VibrationEffect;"),
        "V",
    ),
    VibrationMilliseconds(
        "Landroid/os/Vibrator;",
        "vibrate",
        arrayOf("J"),
        "V",
    ),
}
