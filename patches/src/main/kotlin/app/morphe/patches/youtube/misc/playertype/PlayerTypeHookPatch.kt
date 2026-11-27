package app.morphe.patches.youtube.misc.playertype

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.PlayerTypeFingerprint
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/PlayerTypeHookPatch;"

val playerTypeHookPatch = patch(
    description = "Hook to get the current player type and video playback state.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        PlayerTypeFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->setPlayerType(Ljava/lang/Enum;)V",
        )

        ReelWatchPagerFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->onShortsCreate(Landroid/view/View;)V"
                )
            }
        }

        PlayPauseButtonControlsStateFingerprint.let {
            it.method.apply {
                val videoStateFieldName = getInstruction<ReferenceInstruction>(
                    it.instructionMatches.first().index
                ).reference

                addInstructions(
                    0,
                    """
                        iget-object v0, p1, $videoStateFieldName  # copy VideoState parameter field
                        invoke-static {v0}, $EXTENSION_CLASS->setVideoState(Ljava/lang/Enum;)V
                    """
                )
            }
        }
    }
}
