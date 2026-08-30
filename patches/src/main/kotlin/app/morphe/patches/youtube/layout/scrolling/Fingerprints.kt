/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2582
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.scrolling

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object SnappyRecyclerViewSetFlingLimitFingerprint : Fingerprint(
    accessFlags =  listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        checkCast("Lcom/google/android/apps/youtube/app/common/rendering/SnappyRecyclerView;"),
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "Lcom/google/android/apps/youtube/app/common/rendering/SnappyRecyclerView;",
            type = "Z"
        )
    )
)
