/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.layout.branding.header

import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.ResourcePatchBuilder
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.folderOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import java.io.File
import java.util.logging.Logger

const val CUSTOM_HEADER_RESOURCE_NAME = "morphe_header_custom"

// Must be named in the 'app.morphe' namespace, otherwise the log is not published.
private val logger = Logger.getLogger(ResourcePatchContext::class.java.name)

internal fun baseChangeHeaderPatch(
    targetResourceDirectoryNames: Map<String, String>,
    variants: Array<String>,
    logoResourceNames: Array<String>,
    appendVariantToLogo: Boolean,
    preferenceScreen: BasePreferenceScreen.Screen,
    block: ResourcePatchBuilder.() -> Unit,
    executeBlock: ResourcePatchContext.(customHeaderResourceFileNames: Array<String>) -> Unit = {}
): ResourcePatch = resourcePatch(
    name = "Change header",
    description = "Adds an option to change the header logo in the top left corner of the app."
) {
    val customHeaderResourceFileNames = variants.map { variant ->
        "${CUSTOM_HEADER_RESOURCE_NAME}_$variant.png"
    }.toTypedArray()

    val custom by folderOption(
        key = "custom",
        title = "Custom header logo",
        description = """
            Folder with images to use as a custom header logo.

            The folder must contain one or more of the following folders, depending on the DPI of the device:
            ${targetResourceDirectoryNames.keys.joinToString("\n") { "- $it" }}

            Each of the folders must contain the following file(s):
            ${customHeaderResourceFileNames.joinToString("\n") { "- $it" }}

            Required dimensions:
            ${targetResourceDirectoryNames.map { (dpi, dim) -> "- $dpi: $dim" }.joinToString("\n")}
        """
    ) {
        it == null || customHeaderImages(it, targetResourceDirectoryNames.keys, customHeaderResourceFileNames) != null
    }

    block()

    execute {
        preferenceScreen.addPreferences(
            if (custom == null) {
                ListPreference("morphe_header_logo")
            } else {
                ListPreference(
                    key = "morphe_header_logo",
                    entriesKey = "morphe_header_logo_custom_entries",
                    entryValuesKey = "morphe_header_logo_custom_entry_values"
                )
            }
        )

        logoResourceNames.forEach { logo ->
            if (appendVariantToLogo) {
                variants.forEach { variant ->
                    copyResources("change-header", ResourceGroup("drawable", "${logo}_$variant.xml"))
                }
            } else {
                copyResources("change-header", ResourceGroup("drawable", "$logo.xml"))
            }
        }

        targetResourceDirectoryNames.keys.forEach { dpi ->
            copyResources("change-header", ResourceGroup(dpi, *customHeaderResourceFileNames))
        }

        // Reading the option already checked the folder, see customHeaderImages
        custom?.let { path ->
            val resourceDirectory = get("res")
            customHeaderImages(path, targetResourceDirectoryNames.keys, customHeaderResourceFileNames)!!.forEach { image ->
                image.copyTo(resourceDirectory.resolve("${image.parentFile.name}/${image.name}"), overwrite = true)
            }
        }

        executeBlock(customHeaderResourceFileNames)
    }
}

/**
 * The images of the custom header folder at [path], each in the folder of its density, or null
 * when the folder has none of [densities] or leaves out one of [fileNames] in a density it has.
 * The reason is logged, as a failed option validation does not say what is wrong.
 */
private fun customHeaderImages(path: String, densities: Set<String>, fileNames: Array<String>): List<File>? {
    val folder = File(path.trim())
    val densityFolders = folder.listFiles { file -> file.isDirectory && file.name in densities }
    if (densityFolders.isNullOrEmpty()) {
        logger.warning("Custom header folder must contain one of ${densities.joinToString()}: ${folder.absolutePath}")
        return null
    }

    return densityFolders.flatMap { densityFolder ->
        val images = fileNames.map(densityFolder::resolve)
        val missing = images.filterNot(File::isFile)
        if (missing.isNotEmpty()) {
            logger.warning("Custom header folder ${densityFolder.name} is missing: ${missing.joinToString { it.name }}")
            return null
        }
        images
    }
}
