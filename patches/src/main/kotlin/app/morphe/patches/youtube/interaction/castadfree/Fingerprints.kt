/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.castadfree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterLast
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
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
    filters = listOf(
        opcode(Opcode.RETURN_VOID, location = MatchAfterLast())
    )
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
 * Removes a paired screen.
 */
internal object ScreenManagerRemoveScreenFingerprint : Fingerprint(
    classFingerprint = ScreenManagerFingerprint,
    returnType = "V",
    parameters = { ScreenManagerAddScreenFingerprint.originalMethod.parameterTypes },
    filters = listOf(
        methodCall(
            definingClass = COPY_ON_WRITE_ARRAY_LIST,
            name = "remove"
        ),
        methodCall(
            definingClass = COPY_ON_WRITE_ARRAY_LIST,
            name = "remove",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Constructor of a paired screen: screen(screenInfo, isOnline).
 */
internal object ScreenConstructorFingerprint : Fingerprint(
    definingClass = { ScreenManagerAddScreenFingerprint.originalMethod.parameterTypes.first() },
    name = "<init>",
    parameters = listOf("L", "Z"),
)

/**
 * Synthetic constructor of the screen information, with a mask of the parameters that get their default:
 * screenInfo(pairingInfo, name, screenId, loungeDeviceId, list, id, id, loungeToken, defaultsMask).
 */
internal object ScreenInfoConstructorFingerprint : Fingerprint(
    definingClass = { ScreenConstructorFingerprint.originalMethod.parameterTypes.first() },
    name = "<init>",
    parameters = listOf("L", "Ljava/lang/String;", "L", "L", "Ljava/util/List;", "L", "L", "L", "I"),
)

/**
 * Builds the routes of the media route provider of the screens, which the Cast menu lists.
 */
internal object MediaRouteProviderRoutesFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = { ScreenManagerFingerprint.originalClassDef.type },
            type = COPY_ON_WRITE_ARRAY_LIST
        ),
        // The screen of the route: the next element of the iterator over the screens, cast to its class.
        methodCall(
            definingClass = "Ljava/util/Iterator;",
            name = "next"
        ),
        opcode(Opcode.CHECK_CAST, location = MatchAfterWithin(5)),
        string("YouTube"),
        // The route builder, and its description setter.
        opcode(Opcode.NEW_INSTANCE, location = MatchAfterWithin(5)),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            returnType = "V",
            parameters = listOf("Ljava/lang/String;")
        )
    )
)

/**
 * onDiscoveryRequestChanged(request) of the media route provider of the screens.
 */
internal object MediaRouteProviderDiscoveryRequestFingerprint : Fingerprint(
    classFingerprint = MediaRouteProviderRoutesFingerprint,
    returnType = "V",
    parameters = { listOf(MediaRouteDiscoveryRequestConstructorFingerprint.originalClassDef.type) },
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
 * connect(listener, reason) of the cloud channel, which sets the listener and connects. YouTube 21.47 and newer.
 */
internal object CloudChannelConnectFingerprint : Fingerprint(
    classFingerprint = CloudChannelTerminateFingerprint,
    returnType = "V",
    parameters = { listOf(CloudSessionMessageHandlerFingerprint.originalClassDef.interfaces.single(), "I") },
)

/**
 * setListener(listener) of the cloud channel, which sets the listener and calls connect().
 * Before YouTube 21.47.
 */
internal object CloudChannelSetListenerFingerprint : Fingerprint(
    classFingerprint = CloudChannelTerminateFingerprint,
    returnType = "V",
    parameters = { listOf(CloudSessionMessageHandlerFingerprint.originalClassDef.interfaces.single()) },
    filters = listOf(
        methodCall(
            definingClass = "this",
            returnType = "V",
            parameters = listOf()
        )
    )
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
        string("remotePlayabilityStatusParams"),
        // The session sets the connect parameters of the channel before it connects.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = { CloudChannelTerminateFingerprint.originalClassDef.type }
        )
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
