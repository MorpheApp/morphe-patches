/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5193
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.downloadall

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.reddit.misc.openlink.FbpActivityOnCreateFingerprint
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_44_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.fiveRegisters
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DownloadAllFromGalleryPatch;"

private const val EXTENSION_SHARE_ACTION_HANDLER_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/DownloadAllFromGalleryPatch$ShareActionHandler;"

@Suppress("unused")
val downloadAllFromGalleryPatch = patch(
    name = "Download all from gallery",
    description = "Adds an option to download every item of a gallery post with the Download button of the share sheet. " +
            "This patch works with Reddit 2026.44.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_44_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Download all from gallery' does not work with Reddit 2026.43.0 and earlier"
            )
        }

        ShareSheetLinkFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->setShareSheetLink(Lcom/reddit/domain/model/Link;)V"
        )

        // The media viewer is an activity of its own, and the dialog must show over it.
        FbpActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setMediaViewerActivity(Landroid/app/Activity;)V"
        )

        ShareActionDownloadMediaFingerprint.let {
            val launchDownloadMatch = it.instructionMatches[1]

            it.classDef.apply {
                addPatchInterface(
                    EXTENSION_SHARE_ACTION_HANDLER_INTERFACE,
                    "patch_launchDownload",
                    launchDownloadMatch.methodReference
                )

                val argsField = ShareActionHandlerConstructorFingerprint.instructionMatches[0].fieldReference
                addPatchInterface(
                    EXTENSION_SHARE_ACTION_HANDLER_INTERFACE,
                    "patch_getPostId",
                    """
                        iget-object v0, p0, $argsField
                        invoke-static { v0 }, ${ShareSheetPostIdFingerprint.method}
                        move-result-object v0
                        return-object v0
                    """
                )
            }

            // Show the dialog instead of launching the download.
            it.method.replaceInstruction(
                launchDownloadMatch.index,
                "invoke-static { ${it.method.fiveRegisters(launchDownloadMatch.index)} }, " +
                        "$EXTENSION_CLASS->launchDownload($EXTENSION_SHARE_ACTION_HANDLER_INTERFACE" +
                        "Ljava/lang/Object;)V"
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
