/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.accuweather.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.accuweather.shared.Constants.COMPATIBILITY_ACCUWEATHER
import app.morphe.util.returnEarly

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Removes fullscreen interstitial and app-open advertisements.",
) {
    compatibleWith(COMPATIBILITY_ACCUWEATHER)

    execute {
        // Ads run through the Google Ads Mobile SDK, obfuscated by R8. The
        // interstitial and app-open wrappers each expose a show(Activity) that
        // presents the fullscreen ad (it forwards to an internal display helper
        // and returns void). Resolve each wrapper class by its unique retained
        // parameter-name string, then turn show(Activity) into a no-op so no
        // fullscreen ad is ever presented. Returning void keeps callers happy,
        // so nothing crashes.
        listOf(
            InterstitialAdClassFingerprint,
            AppOpenAdClassFingerprint,
        ).forEach { anchor ->
            Fingerprint(
                definingClass = anchor.originalClassDef.type,
                name = "show",
                returnType = "V",
                parameters = listOf("Landroid/app/Activity;"),
            ).method.returnEarly()
        }
    }
}
