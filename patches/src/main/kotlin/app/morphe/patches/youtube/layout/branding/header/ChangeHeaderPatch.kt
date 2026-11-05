/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.branding.header

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.patch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patches.shared.layout.branding.header.CUSTOM_HEADER_RESOURCE_NAME
import app.morphe.patches.shared.layout.branding.header.baseChangeHeaderPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private val variants = arrayOf("light", "dark")

private val targetResourceDirectoryNames = mapOf(
    "drawable-hdpi" to "194x72 px",
    "drawable-xhdpi" to "258x96 px",
    "drawable-xxhdpi" to "387x144 px",
    "drawable-xxxhdpi" to "512x192 px"
)

private val logoResourceNames = arrayOf("morphe_header")

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ChangeHeaderPatch;"

private val changeHeaderBytecodePatch = patch {

    execute {
        // Verify images exist. Resources are not used during patching but extension code does.
        arrayOf(
            "yt_ringo2_wordmark_header",
            "yt_ringo2_premium_wordmark_header"
        ).forEach { resource ->
            variants.forEach { theme ->
                resourceId(ResourceType.DRAWABLE, resource + "_" + theme)
            }
        }

        arrayOf(
            "ytWordmarkHeader",
            "ytPremiumWordmarkHeader"
        ).forEach { resourceName ->
            resourceLiteral(
                ResourceType.ATTR, resourceName
            ).matchAllMethodIndicesForEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->getHeaderAttributeId(I)I
                        move-result v$register    
                    """
                )
            }
        }

        DrawerHeaderRenderFingerprint.matchAll().forEach { match ->
            match.method.apply {
                val addViewIndex = match.instructionMatches.last().index
                val viewGroupRegister = getInstruction<FiveRegisterInstruction>(addViewIndex).registerC

                addInstructions(
                    addViewIndex + 1,
                    """
                        invoke-static { v$viewGroupRegister }, $EXTENSION_CLASS->updateDrawerLogo(Landroid/view/ViewGroup;)V
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
    preferenceScreen = PreferenceScreen.GENERAL,
    block = {
        dependsOn(changeHeaderBytecodePatch)
        compatibleWith(COMPATIBILITY_YOUTUBE)
    },
    executeBlock = {
        // Logo is replaced using an attribute reference.
        resourceXml("res/values/attrs.xml", copy = false).use { attrs ->
            fun addAttributeReference(logoName: String) {
                attrs.root.appendChild("attr") {
                    setAttribute("format", "reference")
                    setAttribute("name", logoName)
                }
            }

            logoResourceNames.forEach { logoName -> addAttributeReference(logoName) }
            addAttributeReference(CUSTOM_HEADER_RESOURCE_NAME)
        }

        // Add custom drawables to all styles that use the regular and premium logo.
        resourceXml("res/values/styles.xml").use { styles ->
            arrayOf(
                "Base.Theme.YouTube.Light" to "light",
                "Base.Theme.YouTube.Dark" to "dark",
                "CairoLightThemeRingo2Updates" to "light",
                "CairoDarkThemeRingo2Updates" to "dark"
            ).forEach { (style, mode) ->
                val styleElement = styles.resource(style)

                fun addDrawableElement(logoName: String, mode: String) {
                    styleElement.appendChild("item") {
                        setAttribute("name", logoName)
                        text = "@drawable/${logoName}_$mode"
                    }
                }

                logoResourceNames.forEach { logoName -> addDrawableElement(logoName, mode) }
                addDrawableElement(CUSTOM_HEADER_RESOURCE_NAME, mode)
            }
        }
    }
)
