/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4178
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.announcements

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.util.returnEarly

internal const val PATCHES_UPDATE_NOTICE_EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/PatchesUpdateNoticePatch;"

private const val MANAGER_PACKAGE = "app.morphe.manager"

private val patchesUpdateNoticeResourcePatch = patch {
    execute {
        // Allows the "Update" button to open Morphe Manager on Android 11+.
        resourceXml("AndroidManifest.xml").use { manifest ->
            val queries = manifest.elements("queries").firstOrNull() ?: manifest.root.appendChild("queries")
            queries.appendChild("package") { setAttribute("android:name", MANAGER_PACKAGE) }
        }
    }
}

/**
 * The preference is added by the settings patch of each app.
 *
 * @param changelogScope The app name used in the release note scopes, such as "YouTube Music".
 */
internal fun patchesUpdateNoticePatch(
    mainActivityFingerprint: Fingerprint,
    changelogScope: String
) = patch(
    description = "Shows a notice on app startup when new patches with changes for the app are available.",
) {
    dependsOn(patchesUpdateNoticeResourcePatch)

    execute {
        PatchesUpdateNoticeChangelogScopeFingerprint.method.returnEarly(changelogScope)

        mainActivityFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $PATCHES_UPDATE_NOTICE_EXTENSION_CLASS->showNoticeIfNeeded(Landroid/app/Activity;)V",
        )
    }
}
