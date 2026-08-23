/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.accuweather.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.accuweather.shared.Constants.COMPATIBILITY_ACCUWEATHER
import app.morphe.util.returnEarly

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Removes fullscreen (interstitial, app-open) and banner advertisements.",
) {
    compatibleWith(COMPATIBILITY_ACCUWEATHER)

    execute {
        // Ads run through the Google Ads Mobile SDK, obfuscated by R8.

        // region Fullscreen ads
        //
        // The interstitial and app-open wrappers each expose a show(Activity)
        // that presents the fullscreen ad (it forwards to an internal display
        // helper and returns void). Resolve each wrapper class by its unique
        // retained parameter-name string, then turn show(Activity) into a no-op
        // so no fullscreen ad is ever presented. Returning void keeps callers
        // valid, so nothing crashes.
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
        // endregion

        // region Banner / MPU ads
        //
        // Banners render into SDK ad-views that size themselves to the ad in
        // onMeasure: the FrameLayout banner/MPU container and the GmaWebView that
        // draws web/MRAID creatives. Force each onMeasure to report a zero-size
        // measure so the view - and therefore the banner - collapses to nothing.
        // setMeasuredDimension must still be called or the framework throws, so
        // this measures 0x0 rather than simply returning. Both onMeasure methods
        // have free low registers, so v0 is safe to use here.
        listOf(
            BannerAdViewFingerprint,
            BannerWebViewFingerprint,
        ).forEach { anchor ->
            Fingerprint(
                definingClass = anchor.originalClassDef.type,
                name = "onMeasure",
                returnType = "V",
                parameters = listOf("I", "I"),
            ).method.addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    invoke-virtual { p0, v0, v0 }, Landroid/view/View;->setMeasuredDimension(II)V
                    return-void
                """
            )
        }
        // endregion
    }
}
