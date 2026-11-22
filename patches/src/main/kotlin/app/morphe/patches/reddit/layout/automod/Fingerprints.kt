/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5156
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.automod

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.Opcode

/**
 * Constructor of the comments loaded for a post. Every comment load goes through it,
 * before Reddit builds its comment tree (and its presentation models in older versions).
 */
internal object CommentsResultConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/CommentsResultWithSource;",
    name = "<init>",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            name = "comments",
            type = "Ljava/util/List;"
        )
    )
)
