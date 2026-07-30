/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.ask

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

internal object AskButtonComposableFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("trailing_ask_button"),
        string("search_ask_icon"),
        string("search_ask_label")
    )
)
