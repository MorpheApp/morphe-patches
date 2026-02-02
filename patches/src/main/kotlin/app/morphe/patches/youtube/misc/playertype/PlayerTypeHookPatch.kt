package app.morphe.patches.youtube.misc.playertype

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/PlayerTypeHookPatch;"

val playerTypeHookPatch = bytecodePatch(
    description = "Hook to get the current player type and video playback state.",
) {
    dependsOn(sharedExtensionPatch, resourceMappingPatch)

    execute {
        val PlayerOverlaysSetPlayerTypeFingerprint = Fingerprint(
            definingClass = "/YouTubePlayerOverlaysLayout;",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(PlayerTypeEnumFingerprint.originalClassDef.type)
        )

        PlayerOverlaysSetPlayerTypeFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->setPlayerType(Ljava/lang/Enum;)V",
        )

        ReelWatchPagerFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->onShortsCreate(Landroid/view/View;)V"
                )
            }
        }

        val controlStateType = ControlsStateToStringFingerprint.originalClassDef.type

        val VideoStateFingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(controlStateType),
            filters = listOf(
                // Obfuscated parameter field name.
                fieldAccess(
                    definingClass = controlStateType,
                    type = VideoStateEnumFingerprint.originalClassDef.type
                ),
                resourceLiteral(ResourceType.STRING, "accessibility_play"),
                resourceLiteral(ResourceType.STRING, "accessibility_pause")
            )
        )

        VideoStateFingerprint.let {
            it.method.apply {
                val videoStateFieldName = getInstruction<ReferenceInstruction>(
                    it.instructionMatches.first().index
                ).reference

                addInstructions(
                    0,
                    """
                        iget-object v0, p1, $videoStateFieldName  # copy VideoState parameter field
                        invoke-static {v0}, $EXTENSION_CLASS_DESCRIPTOR->setVideoState(Ljava/lang/Enum;)V
                    """
                )
            }
        }
    }
}
