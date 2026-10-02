/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3446
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.playlisttracktap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Resolves a command with every registered command resolver.
 * Every command a tap sends is routed through here before it is resolved.
 */
internal object CommandResolverFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        string("Unknown command not resolved")
    )
)

/**
 * Builds a command holding a watch endpoint.
 * Parameters are the video id, playlist id, playlist index, start time,
 * player params, params and whether the watch endpoint is a music video.
 */
internal object WatchEndpointCommandBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "I",
        "F",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z"
    )
)

/**
 * Resolves the queue add endpoint, which the long press 'Add to queue' menu item sends.
 */
internal object QueueAddEndpointCommandFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    strings = listOf("Move performed instead of add while casting.")
)
