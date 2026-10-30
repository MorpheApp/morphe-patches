/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3899
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.gms

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/** Server flag that replaces the queue move sent to the cast receiver with a server side edit. */
internal const val MDX_QUEUE_MOVE_SERVER_EDIT_FLAG = 45418016L

private val queryIntentServicesFilter
    get() = methodCall(
        definingClass = "Landroid/content/pm/PackageManager;",
        name = "queryIntentServices"
    )

/**
 * androidx RegisteredMediaRouteProviderWatcher.scan(), with the MediaRouter2
 * and legacy route provider service queries.
 */
internal object MediaRouteProviderScanFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("android.media.MediaRoute2ProviderService"),
        queryIntentServicesFilter,
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("android.media.MediaRouteProviderService"),
        queryIntentServicesFilter,
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
    )
)

/**
 * androidx MediaRoute2Provider.refreshRoutes(). MediaRouter2.getRoutes() is called through an
 * R8 API outline that takes the router.
 */
internal object MediaRoute2ProviderRefreshRoutesFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf(
        "androidx.mediarouter.media.KEY_ORIGINAL_ROUTE_ID",
        "Cannot find the original route Id. route=",
    ),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Landroid/media/MediaRouter2;"),
        methodCall(
            parameters = listOf("Landroid/media/MediaRouter2;"),
            returnType = "Ljava/util/List;",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
    )
)

/** MdxPlaybackQueue.moveItemInList(fromList, fromIndex, toList, toIndex). */
internal object MdxQueueMoveItemFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I", "I", "I", "I"),
    strings = listOf("moveItemFromQueueListToQueueList"),
    filters = listOf(
        literal(MDX_QUEUE_MOVE_SERVER_EDIT_FLAG)
    )
)

/** The server side queue edit for a move (ACTION_MOVE_VIDEO_AFTER, edit type REORDER). */
internal object MdxQueueReorderServerEditFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("REORDER"),
    filters = listOf(
        literal(MDX_QUEUE_MOVE_SERVER_EDIT_FLAG)
    )
)

/**
 * QueueRestorationController.restoreFromHomepage(), run when the app returns to the foreground.
 * The restoration runs only once, behind an AtomicBoolean.
 */
internal object RestoreQueueFromHomepageFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("restoreFromHomepage(currentPlayerViewMode: %s)"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/concurrent/atomic/AtomicBoolean;",
            name = "compareAndSet"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
    )
)
