/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4930
 *
 * Adapted from
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.parkedapp

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val CAR_LAUNCHER_CATEGORY = "android.intent.category.CAR_LAUNCHER"

@Suppress("unused")
val parkedAppPatch = patch(
    name = "Parked app for Android Auto",
    // TODO: Remove "early access only" text when Android Auto feature is widely available.
    description = "Declares YouTube a parked video app, so Android Auto (early access only) and DriveDeck " +
        "(available now) list it on the car screen by themselves. " +
        "Android Auto still needs Unknown sources in its developer settings for a patched app.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    // In finalize, so launcher entries other patches add in execute get the category too.
    // Custom branding adds its entries in finalize by copying the original intent filters,
    // so those keep the category.
    finalize {
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.elements("application").first().setAttribute("android:appCategory", "video")

            manifest.elements("intent-filter").forEach { filter ->
                fun declares(tag: String, name: String) =
                    filter.children(tag).any { it.attribute("android:name") == name }

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
}
