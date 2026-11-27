/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.castadfree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal const val COPY_ON_WRITE_ARRAY_LIST = "Ljava/util/concurrent/CopyOnWriteArrayList;"

/**
 * A method of the MDX screen manager, whose screens the app lists in its Cast menu.
 */
internal object ScreenManagerFingerprint : Fingerprint(
    filters = listOf(
        string("<unknown ip address>")
    )
)

internal object ScreenManagerConstructorFingerprint : Fingerprint(
    classFingerprint = ScreenManagerFingerprint,
    name = "<init>",
)

/**
 * Adds a paired screen.
 */
internal object ScreenManagerAddScreenFingerprint : Fingerprint(
    classFingerprint = ScreenManagerFingerprint,
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            definingClass = COPY_ON_WRITE_ARRAY_LIST,
            name = "contains"
        )
    )
)

/**
 * Terminates the cloud channel: the connection of a session to the lounge on YouTube's servers.
 */
internal object CloudChannelTerminateFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("clientDisconnectReason")
    )
)

internal object CloudChannelStateFingerprint : Fingerprint(
    classFingerprint = CloudChannelTerminateFingerprint,
    returnType = "I",
    parameters = listOf(),
)

internal object CloudChannelSendFingerprint : Fingerprint(
    classFingerprint = CloudChannelTerminateFingerprint,
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf("L", "L"),
)

internal object CloudChannelDisconnectFingerprint : Fingerprint(
    classFingerprint = CloudChannelTerminateFingerprint,
    returnType = "V",
    parameters = listOf("L", "Z", "Z", "Lj\$/util/Optional;"),
)

/**
 * Handles the lounge messages of a cloud session. Its class is the listener of the cloud channel.
 */
internal object CloudSessionMessageHandlerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        string("firstVideoId")
    )
)

/**
 * Connects a cloud session to a screen: connect(screenInfo, playback, sessionState).
 */
internal object CloudSessionConnectFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L", "Lj\$/util/Optional;"),
    filters = listOf(
        string("setStatesParams"),
        string("remotePlayabilityStatusParams")
    )
)

/**
 * Opens a video in the watch page. While casting, a server flag makes the app play a video that is not
 * the one on the screen on the phone instead, and end the session.
 */
internal object PlayOnPhoneFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45693138L),
        methodCall(
            returnType = "Z",
            location = MatchAfterWithin(3)
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

/**
 * Constructor of the discovery request of a media route provider. The request is active while the Cast menu
 * of the app is open.
 */
internal object MediaRouteDiscoveryRequestConstructorFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(
        string("activeScan")
    )
)
