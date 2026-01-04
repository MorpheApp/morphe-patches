package app.morphe.patches.shared.layout.branding

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.util.inputStreamFromBundledResource
import java.nio.file.Files

/**
 * Copies the license and branding notice files to the target apk.
 */
internal val addLicensePatch = rawResourcePatch {
    execute {
        arrayOf(
            "MORPHE_BRANDING.TXT",
            "MORPHE_LICENSE.TXT",
            "MORPHE_LICENSE_NOTICE.TXT"
        ).forEach { sourceFileName ->
            val inputFileStream = inputStreamFromBundledResource(
                "license",
                sourceFileName
            )!!

            val targetFile = get(sourceFileName, false).toPath()

            // Check if target file exists and give a more informative error
            // because Files.copy throws an exception if the file already exists.
            if (Files.exists(targetFile)) {
                throw PatchException("App is already modified with Morphe patches ($targetFile already exists)")
            }

            Files.copy(inputFileStream, targetFile)
        }
    }
}
