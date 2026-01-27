package app.morphe.patches.youtube.layout.branding.header

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.Document
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.layout.searchbar.wideSearchbarPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.util.ResourceGroup
import app.morphe.util.Utils.trimIndentMultiline
import app.morphe.util.copyResources
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.forEachLiteralValueInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Node
import java.io.File

private val variants = arrayOf("light", "dark")

private val targetResourceDirectoryNames = mapOf(
    "drawable-hdpi" to "194x72 px",
    "drawable-xhdpi" to "258x96 px",
    "drawable-xxhdpi" to "387x144 px",
    "drawable-xxxhdpi" to "512x192 px"
)

/**
 * Header logos built into this patch.
 */
private val logoResourceNames = arrayOf(
    "morphe_header",
)

/**
 * Custom header resource/file name.
 */
private const val CUSTOM_HEADER_RESOURCE_NAME = "morphe_header_custom"

/**
 * Custom header resource/file names.
 */
private val customHeaderResourceFileNames = variants.map { variant ->
    "${CUSTOM_HEADER_RESOURCE_NAME}_$variant.png"
}.toTypedArray()

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/ChangeHeaderPatch;"

private val changeHeaderBytecodePatch = bytecodePatch {
    dependsOn(
        resourceMappingPatch
    )

    execute {
        // Verify images exist. Resources are not used during patching but extension code does.
        arrayOf(
            "yt_ringo2_wordmark_header",
            "yt_ringo2_premium_wordmark_header"
        ).forEach { resource ->
            variants.forEach { theme ->
                getResourceId(ResourceType.DRAWABLE, resource + "_" + theme)
            }
        }

        arrayOf(
            "ytWordmarkHeader",
            "ytPremiumWordmarkHeader"
        ).forEach { resourceName ->
            val resourceId = getResourceId(ResourceType.ATTR, resourceName)

            forEachLiteralValueInstruction(resourceId) { literalIndex ->
                val register = getInstruction<OneRegisterInstruction>(literalIndex).registerA
                addInstructions(
                    literalIndex + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->getHeaderAttributeId(I)I
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
    description = "Adds an option to change the header logo in the top left corner of the app.",
) {
    dependsOn(
        changeHeaderBytecodePatch,
        wideSearchbarPatch
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.26.46",
            "20.31.42",
            "20.37.48",
            "20.40.45",
        )
    )

    val custom by stringOption(
        key = "custom",
        title = "Custom header logo",
        description = """
            Folder with images to use as a custom header logo.
            
            The folder must contain one or more of the following folders, depending on the DPI of the device:
            ${targetResourceDirectoryNames.keys.joinToString("\n") { "- $it" }}
            
            Each of the folders must contain all of the following files:
            ${customHeaderResourceFileNames.joinToString("\n")} 

            The image dimensions must be as follows:
            ${targetResourceDirectoryNames.map { (dpi, dim) -> "- $dpi: $dim" }.joinToString("\n")}
        """.trimIndentMultiline()
    )

    execute {
        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
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
            variants.forEach { variant ->
                copyResources(
                    "change-header",
                    ResourceGroup(
                        "drawable",
                        logo + "_" + variant + ".xml"
                    )
                )
            }
        }

        // Copy custom template. Images are only used if settings
        // are imported and a custom header is enabled.
        targetResourceDirectoryNames.keys.forEach { dpi ->
            variants.forEach { variant ->
                copyResources(
                    "change-header",
                    ResourceGroup(
                        dpi,
                        *customHeaderResourceFileNames
                    )
                )
            }
        }

        // Logo is replaced using an attribute reference.
        document("resources/package_1/res/values/attrs.xml").use { document ->
            val resources = document.childNodes.item(0)

            fun addAttributeReference(logoName: String) {
                val item = document.createElement("attr")
                item.setAttribute("formats", "reference")
                item.setAttribute("name", logoName)
                resources.appendChild(item)
            }

            logoResourceNames.forEach { logoName ->
                addAttributeReference(logoName)
            }

            addAttributeReference(CUSTOM_HEADER_RESOURCE_NAME)
        }

        fun getLastAttributeId(parentNode: Node, type: String): Int {
            var highestId = 0
            val numChildren = parentNode.childNodes.length
            for (i in 0 until numChildren) {
                val childNode = parentNode.childNodes.item(i)
                if (childNode.nodeType != Node.ELEMENT_NODE) continue

                val element = childNode as org.w3c.dom.Element
                val elemType = element.getAttribute("type")
                if (!elemType.equals(type)) continue

                val idString = element.getAttribute("id")
                if (idString.startsWith("0x")) {
                    val id = idString.substring(2).toInt(16)
                    if (id > highestId) {
                        highestId = id
                    }
                }
            }
            return highestId
        }

        document("resources/package_1/res/values/public.xml").use { document ->
            val resources = document.childNodes.item(0)

            // 0x7f040b24
            val startingId = getLastAttributeId(resources, "attr") + 1

            fun addAttributeReference(logoName: String, id: Int) {
                val item = document.createElement("public")
                item.setAttribute("id", "0x${id.toString(16)}")
                item.setAttribute("type", "attr")
                item.setAttribute("name", logoName)
                resources.appendChild(item)
            }

            logoResourceNames.forEachIndexed { index, logoName ->
                addAttributeReference(logoName, startingId + 1 + index)
            }

            addAttributeReference(CUSTOM_HEADER_RESOURCE_NAME, startingId)
        }

        // Add custom drawables to all styles that use the regular and premium logo.
        document("resources/package_1/res/values/styles.xml").use { document ->
            arrayOf(
                "Base.Theme.YouTube.Light" to "light",
                "Base.Theme.YouTube.Dark" to "dark",
                "CairoLightThemeRingo2Updates" to "light",
                "CairoDarkThemeRingo2Updates" to "dark"
            ).forEach { (style, mode) ->
                val styleElement = document.childNodes.findElementByAttributeValueOrThrow(
                    "name", style
                )

                fun addDrawableElement(document: Document, logoName: String, mode: String) {
                    val item = document.createElement("item")
                    item.setAttribute("name", logoName)
                    item.textContent = "@drawable/${logoName}_$mode"
                    styleElement.appendChild(item)
                }

                logoResourceNames.forEach { logoName ->
                    addDrawableElement(document, logoName, mode)
                }

                addDrawableElement(document, CUSTOM_HEADER_RESOURCE_NAME, mode)
            }
        }

        // Copy user provided images last, so if an exception is thrown due to bad input.
        if (custom != null) {
            val customFile = File(custom!!.trim())
            if (!customFile.exists()) {
                throw PatchException("The custom header path cannot be found: " +
                        customFile.absolutePath
                )
            }

            if (!customFile.isDirectory) {
                throw PatchException("The custom header path must be a folder: "
                        + customFile.absolutePath)
            }

            var copiedFiles = false

            // For each source folder, copy the files to the target resource directories.
            customFile.listFiles {
                file -> file.isDirectory && file.name in targetResourceDirectoryNames
            }!!.forEach { dpiSourceFolder ->
                val targetDpiFolder = get("res").resolve(dpiSourceFolder.name)
                if (!targetDpiFolder.exists()) {
                    // Should never happen.
                    throw IllegalStateException("Resource not found: $dpiSourceFolder")
                }

                val customFiles = dpiSourceFolder.listFiles { file ->
                    file.isFile && file.name in customHeaderResourceFileNames
                }!!

                if (customFiles.isNotEmpty() && customFiles.size != variants.size) {
                    throw PatchException("Both light/dark mode images " +
                            "must be specified but only found: " + customFiles.map { it.name })
                }

                customFiles.forEach { imgSourceFile ->
                    val imgTargetFile = targetDpiFolder.resolve(imgSourceFile.name)
                    imgSourceFile.copyTo(target = imgTargetFile, overwrite = true)

                    copiedFiles = true
                }
            }

            if (!copiedFiles) {
                throw PatchException("Expected to find directories and files: "
                        + customHeaderResourceFileNames.contentToString()
                        + "\nBut none were found in the provided option file path: " + customFile.absolutePath)
            }
        }
    }
}
