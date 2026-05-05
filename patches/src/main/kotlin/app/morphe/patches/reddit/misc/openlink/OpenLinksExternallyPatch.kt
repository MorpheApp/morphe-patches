/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/OpenLinksExternallyPatch;"

@Suppress("unused")
val openLinksExternallyPatch = bytecodePatch(
    name = "Open links externally",
    description = "Adds an option to always open links in your browser instead of with the in-app-browser."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        screenNavigatorMethodResolverPatch
    )

    execute {
        screenNavigatorMethodRef.get()!!.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1, p2 }, $EXTENSION_CLASS->openLinksExternally(Landroid/app/Activity;Landroid/net/Uri;)Z
                    move-result v0
                    if-eqz v0, :ignore
                    return-void
                    :ignore
                    nop
                """
            )
        }

        FbpActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                    "setActivity(Landroid/app/Activity;)V"
        )

        // Use matchAll() to behave like single()
        ArticleConstructorFingerprint.matchAll(1 .. 1).first().let {
            it.method.apply {
                addInstruction(
                    0,
                    "invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->" +
                            "openLinksExternally(Ljava/lang/String;)V"
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

