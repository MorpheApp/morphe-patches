/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.meteomedia.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * Matches the RevenueCat subscription interactor (obfuscated to `Lin/c;`) that
 * tracks whether the user owns the paid, ad-free entitlement.
 *
 * The class is identified by two log strings that only appear together in its
 * customer-info handler. It is used to resolve the class that owns the ad-free
 * gate getter, which every ad presenter checks before requesting an ad.
 */
internal object RevenueCatInteractorFingerprint : Fingerprint(
    filters = listOf(
        string("Customer info retrieved successfully: isPremium: "),
        string("Error getting customer info: "),
    )
)
