/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.ifunny.premium

import app.morphe.patcher.Fingerprint

internal object IsUserPremiumFingerprint : Fingerprint(
    definingClass = "Lmobi/ifunny/rest/content/User;",
    name = "isUserPremium",
    returnType = "Z",
    parameters = listOf()
)
