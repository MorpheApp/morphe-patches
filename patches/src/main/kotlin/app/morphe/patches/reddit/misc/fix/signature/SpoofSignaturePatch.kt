/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.fix.signature

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.installer.changeInstallerSource
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.misc.version.is_2024_03_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT_INCLUDING_LEGACY
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SpoofSignaturePatch;"

@Suppress("unused")
val spoofSignaturePatch = bytecodePatch(
    name = "Spoof signature",
    description = "Spoofs the signature of the app to fix notification issues."
) {
    category("Spoofing")

    compatibleWith(COMPATIBILITY_REDDIT_INCLUDING_LEGACY)

    dependsOn(sharedExtensionPatch, changeInstallerSource, versionCheckPatch)

    execute {
        if (!is_2024_03_0_or_greater) {
            Logger.getLogger(this::class.java.name).warning(
                "Reddit 2024.02.0 has limited support. No meaningful patches are " +
                        "available, and this should only be used to successfully login " +
                        "and then upgrade the installation to a newer version of Reddit."
            )
        }

        ApplicationFingerprint.classDef.setSuperClass(EXTENSION_CLASS)
    }
}
