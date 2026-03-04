/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.music.layout.branding.header

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.layout.branding.header.baseChangeHeaderPatch
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.forEachLiteralValueInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private val targetResourceDirectoryNames = mapOf(
    "drawable-hdpi" to "121x36 px",
    "drawable-xhdpi" to "160x48 px",
    "drawable-xxhdpi" to "240x72 px",
    "drawable-xxxhdpi" to "320x96 px"
)

private val variants = arrayOf("dark")
private val logoResourceNames = arrayOf("morphe_header_dark")

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
val changeHeaderPatch = baseChangeHeaderPatch(
    targetResourceDirectoryNames = targetResourceDirectoryNames,
    variants = variants,
    logoResourceNames = logoResourceNames,
    appendVariantToLogo = false,
    preferenceScreen = PreferenceScreen.GENERAL,
    block = {
        dependsOn(changeHeaderBytecodePatch)
        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    }
)