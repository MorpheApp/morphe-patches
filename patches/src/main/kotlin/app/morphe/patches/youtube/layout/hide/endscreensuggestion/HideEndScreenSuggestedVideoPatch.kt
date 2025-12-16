package app.morphe.patches.youtube.layout.hide.endscreensuggestion

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HideEndScreenSuggestedVideoPatch;"

@Suppress("unused")
val hideEndScreenSuggestedVideoPatch = bytecodePatch(
    name = "Hide end screen suggested video",
    description = "Adds an option to hide the suggested video at the end of videos.",
) {
    dependsOn(
        sharedExtensionPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_end_screen_suggested_video"),
        )

        RemoveOnLayoutChangeListenerFingerprint.let {
            val endScreenMethod = navigate(it.originalMethod).to(it.instructionMatches.last().index).stop()

            endScreenMethod.apply {
                val autoNavStatusMethodName = AutoNavStatusFingerprint.match(
                    AutoNavConstructorFingerprint.classDef
                ).originalMethod.name

                val invokeIndex = indexOfFirstInstructionOrThrow {
                    val reference = getReference<MethodReference>()
                    reference?.name == autoNavStatusMethodName &&
                            reference.returnType == "Z" &&
                            reference.parameterTypes.isEmpty()
                }
                val iGetObjectIndex = indexOfFirstInstructionReversedOrThrow(invokeIndex, Opcode.IGET_OBJECT)
                val invokeReference = getInstruction<ReferenceInstruction>(invokeIndex).reference
                val iGetObjectReference = getInstruction<ReferenceInstruction>(iGetObjectIndex).reference
                val opcodeName = getInstruction(invokeIndex).opcode.name

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideEndScreenSuggestedVideo()Z
                        move-result v0
                        if-eqz v0, :show_end_screen_recommendation

                        iget-object v0, p0, $iGetObjectReference

                        # This reference checks whether autoplay is turned on.
                        $opcodeName { v0 }, $invokeReference
                        move-result v0

                        # Hide suggested video end screen only when autoplay is turned off.
                        if-nez v0, :show_end_screen_recommendation
                        return-void
                    """,
                    ExternalLabel("show_end_screen_recommendation", getInstruction(0))
                )
            }
        }
    }
}
