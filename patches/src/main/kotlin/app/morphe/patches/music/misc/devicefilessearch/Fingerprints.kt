/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4725
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.devicefilessearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal object DeviceFilesSearchFingerprint : Fingerprint(
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf("Ljava/lang/String;", "[Ljava/lang/String;"),
    filters = listOf(
        string("thing_proto")
    )
)

internal object SearchFuturesFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/util/concurrent/Future;"),
    filters = listOf(
        string("Future was expected to be done: %s")
    )
)

internal object SubmitSearchFingerprint : Fingerprint(
    classFingerprint = SearchFuturesFingerprint,
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf("Ljava/util/concurrent/Callable;", "Ljava/util/concurrent/Executor;"),
    filters = listOf(
        opcode(Opcode.NEW_INSTANCE),
        methodCall(
            name = "<init>",
            parameters = listOf("Ljava/util/concurrent/Callable;"),
            location = MatchAfterImmediately()
        ),
        methodCall(definingClass = "Ljava/util/concurrent/Executor;", name = "execute")
    )
)
