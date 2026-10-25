/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.savetowatchlater

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Finds the constructor of the edit playlist request class, identified by the hardcoded
 * request URL. Saves to Watch later use a playlist id of `WL`, and all entry points
 * that save to a playlist funnel through this single request builder.
 */
internal object EditPlaylistRequestClassFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(
        string("browse/edit_playlist"),
    ),
)

/**
 * Finds the method that builds the edit playlist request, and the playlist id field
 * that is read to build it. The method in the stock app is obfuscated.
 */
internal object EditPlaylistBuildMethodFingerprint : Fingerprint(
    classFingerprint = EditPlaylistRequestClassFingerprint,
    name = "a",
    returnType = "L",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;",
        ),
    ),
)
