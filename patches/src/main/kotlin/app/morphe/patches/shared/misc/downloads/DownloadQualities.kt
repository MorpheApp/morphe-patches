/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4890
 * https://github.com/MorpheApp/morphe-patches/pull/4909
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.shared.FormatStreamModelToStringFingerprint
import app.morphe.util.findMethodFromToString

private const val EXTENSION_BASE_IN_APP_DOWNLOADS_CLASS =
    "Lapp/morphe/extension/shared/patches/BaseInAppDownloadsPatch;"

internal const val EXTENSION_STREAMING_DATA_INTERFACE =
    "Lapp/morphe/extension/shared/patches/BaseInAppDownloadsPatch\$StreamingDataInterface;"

internal const val EXTENSION_FORMAT_STREAM_INTERFACE =
    "Lapp/morphe/extension/shared/patches/BaseInAppDownloadsPatch\$FormatStreamInterface;"

/**
 * Offers the download qualities up to 2160p on every screen, labels the qualities above 1080p,
 * and adds the interfaces of the streaming data and the streams, which the extension uses to
 * pick the streams of a download. YouTube and YouTube Music save downloads the same way.
 */
internal fun hookDownloadQualities() {
    // The app offers only the qualities up to the shorter side of the screen.
    OfflineSettingsConstructorFingerprint.method.addInstructions(
        0,
        """
            invoke-static { p3 }, $EXTENSION_BASE_IN_APP_DOWNLOADS_CLASS->getMaxDownloadQualityHeight(I)I
            move-result p3
        """
    )

    // Labels of the qualities above 1080p in the download quality setting.
    FormatTypeLabelFingerprint.method.addInstructionsWithLabels(
        0,
        """
            invoke-static { p0 }, $EXTENSION_BASE_IN_APP_DOWNLOADS_CLASS->getDownloadQualityLabel(Ljava/lang/Object;)I
            move-result v0
            if-eqz v0, :app_label
            return v0
            :app_label
            nop
        """
    )

    StreamingDataFormatStreamsFingerprint.let {
        it.classDef.addPatchInterface(
            EXTENSION_STREAMING_DATA_INTERFACE,
            "patch_getFormatStreams",
            it.instructionMatches.first().fieldReference
        )
    }

    FormatStreamModelToStringFingerprint.let {
        mapOf(
            "itag" to "patch_getItag",
            "width" to "patch_getWidth",
            "height" to "patch_getHeight",
            "mimeType" to "patch_getMimeType",
            "xtags=" to "patch_getXtags"
        ).forEach { (toStringName, interfaceMethodName) ->
            it.classDef.addPatchInterface(
                EXTENSION_FORMAT_STREAM_INTERFACE,
                interfaceMethodName,
                it.originalMethod.findMethodFromToString(toStringName)
            )
        }
    }
}
