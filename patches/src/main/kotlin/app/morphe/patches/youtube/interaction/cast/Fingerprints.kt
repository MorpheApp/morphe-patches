/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.cast

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * When a different video is selected while casting, the flag disconnects the Cast session
 * and plays the video on the phone, unless the video is from the queue of the TV.
 */
internal object CastDisconnectOnVideoSelectFeatureFlagFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        literal(45693138L)
    )
)

/**
 * When the player is closed or minimized while casting, the flag disconnects the Cast session.
 * The flag is also read by the minimize button of the player, which then shows a close icon.
 */
internal object CastDisconnectOnPlayerCloseFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45798912L)
    )
)

/**
 * Picks where the player goes when swiped down.
 * While casting, the player is dismissed into the You tab instead of minimized.
 */
internal object CastSwipeDownTargetFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
    parameters = listOf("I"),
    filters = listOf(
        literal(45783248L),
        literal(4096L)
    )
)
