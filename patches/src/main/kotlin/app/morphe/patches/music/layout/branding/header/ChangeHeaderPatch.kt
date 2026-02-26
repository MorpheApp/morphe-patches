package app.morphe.patches.music.layout.branding.header

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.forEachLiteralValueInstruction
import app.morphe.util.trimIndentMultiline
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.io.File

private val targetResourceDirectoryNames = mapOf(
    "drawable-hdpi" to "121x36 px",
    "drawable-xhdpi" to "160x48 px",
    "drawable-xxhdpi" to "240x72 px",
    "drawable-xxxhdpi" to "320x96 px"
)

private val variants = arrayOf("dark")
private val logoResourceNames = arrayOf("morphe_header_dark")
private const val CUSTOM_HEADER_RESOURCE_NAME = "morphe_header_custom"
private val customHeaderResourceFileNames = variants.map { variant ->
    "${CUSTOM_HEADER_RESOURCE_NAME}_$variant.png"
}.toTypedArray()

private val headerDrawableNames = arrayOf(
    "action_bar_logo_ringo2",
    "ytm_logo_ringo2"
)

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/music/patches/ChangeHeaderPatch;"

private val changeHeaderBytecodePatch = bytecodePatch {
    dependsOn(resourceMappingPatch)

    execute {
        headerDrawableNames.forEach { drawableName ->
            val drawableId = getResourceId(ResourceType.DRAWABLE, drawableName)

            forEachLiteralValueInstruction(drawableId) { literalIndex ->
                val register = getInstruction<OneRegisterInstruction>(literalIndex).registerA

                addInstructions(
                    literalIndex + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->getHeaderDrawableId(I)I
                        move-result v$register
                    """
                )
            }
        }
    }
}

@Suppress("unused")
val changeHeaderPatch = resourcePatch(
    name = "Change header",
    description = "Adds an option to change the YouTube Music header logo."
) {

    dependsOn(changeHeaderBytecodePatch)
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    val custom by stringOption(
        key = "custom",
        title = "Custom header folder",
        description = """
            Folder with images to use as a custom header logo.

            The folder must contain one or more of:
            ${targetResourceDirectoryNames.keys.joinToString("\n") { "- $it" }}

            Each folder must contain the following file:
            ${customHeaderResourceFileNames.joinToString("\n") { "- $it" }}

            Required dimensions:
            ${targetResourceDirectoryNames.map { (dpi, dim) -> "- $dpi: $dim" }.joinToString("\n")}
        """.trimIndentMultiline()
    )

    execute {
        PreferenceScreen.GENERAL.addPreferences(
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
            copyResources(
                "change-header",
                ResourceGroup("drawable", "$logo.xml")
            )
        }

        targetResourceDirectoryNames.keys.forEach { dpi ->
            copyResources(
                "change-header",
                ResourceGroup(dpi, *customHeaderResourceFileNames)
            )
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
                        throw PatchException("Missing required image in ${dpiFolder.name}. Expected: ${customHeaderResourceFileNames[0]}")

                    files.forEach { source ->
                        source.copyTo(targetFolder.resolve(source.name), overwrite = true)
                        copied = true
                    }
                }

            if (!copied)
                throw PatchException("No valid DPI folders found in: ${customDir.absolutePath}")
        }
    }
}