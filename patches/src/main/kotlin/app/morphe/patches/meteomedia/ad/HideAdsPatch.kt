/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.meteomedia.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.meteomedia.shared.Constants.COMPATIBILITY_METEOMEDIA
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Removes banner and interstitial advertisements throughout the app.",
) {
    compatibleWith(COMPATIBILITY_METEOMEDIA)

    execute {
        // Every ad request in the app is gated behind a RevenueCat subscription
        // check that reports whether the user owns the paid, ad-free entitlement
        // (RevenueCatInteractor "isPremium", obfuscated to `Lin/c;->g()Z`).
        //
        // Ad presenters - the banner loader, the interstitial loader, the
        // "remove ads" upsell, etc. - all read this getter and skip requesting an
        // ad when it returns true. Force it to always return true so the app
        // behaves exactly as it would for a paid, ad-free subscriber, without
        // requesting or laying out any ad.
        //
        // Within the interactor the getter is the only public, parameterless
        // method that returns a boolean, so the class + signature resolve it
        // unambiguously even though its name is obfuscated.
        Fingerprint(
            definingClass = RevenueCatInteractorFingerprint.originalClassDef.type,
            accessFlags = listOf(AccessFlags.PUBLIC),
            returnType = "Z",
            parameters = listOf(),
        ).method.returnEarly(true)
    }
}
