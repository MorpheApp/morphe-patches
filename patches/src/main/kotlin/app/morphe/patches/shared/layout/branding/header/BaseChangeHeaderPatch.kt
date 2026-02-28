/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 */

package app.morphe.patches.shared.layout.branding.header

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.ResourcePatchBuilder
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.trimIndentMultiline
import java.io.File

const val CUSTOM_HEADER_RESOURCE_NAME = "morphe_header_custom"

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

    val custom by stringOption(
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
        """.trimIndentMultiline()
    )

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

        custom?.trim()?.let { customPath ->
            val customDir = File(customPath)
            if (!customDir.exists())
                throw PatchException("Custom header path not found: ${customDir.absolutePath}")

            if (!customDir.isDirectory)
                throw PatchException("Custom header path must be a directory.")

            var copied = false
            customDir.listFiles { file -> file.isDirectory && file.name in targetResourceDirectoryNames }
                ?.forEach { dpiFolder ->
                    val targetFolder = get("res").resolve(dpiFolder.name)
                    targetFolder.mkdirs()

                    val files = dpiFolder.listFiles { file ->
                        file.isFile && file.name in customHeaderResourceFileNames
                    } ?: emptyArray()

                    if (files.size != customHeaderResourceFileNames.size)
                        throw PatchException("Missing required images in ${dpiFolder.name}. Expected: ${customHeaderResourceFileNames.joinToString(", ")}")

                    files.forEach { source ->
                        source.copyTo(targetFolder.resolve(source.name), overwrite = true)
                        copied = true
                    }
                }

            if (!copied)
                throw PatchException("No valid DPI folders found in: ${customDir.absolutePath}")
        }

        executeBlock(customHeaderResourceFileNames)
    }
}