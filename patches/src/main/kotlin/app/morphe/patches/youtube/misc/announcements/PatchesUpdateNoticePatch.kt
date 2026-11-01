/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4174
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.announcements

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/announcements/PatchesUpdateNoticePatch;"

private const val MANAGER_PACKAGE = "app.morphe.manager"

private val patchesUpdateNoticeResourcePatch = resourcePatch {
    execute {
        // Allows the "Update" button to open Morphe Manager on Android 11+.
        document("AndroidManifest.xml").use { document ->
            val queries = document.getElementsByTagName("queries").item(0)
                ?: document.createElement("queries").also { document.documentElement.appendChild(it) }

            queries.appendChild(
                document.createElement("package").apply {
                    setAttribute("android:name", MANAGER_PACKAGE)
                }
            )
        }
    }
}

@Suppress("unused")
val patchesUpdateNoticePatch = bytecodePatch(
    name = "Patches update notice",
    description = "Adds an option to show a notice on app startup when new patches with YouTube changes are available.",
) {
    dependsOn(
        settingsPatch,
        patchesUpdateNoticeResourcePatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.MISC.addPreferences(
            // Kept together, so the dev releases option is shown right under the notice option.
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_patches_update_notice", summary = true),
                SwitchPreference("morphe_patches_update_notice_dev_releases", summary = true),
            ),
        )

        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->showNoticeIfNeeded(Landroid/app/Activity;)V",
        )
    }
}
