/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.incognitokeyboard

import app.morphe.patcher.Fingerprint

internal object FrontpageApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/frontpage/FrontpageApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)
