package app.morphe.patches.youtube.misc.playertype

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/PlayerTypeHookPatch;"

val playerTypeHookPatch = bytecodePatch(
    description = "Hook to get the current player type and video playback state.",
) {
    dependsOn(sharedExtensionPatch, resourceMappingPatch)

    execute {
        youTubePlayerOverlaysLayoutFingerprint(
            PlayerTypeEnumFingerprint.originalClassDef.type
        ).method.addInstruction(
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

        val controlStateType = ControlsStateToStringFingerprint.originalClassDef.type
        val videoStateType = VideoStateEnumFingerprint.originalClassDef.type

        videoStateFingerprint(
            controlStateType = controlStateType,
            videoStateEnumType = videoStateType
        ).let {
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
