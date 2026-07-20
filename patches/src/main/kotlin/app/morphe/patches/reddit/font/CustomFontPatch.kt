/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2100
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
    "Lapp/morphe/extension/reddit/patches/CustomFontPatch;"

@Suppress("unused")
val customFontPatch = bytecodePatch(
    name = "Custom font",
    description = "Adds an option to replace Reddit Sans / Roboto with a custom TTF or OTF font file at runtime.",
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
                        invoke-static { p0, p2, p4 }, $EXTENSION_CLASS->getCustomTypeface(Landroid/content/res/Resources;Ljava/lang/String;I)Landroid/graphics/Typeface;
                        move-result-object v0
                        if-eqz v0, :original
                        return-object v0
                        :original
                        nop
                    """

                TypefaceCompatCreateFromResourcesFontFileVariant.MODERN_VIRTUAL ->
                    """
                        invoke-static { p2, p4, p5 }, $EXTENSION_CLASS->getCustomTypeface(Landroid/content/res/Resources;Ljava/lang/String;I)Landroid/graphics/Typeface;
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