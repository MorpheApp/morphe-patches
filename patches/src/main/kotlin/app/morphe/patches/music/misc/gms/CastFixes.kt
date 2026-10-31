/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3899
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.gms

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patches.music.misc.playservice.is_9_30_or_greater
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

// Fixes for casting through a GmsCore with Cast support.

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/CastFixesPatch;"

/**
 * On a phone that also has the real Play Services, its MediaRouter2 provider lists every cast
 * device a second time. Picking one of those makes Play Services connect to the device too, next
 * to GmsCore. Its routes and route provider services are filtered out of the app's media router.
 */
internal fun BytecodePatchContext.hidePlayServicesCastRoutes() {
    MediaRouteProviderScanFingerprint.let {
        it.method.apply {
            // Last match first, so the earlier index is still valid.
            listOf(it.instructionMatches[5].index, it.instructionMatches[2].index).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->filterRouteProviderServices(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """
                )
            }
        }
    }

    MediaRoute2ProviderRefreshRoutesFingerprint.let {
        it.method.apply {
            val index = it.instructionMatches.last().index
            val register = getInstruction<OneRegisterInstruction>(index).registerA

            addInstructions(
                index + 1,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->filterRoutes(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$register
                """
            )
        }
    }
}

/**
 * Moving a song within the queue while casting (Play next on a queued song, dragging it) is
 * skipped by the receiver, in the unpatched app too. With the server flag on, the move is not sent
 * to the receiver ('moveVideo'); a server side REORDER edit is sent instead, which the receiver
 * does not apply. The flag is read as off in both places: the move goes to the receiver again, and
 * the server edit, which would now conflict with the receiver's own update, fail and roll the
 * phone's queue back, is not sent.
 *
 * Before 9.30 the move is always sent to the receiver and there is no flag.
 */
internal fun BytecodePatchContext.sendQueueMovesToReceiver() {
    if (!is_9_30_or_greater) return

    MdxQueueMoveItemFingerprint.method.insertLiteralOverride(MDX_QUEUE_MOVE_SERVER_EDIT_FLAG, false)
    MdxQueueReorderServerEditFingerprint.method.insertLiteralOverride(MDX_QUEUE_MOVE_SERVER_EDIT_FLAG, false)
}

/**
 * When the app returns to the foreground it restores the last queue, which loads and plays the
 * last song on the phone while the cast device keeps playing too. Skipped while casting.
 */
internal fun BytecodePatchContext.skipQueueRestorationWhileCasting() {
    RestoreQueueFromHomepageFingerprint.let {
        it.method.apply {
            // Skip inside the run-once guard, so the skipped restoration counts as having run
            // and does not come back after the cast ends.
            val index = it.instructionMatches.last().index + 1
            val register = findFreeRegister(index)

            addInstructionsWithLabels(
                index,
                """
                    invoke-static { }, $EXTENSION_CLASS->skipQueueRestoration()Z
                    move-result v$register
                    if-eqz v$register, :restore
                    return-void
                    :restore
                    nop
                """
            )
        }
    }
}
