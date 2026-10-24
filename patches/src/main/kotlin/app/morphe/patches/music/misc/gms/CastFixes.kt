/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.gms

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// Fixes for casting through a GmsCore with Cast support, applied with the "Enable Google Cast" option.

private const val CAST_ROUTES_CLASS = "Lapp/morphe/extension/music/patches/CastRoutesPatch;"
private const val CAST_STATE_CLASS = "Lapp/morphe/extension/music/patches/CastStatePatch;"

// region Cast routes of the real Play Services

/** androidx RegisteredMediaRouteProviderWatcher.scan(). */
internal object MediaRouteProviderScanFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf(
        "android.media.MediaRoute2ProviderService",
        "android.media.MediaRouteProviderService",
    )
)

/** androidx MediaRoute2Provider.refreshRoutes(). */
internal object MediaRoute2ProviderRefreshFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf(
        "androidx.mediarouter.media.KEY_ORIGINAL_ROUTE_ID",
        "Cannot find the original route Id. route=",
    )
)

/**
 * On a phone that also has the real Play Services, its MediaRouter2 provider lists every cast
 * device a second time. Picking one of those makes Play Services connect to the device too, next
 * to GmsCore. Its routes and route provider services are filtered out of the app's media router.
 */
internal fun BytecodePatchContext.hidePlayServicesCastRoutes() {
    MediaRouteProviderScanFingerprint.method.apply {
        val indices = instructions.withIndex().filter { (_, instruction) ->
            val reference = instruction.getReference<MethodReference>()
            reference?.definingClass == "Landroid/content/pm/PackageManager;" &&
                    reference.name == "queryIntentServices"
        }.map { it.index }
        if (indices.isEmpty()) throw IllegalStateException("queryIntentServices not found")
        indices.forEach { index ->
            val call = getInstruction<FiveRegisterInstruction>(index)
            replaceInstruction(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, " +
                        "$CAST_ROUTES_CLASS->queryProviderServices(" +
                        "Landroid/content/pm/PackageManager;Landroid/content/Intent;I)Ljava/util/List;"
            )
        }
    }

    MediaRoute2ProviderRefreshFingerprint.method.apply {
        // R8 may route the framework call through an API outline that takes the router.
        val index = instructions.indexOfFirst { instruction ->
            val reference = instruction.getReference<MethodReference>() ?: return@indexOfFirst false
            reference.returnType == "Ljava/util/List;" && (
                    reference.definingClass == "Landroid/media/MediaRouter2;" && reference.name == "getRoutes" ||
                            reference.parameterTypes.map { it.toString() } == listOf("Landroid/media/MediaRouter2;")
                    )
        }
        if (index < 0) throw IllegalStateException("MediaRouter2.getRoutes not found")
        val call = getInstruction<FiveRegisterInstruction>(index)
        replaceInstruction(
            index,
            "invoke-static { v${call.registerC} }, " +
                    "$CAST_ROUTES_CLASS->getRoutes(Landroid/media/MediaRouter2;)Ljava/util/List;"
        )
    }
}

// endregion

// region Queue moves

/** Server flag that replaces the queue move sent to the receiver with a server side edit. */
private const val MOVE_FLAG = 45418016L

/** MdxPlaybackQueue.moveItemInList(fromList, fromIndex, toList, toIndex). */
internal object MdxQueueMoveItemFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I", "I", "I", "I"),
    strings = listOf("moveItemFromQueueListToQueueList")
)

/** The server side queue edit for a move (ACTION_MOVE_VIDEO_AFTER, edit type REORDER). */
internal object QueueReorderServerEditFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("REORDER"),
    filters = listOf(literal(MOVE_FLAG))
)

/**
 * Moving a song within the queue while casting (Play next on a queued song, dragging it) is
 * skipped by the receiver, in the unpatched app too. With the server flag on, the move is not sent
 * to the receiver ('moveVideo'); a server side REORDER edit is sent instead, which the receiver
 * does not apply. The flag is read as off in both places: the move goes to the receiver again, and
 * the server edit, which would now conflict with the receiver's own update, fail and roll the
 * phone's queue back, is not sent.
 */
internal fun BytecodePatchContext.sendQueueMovesToReceiver() {
    MdxQueueMoveItemFingerprint.method.insertLiteralOverride(MOVE_FLAG, false)
    QueueReorderServerEditFingerprint.method.insertLiteralOverride(MOVE_FLAG, false)
}

// endregion

// region Queue restoration

/** QueueRestorationController.restoreFromHomepage, run when the app returns to the foreground. */
internal object RestoreQueueFromHomepageFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("restoreFromHomepage(currentPlayerViewMode: %s)")
)

/**
 * When the app returns to the foreground it restores the last queue, which loads and plays the
 * last song on the phone while the cast device keeps playing too. Skipped while casting.
 */
internal fun BytecodePatchContext.skipQueueRestorationWhileCasting() {
    RestoreQueueFromHomepageFingerprint.method.apply {
        // The restoration runs once, behind an AtomicBoolean. Skipping it inside that guard counts
        // as having run, so it does not come back later, after the cast ends.
        val guardIndex = instructions.indexOfFirst {
            val reference = it.getReference<MethodReference>()
            reference?.definingClass == "Ljava/util/concurrent/atomic/AtomicBoolean;" &&
                    reference.name == "compareAndSet"
        }
        val insertIndex = if (guardIndex < 0) {
            0
        } else {
            (guardIndex until instructions.size).first { instructions[it].opcode == Opcode.IF_EQZ } + 1
        }
        val register = findFreeRegister(insertIndex)
        addInstructionsWithLabels(
            insertIndex,
            """
                invoke-static { }, $CAST_STATE_CLASS->skipQueueRestoration()Z
                move-result v$register
                if-eqz v$register, :restore
                return-void
            """,
            ExternalLabel("restore", getInstruction(insertIndex))
        )
    }
}

// endregion
