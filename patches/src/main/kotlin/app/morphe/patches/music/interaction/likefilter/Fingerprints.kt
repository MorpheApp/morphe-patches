/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4929
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.likefilter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * The success callback of "Play next" and "Add to queue". The first filter reads the list of
 * songs that are added to the queue from the server response.
 */
internal object QueueEnqueueSuccessFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        checkCast("L"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/util/List;"),
        string("MusicQueueCallback onSuccess, numItems: %d, insertIndex: %d"),
    )
)
