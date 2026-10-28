/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object OfflineVideoEndpointFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Ljava/util/Map;",
        "L",
        "Ljava/lang/String", // Video ID
        "L",
    ),
    filters = listOf(
        anyInstruction(
            string("Unsupported Offline Video Action: "), // 21.14 and lower
            string("Unsupported Offline Video Action: %s") // 21.15+
        )
    )
)

/**
 * Saves or removes a playlist, such as from the Download button of a playlist page header.
 */
internal object OfflinePlaylistEndpointFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "L",
        "Ljava/util/Map;",
        "Ljava/lang/String;", // Playlist ID
        "L", // Action
    ),
    filters = listOf(
        anyInstruction(
            string("Unsupported Offline Playlist Action: "), // 20.x
            string("Unsupported Offline Playlist Action: %d") // 21.x
        )
    )
)

/**
 * Matches the offline video endpoint method, to find the call that saves a video with its offlineability renderer.
 * The quality picker and the Premium upsell of the video are both shown by the called method.
 */
internal object SaveOfflineVideoCallFingerprint : Fingerprint(
    classFingerprint = OfflineVideoEndpointFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Ljava/util/Map;",
        "L",
        "Ljava/lang/String",
        "L",
    ),
    filters = listOf(
        methodCall(
            parameters = listOf(
                "Ljava/lang/String;", // Video ID
                "L", // Offlineability renderer
                "L",
                "L",
                "L",
                "I"
            ),
            returnType = "V"
        )
    )
)

/**
 * Resolves a command with the first command mapping that supports it.
 */
internal object ResolveCommandFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        anyInstruction(
            string("Unknown command not resolved"), // 20.x
            string("Unknown command not resolved %s") // 21.x
        )
    )
)

/**
 * Response callback of the 'ypc/get_offline_upsell' endpoint.
 */
internal object OfflineUpsellResponseFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        checkCast("L"), // Response.
        literal(131899791), // Upsell renderer of the response.
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"), // Endpoint command.
        string("YpcGetOfflineUpsellResponse_videoIdKey")
    )
)

/**
 * Fetches the player response a video is saved with, and puts it into the cache.
 */
internal object OfflinePlayerResponseFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        string("Some error occurred when reading from the cache player response"),
        methodCall(
            parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            returnType = "Lcom/google/common/util/concurrent/ListenableFuture;"
        )
    )
)

/**
 * Updates the offline state of a saved video with the state of the 'player/refresh' response.
 * Matches two methods, one for each of the two ways the app refreshes saved videos.
 */
internal object UpdateOfflineVideoPolicyFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "L",
        "J", // Time of the refresh.
        "L", // Offline state.
        "L" // Video policy.
    ),
    filters = listOf(
        anyInstruction(
            string("[Offline] UpdateVideoPolicy failed for video "), // 20.x
            string("[Offline] UpdateVideoPolicy failed for video %s") // 21.x
        )
    )
)

/**
 * Creates a player response model from the bytes of a player response.
 */
internal fun playerResponseModelFromBytesFingerprint(playerResponseModel: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = playerResponseModel,
    parameters = listOf("[B", "J"),
    filters = listOf(
        methodCall(
            parameters = listOf("[B", "Lcom/google/protobuf/MessageLite;"),
            returnType = "Lcom/google/protobuf/MessageLite;"
        ),
        opcode(Opcode.CHECK_CAST) // Player response.
    )
)

internal fun playerResponseGetterFingerprint(playerResponseModelClass: String, playerResponse: String) = Fingerprint(
    definingClass = playerResponseModelClass,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = playerResponse,
    parameters = listOf()
)
