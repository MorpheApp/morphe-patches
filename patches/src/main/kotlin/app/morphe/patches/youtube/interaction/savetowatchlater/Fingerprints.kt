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

internal object EditPlaylistRequestConstructorFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(
        string("browse/edit_playlist"),
    ),
)

/**
 * Builds the edit playlist request proto. All playlist edits of the app go through this method,
 * including adding to and removing from Watch later (playlist id `WL`).
 */
internal object EditPlaylistRequestBuildFingerprint : Fingerprint(
    classFingerprint = EditPlaylistRequestConstructorFingerprint,
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        // Playlist id.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
        ),
        // Edit actions.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;",
        ),
    ),
)
