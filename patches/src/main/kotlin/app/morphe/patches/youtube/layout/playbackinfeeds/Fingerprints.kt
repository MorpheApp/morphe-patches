/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2261
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.playbackinfeeds

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Sets up the native 'Playback in feeds' setting.
 * The last instruction match is a call to the class that holds the mode in use.
 */
internal object PlaybackInFeedsSettingFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("inline_global_play_pause"),
        methodCall(
            definingClass = "Landroidx/preference/ListPreference;",
            parameters = listOf("[Ljava/lang/CharSequence;")
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "L"),
            returnType = "I"
        )
    )
)

/**
 * Returns the mode in use: 0 (off), 1 (Wi-Fi only) or 2 (always on).
 */
internal object PlaybackInFeedsGetModeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Ljava/util/concurrent/atomic/AtomicInteger;->get()I")
    )
)

/**
 * Saves the mode. Called by the native 'Playback in feeds' setting.
 */
internal object PlaybackInFeedsSetModeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I"),
    filters = listOf(
        methodCall(returnType = "Lcom/google/common/util/concurrent/ListenableFuture;")
    )
)
