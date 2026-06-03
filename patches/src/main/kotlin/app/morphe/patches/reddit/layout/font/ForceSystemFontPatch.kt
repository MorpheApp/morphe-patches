/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.font

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
    description = "Adds an option to render the app using the device's system font " +
            "instead of Reddit Sans / Roboto. Affects both classic Views and Compose text " +
            "that loads its typeface from an R.font.* resource.",
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        // TypefaceCompat.createFromResourcesFontFile is the central choke point that
        // returns a Typeface for every R.font.* lookup made by AndroidX (and therefore
        // by Compose's FontFamily resolver and View's font attribute).
        // Param mapping for this static method:
        //   p0 = Resources, p1 = font res id, p2 = path,
        //   p3 = ttc index, p4 = style (Typeface.NORMAL / BOLD / ITALIC / BOLD_ITALIC).
        TypefaceCompatCreateFromResourcesFontFileFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p4 }, $EXTENSION_CLASS->getSystemTypeface(I)Landroid/graphics/Typeface;
                move-result-object v0
                if-eqz v0, :original
                return-object v0
                :original
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
