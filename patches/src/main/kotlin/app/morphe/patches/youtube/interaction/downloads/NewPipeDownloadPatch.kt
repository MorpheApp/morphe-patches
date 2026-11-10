package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patches.shared.misc.newpipedownload.NEWPIPE_DOWNLOADS_CLASS
import app.morphe.patches.shared.misc.newpipedownload.newPipeDownloadExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val newPipeDownloadPatch = patch(
    name = "NewPipe downloader",
    description = "Downloads video and audio files through the existing Download action using NewPipe.",
    default = false,
) {
    dependsOn(downloadsPatch, newPipeDownloadExtensionPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        SaveOfflineVideoCallFingerprint.clearMatch()
        val method = SaveOfflineVideoCallFingerprint.instructionMatches.first().getMethodCalled().cloneParameters()
        method.addInstructionsWithLabels(method.numberOfParameterRegistersLogical,
            """
                invoke-static/range {p1 .. p1}, $NEWPIPE_DOWNLOADS_CLASS->onDownload(Ljava/lang/String;)Z
                move-result p0
                if-eqz p0, :original_download
                return-void
                :original_download
                nop
            """
        )
    }
}
