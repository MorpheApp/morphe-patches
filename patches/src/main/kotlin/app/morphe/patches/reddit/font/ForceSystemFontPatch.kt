/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.font

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/ForceSystemFontPatch;"

@Suppress("unused")
val forceSystemFontPatch = bytecodePatch(
    name = "Force system font",
    description = "Adds an option that renders Reddit with the device system font instead of Reddit Sans / Roboto.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    extendWith("extensions/reddit.mpe")

    execute {
        val targetMethod = resolveTypefaceCompatCreateFromResourcesFontFileTarget()

        targetMethod.method.addInstructionsWithLabels(
            0,
            when (targetMethod.variant) {
                TypefaceCompatCreateFromResourcesFontFileVariant.LEGACY_STATIC ->
                    """
                        invoke-static { p4 }, $EXTENSION_CLASS->getSystemTypeface(I)Landroid/graphics/Typeface;
                        move-result-object v0
                        if-eqz v0, :original
                        return-object v0
                        :original
                        nop
                    """

                TypefaceCompatCreateFromResourcesFontFileVariant.MODERN_VIRTUAL ->
                    """
                        invoke-static { p5 }, $EXTENSION_CLASS->getSystemTypeface(I)Landroid/graphics/Typeface;
                        move-result-object v0
                        if-eqz v0, :original
                        return-object v0
                        :original
                        nop
                    """
            }
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}