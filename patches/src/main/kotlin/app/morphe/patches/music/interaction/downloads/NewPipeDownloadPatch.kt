package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.newpipedownload.NEWPIPE_DOWNLOADS_CLASS
import app.morphe.patches.shared.misc.newpipedownload.newPipeDownloadExtensionPatch

@Suppress("unused")
val newPipeDownloadPatch = patch(
    name = "NewPipe downloader",
    description = "Downloads video and audio files through the existing Download action using NewPipe.",
    default = false,
) {
    dependsOn(downloadsPatch, newPipeDownloadExtensionPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val method = Fingerprint(
            definingClass = "Lapp/morphe/extension/music/patches/DownloadsPatch;",
            name = "startDownload",
            parameters = listOf("Ljava/lang/String;", "Z"),
            returnType = "V",
        ).method.cloneParameters()
        method.addInstructionsWithLabels(method.numberOfParameterRegistersLogical,
            """
                if-nez p1, :original_download
                invoke-static/range {p0 .. p0}, $NEWPIPE_DOWNLOADS_CLASS->onDownload(Ljava/lang/String;)Z
                move-result p1
                if-eqz p1, :original_download
                return-void
                :original_download
                nop
            """
        )
    }
}
