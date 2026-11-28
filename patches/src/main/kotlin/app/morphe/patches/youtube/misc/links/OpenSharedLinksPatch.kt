/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5482
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.links

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val SHARE_ACTIVITY_CLASS_NAME =
    "app.morphe.extension.youtube.patches.OpenSharedLinksActivity"

@Suppress("unused")
val openSharedLinksPatch = patch(
    name = "Open shared links",
    description = "Adds YouTube to the share menu of other apps, to open YouTube links in shared text.",
    // The share menu lists the app for all shared text, including text without a link.
    default = false,
) {
    dependsOn(
        sharedExtensionPatch,
        // Adds the strings of the extension.
        addResourcesPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.elements("application").first().appendChild("activity") {
                setAttribute("android:name", SHARE_ACTIVITY_CLASS_NAME)
                setAttribute("android:exported", "true")
                setAttribute("android:excludeFromRecents", "true")
                setAttribute("android:noHistory", "true")
                // The activity finishes before it is shown.
                setAttribute("android:theme", "@android:style/Theme.NoDisplay")
                appendChild("intent-filter") {
                    appendChild("action") {
                        setAttribute("android:name", "android.intent.action.SEND")
                    }
                    appendChild("category") {
                        setAttribute("android:name", "android.intent.category.DEFAULT")
                    }
                    appendChild("data") {
                        setAttribute("android:mimeType", "text/plain")
                    }
                }
            }
        }
    }
}
