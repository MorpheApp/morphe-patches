/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.startpage

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

internal object ColdStartUpFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("FEmusic_library_sideloaded_tracks"),
        string("FEmusic_home")
    )
)

/**
 * 9.26+
 * Checks if a library page opens inside the Library tab:
 * `libraryPages.contains(browseId) && (libraryRootPages.contains(browseId) || "FEmusic_library_landing".equals(activeRootTag))`.
 */
internal object OpenInLibraryTabFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string("FEmusic_library_landing"),
        methodCall(
            definingClass = "Ljava/lang/String;",
            name = "equals",
            location = MatchAfterWithin(6)
        )
    )
)
