/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.accuweather.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * AccuWeather serves ads through the Google Ads Mobile SDK
 * (`com.google.android.libraries.ads.mobile.sdk`), which is renamed by R8.
 *
 * The interstitial ad wrapper keeps a retained Kotlin parameter-name string
 * ("internalInterstitialAd") in its constructor. That string is unique to the
 * class, so it resolves the wrapper whose `show(Activity)` presents the
 * fullscreen interstitial.
 */
internal object InterstitialAdClassFingerprint : Fingerprint(
    filters = listOf(
        string("internalInterstitialAd")
    )
)

/**
 * Same idea for the app-open ad wrapper, anchored by "internalAppOpenAd".
 */
internal object AppOpenAdClassFingerprint : Fingerprint(
    filters = listOf(
        string("internalAppOpenAd")
    )
)
