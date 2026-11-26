/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2556
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.album

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal

/**
 * Case of the WatchNextResponse oneof the watch next model reads its playlist from.
 */
private const val WATCH_NEXT_PLAYLIST_CASE = 51779735L

/**
 * Constructor of the watch next model, which every watch next response the app shows is built
 * into, whether it came from the network, the streaming watch response or the offline store.
 * The only parameter is the WatchNextResponse.
 */
internal object WatchNextResponseModelConstructorFingerprint : Fingerprint(
    interfaces = listOf("Landroid/os/Parcelable;"),
    name = "<init>",
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        literal(WATCH_NEXT_PLAYLIST_CASE)
    )
)
