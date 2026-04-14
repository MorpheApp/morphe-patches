package app.morphe.patches.youtube.misc.fix.pipchatbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/FixPipChatBarPatch;"

/**
 * Hides the bar that appears over the PiP video after using live chat text entry.
 */
internal val fixPipChatBarPatch = bytecodePatch{
    dependsOn(sharedExtensionPatch)

    execute {
        PipModeChangedFingerprint.method.addInstruction(
            0,
            "invoke-static { p0, p1 }, $EXTENSION_CLASS->onPipModeChanged(Landroid/app/Activity;Z)V"
        )
    }
}
