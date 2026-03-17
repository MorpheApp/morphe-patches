/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.music.layout.startpage

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

internal object ColdStartUpFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("FEmusic_library_sideloaded_tracks"),
        string("FEmusic_home")
    )
)