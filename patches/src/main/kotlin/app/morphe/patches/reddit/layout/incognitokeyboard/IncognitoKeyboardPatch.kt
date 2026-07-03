/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.incognitokeyboard

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/IncognitoKeyboardPatch;"

@Suppress("unused")
val incognitoKeyboardPatch = bytecodePatch(
    name = "Incognito keyboard",
    description = "Adds an option to enable incognito keyboard mode."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        FrontpageApplicationOnCreateFingerprint.method.apply {
            addInstructions(
                0,
                """
                    invoke-static { p0 }, $EXTENSION_CLASS->initialize(Landroid/app/Application;)V
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
