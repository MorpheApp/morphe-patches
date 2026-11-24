/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5191
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.reply

import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.flag.featureFlagHookPatch
import app.morphe.patches.reddit.misc.flag.hookFeatureFlag
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/FullScreenReplyPatch;"

@Suppress("unused")
val fullScreenReplyPatch = patch(
    name = "Full-screen reply",
    description = "Adds an option to write replies in the older full-screen editor, which shows the comment you are replying to. " +
        "This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        featureFlagHookPatch,
        versionCheckPatch
    )

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Full-screen reply' does not work with Reddit 2026.23.0 and earlier"
            )
        }

        hookFeatureFlag("$EXTENSION_CLASS->useFullScreenReply")

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
