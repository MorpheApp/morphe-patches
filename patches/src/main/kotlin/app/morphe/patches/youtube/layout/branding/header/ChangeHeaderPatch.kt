/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 */

package app.morphe.patches.youtube.layout.branding.header

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.Document
import app.morphe.patches.shared.layout.branding.header.CUSTOM_HEADER_RESOURCE_NAME
import app.morphe.patches.shared.layout.branding.header.baseChangeHeaderPatch
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.forEachLiteralValueInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private val variants = arrayOf("light", "dark")

private val targetResourceDirectoryNames = mapOf(
    "drawable-hdpi" to "194x72 px",
    "drawable-xhdpi" to "258x96 px",
    "drawable-xxhdpi" to "387x144 px",
    "drawable-xxxhdpi" to "512x192 px"
)

private val logoResourceNames = arrayOf("morphe_header")

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/ChangeHeaderPatch;"

private val changeHeaderBytecodePatch = bytecodePatch {
    dependsOn(resourceMappingPatch)

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
val changeHeaderPatch = baseChangeHeaderPatch(
    targetResourceDirectoryNames = targetResourceDirectoryNames,
    variants = variants,
    logoResourceNames = logoResourceNames,
    appendVariantToLogo = true,
    preferenceScreen = PreferenceScreen.GENERAL_LAYOUT,
    block = {
        dependsOn(changeHeaderBytecodePatch)
        compatibleWith(COMPATIBILITY_YOUTUBE)
    },
    executeBlock = {
        // Logo is replaced using an attribute reference.
        document("res/values/attrs.xml").use { document ->
            val resources = document.childNodes.item(0)

            fun addAttributeReference(logoName: String) {
                val item = document.createElement("attr")
                item.setAttribute("format", "reference")
                item.setAttribute("name", logoName)
                resources.appendChild(item)
            }

            logoResourceNames.forEach { logoName -> addAttributeReference(logoName) }
            addAttributeReference(CUSTOM_HEADER_RESOURCE_NAME)
        }

        // Add custom drawables to all styles that use the regular and premium logo.
        document("res/values/styles.xml").use { document ->
            arrayOf(
                "Base.Theme.YouTube.Light" to "light",
                "Base.Theme.YouTube.Dark" to "dark",
                "CairoLightThemeRingo2Updates" to "light",
                "CairoDarkThemeRingo2Updates" to "dark"
            ).forEach { (style, mode) ->
                val styleElement = document.childNodes.findElementByAttributeValueOrThrow("name", style)

                fun addDrawableElement(document: Document, logoName: String, mode: String) {
                    val item = document.createElement("item")
                    item.setAttribute("name", logoName)
                    item.textContent = "@drawable/${logoName}_$mode"
                    styleElement.appendChild(item)
                }

                logoResourceNames.forEach { logoName -> addDrawableElement(document, logoName, mode) }
                addDrawableElement(document, CUSTOM_HEADER_RESOURCE_NAME, mode)
            }
        }
    }
)