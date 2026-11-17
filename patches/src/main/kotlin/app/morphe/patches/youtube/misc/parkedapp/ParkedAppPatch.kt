/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.parkedapp

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val CAR_LAUNCHER_CATEGORY = "android.intent.category.CAR_LAUNCHER"

/**
 * The Android Auto car connection. Read to find the car display, and declared as a query
 * because YouTube does not always declare it itself.
 */
private const val CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection"

@Suppress("unused")
val parkedAppPatch = patch(
    name = "Parked app for Android Auto",
    description = "Declares YouTube a parked video app, so Android Auto (early access only) and DriveDeck " +
        "(available now) list it on the car screen by themselves. " +
        "Android Auto still needs Unknown sources in its developer settings for a patched app.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    // Last, so launcher entries other patches add, such as Custom branding, get the category too.
    finalize {
        resourceXml("AndroidManifest.xml").use { manifest ->
            val application = manifest.elements("application").first()

            if (application.attribute("android:appCategory").isNullOrEmpty()) {
                application.setAttribute("android:appCategory", "video")
            }

            for (tag in listOf("activity", "activity-alias")) {
                application.elements(tag).forEach { launched ->
                    launched.elements("intent-filter").forEach { filter ->
                        fun declares(tag: String, name: String) =
                            filter.elements(tag).any { it.attribute("android:name") == name }

                        if (declares("action", "android.intent.action.MAIN") &&
                            declares("category", "android.intent.category.LAUNCHER") &&
                            !declares("category", CAR_LAUNCHER_CATEGORY)
                        ) {
                            filter.appendChild("category") {
                                setAttribute("android:name", CAR_LAUNCHER_CATEGORY)
                            }
                        }
                    }
                }
            }

            val queries = manifest.elements("queries").firstOrNull()
                ?: manifest.root.insertChild(application.index, "queries")
            if (queries.elements("provider").none { it.attribute("android:authorities") == CAR_CONNECTION_AUTHORITY }) {
                queries.appendChild("provider") {
                    setAttribute("android:authorities", CAR_CONNECTION_AUTHORITY)
                }
            }
        }
    }
}
