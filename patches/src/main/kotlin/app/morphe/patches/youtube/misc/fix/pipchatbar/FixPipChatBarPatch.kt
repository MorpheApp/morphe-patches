package app.morphe.patches.youtube.misc.fix.pipchatbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/FixPipChatBarPatch;"

@Suppress("unused")
val fixPipChatBarPatch = bytecodePatch(
    name = "Fix PiP chat bar",
    description = "Hides the bar that appears over the PiP video after using live chat text entry.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PipModeChangedFingerprint.method.addInstruction(
            1,
            "invoke-static { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->onPipModeChanged(Landroid/app/Activity;Z)V"
        )
    }
}
