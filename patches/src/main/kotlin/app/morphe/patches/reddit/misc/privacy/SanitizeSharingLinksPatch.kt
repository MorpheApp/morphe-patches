/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SanitizeSharingLinksPatch;"

val sanitizeSharingLinksPatch = patch(
    name = "Sanitize sharing links",
    description = "Adds an option to sanitize sharing links by removing tracking query parameters."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        ShareLinkFormatterFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS->stripQueryParameters()Z
                move-result v0
                if-eqz v0, :off
                return-object p0
                :off
                nop
            """
        )

        // Do not request a short link. The app then shares the full link instead.
        val (shortUrlFingerprint, failedResult) = if (is_2026_24_0_or_greater) {
            val errorConstructor = GetShortUrlFingerprint.instructionMatches[2].methodReference
            GetShortUrlFingerprint to """
                new-instance v0, ${errorConstructor.definingClass}
                sget-object v1, $SHORT_URL_ERROR_CLASS->UNKNOWN_ERROR:$SHORT_URL_ERROR_CLASS
                invoke-direct { v0, v1 }, $errorConstructor
            """
        } else {
            GetShortUrlLegacyFingerprint to "const/4 v0, 0x0"
        }

        shortUrlFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS->disableShortLinks()Z
                move-result v0
                if-eqz v0, :off
                $failedResult
                return-object v0
                :off
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
