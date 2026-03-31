package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.layout.shortsplayer.openShortsInRegularPlayerPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/OpenVideosFullscreenHookPatch;"

/**
 * Used by both [openVideosFullscreenPatch] and [openShortsInRegularPlayerPatch].
 */
internal val openVideosFullscreenHookPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch
    )

    execute {

        OpenVideosFullscreenPortraitFingerprint.let {
            // Remove A/B feature call that forces what this patch already does.
            // Cannot use the A/B flag to accomplish the same goal because 19.50+
            // Shorts fullscreen regular player does not use fullscreen
            // if the player is minimized, and it must be forced using other conditional check.
            it.method.insertLiteralOverride(
                it.instructionMatches.last().index,
                false
            )
        }

        OpenVideosFullscreenPortraitFingerprint.let {
            it.method.apply {
                val register = getInstruction<OneRegisterInstruction>(
                    it.instructionMatches.first().index
                ).registerA

                addInstructions(
                    it.instructionMatches.first().index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->doNotOpenVideoFullscreenPortrait(Z)Z
                        move-result v$register
                    """
                )
            }
        }
    }
}
