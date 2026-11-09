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
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.youtube.video.information.ChannelInformationFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object YouTubeOfflineVideoEndpointFingerprint : Fingerprint(
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

/** Finds the native function that converts Downloads progress to a card start time. */
internal object OfflineDownloadCardPositionFingerprint : Fingerprint(
    parameters = listOf("J", "J"),
    returnType = "F",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = "this",
            parameters = listOf("J", "J"),
            returnType = "I"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = "this",
            parameters = listOf("J", "J", "I"),
            returnType = "Z",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        opcode(Opcode.LONG_TO_FLOAT, location = MatchAfterImmediately())
    )
)

internal fun offlineDownloadLoadFingerprint(modelType: String, descriptorType: String) = Fingerprint(
    classFingerprint = ChannelInformationFingerprint,
    parameters = listOf(modelType, descriptorType),
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "this", parameters = listOf("L", descriptorType), returnType = "V"),
        string("loadVideo() called on LocalDirector in wrong state")
    )
)

internal fun offlineDownloadPlayerFingerprint(refreshMethod: MethodReference, modelType: String) = Fingerprint(
    definingClass = refreshMethod.definingClass,
    name = refreshMethod.name,
    parameters = refreshMethod.parameterTypes.map { it.toString() },
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = refreshMethod.parameterTypes.first().toString()),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(),
            returnType = modelType,
            location = MatchAfterImmediately()
        )
    )
)

internal fun offlineDownloadPositionApplyFingerprint(
    refreshMethod: MethodReference,
    playerType: String
) = Fingerprint(
    definingClass = refreshMethod.definingClass,
    name = refreshMethod.name,
    parameters = refreshMethod.parameterTypes.map { it.toString() },
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_STATIC, parameters = listOf(playerType, "J"), returnType = "V"),
        methodCall(definingClass = "this", parameters = listOf("Z", "I", playerType), returnType = "V")
    )
)

internal fun offlineDownloadDurationFingerprint(modelImplementation: String) = Fingerprint(
    definingClass = modelImplementation,
    parameters = listOf(),
    returnType = "J",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = "Ljava/util/concurrent/TimeUnit;",
            name = "SECONDS"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "this",
            parameters = listOf(),
            returnType = "I",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.INT_TO_LONG, location = MatchAfterImmediately()),
        methodCall(
            "Ljava/util/concurrent/TimeUnit;->toMillis(J)J",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Saves or removes a playlist, such as from the Download button of a playlist page header.
 */
internal object YouTubeOfflinePlaylistEndpointFingerprint : Fingerprint(
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
    classFingerprint = YouTubeOfflineVideoEndpointFingerprint,
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

/**
 * Reads the interaction logger from the context of a command, as the interaction logger interface.
 */
internal object InteractionLoggerContextFingerprint : Fingerprint(
    filters = listOf(
        string("com.google.android.libraries.youtube.logging.interaction_logger"),
        opcode(Opcode.CONST_CLASS, location = MatchAfterImmediately())
    )
)

/**
 * Extension method, that returns the interaction logger that logs nothing.
 */
internal object NoOpInteractionLoggerFingerprint : Fingerprint(
    definingClass = EXTENSION_IN_APP_DOWNLOADS_CLASS,
    name = "getNoOpInteractionLogger",
    returnType = "Ljava/lang/Object;",
    parameters = listOf()
)
