/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.methodCall
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.matchSingle
import app.morphe.patches.youtube.misc.playservice.is_21_31_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_44_or_greater
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val QUEUE_CLOSE = "$EXTENSION_CLASS->onQueuePlayerClosing()V"

/** Observe the user close before teardown; keep the original native listener and dismissal. */
internal fun installQueuePlayerClose(
    endpointType: String,
    dispatch: MethodReference
) {
    hookUserMiniplayerDismissal(QUEUE_CLOSE)

    // This dispatcher belongs to the activity, not the disposed playback backend.
    // Reading it remains valid after the watch view detaches. No listener is replaced.
    val controllerType = PlaylistControllerConstructorFingerprint.originalClassDef.type
    val dispatcher = playlistControllerDispatcherFingerprint(controllerType, dispatch.definingClass)
        .instructionMatches.first().fieldReference

    mutableClassDefBy(controllerType).addPatchInterface(
        CONTROLLER_INTERFACE,
        "patch_showQueueFeedback",
        """
            ${parseProtoInstructions(endpointType, bytes = "p1", result = "v0", registry = "v1")}
            check-cast v0, $endpointType
            iget-object v1, p0, $dispatcher
            invoke-static {}, Ljava/util/Collections;->emptyMap()Ljava/util/Map;
            move-result-object v2
            invoke-interface {v1, v0, v2}, $dispatch
            return-void
        """
    )
}

internal fun hookUserMiniplayerDismissal(
    extensionMethod: String,
    observeTouchMethod: String? = null,
    touchDismissalMethod: String = extensionMethod,
) {
    // Before 21.31 the fling has a second accepted-dismiss branch. Hook every user
    // dismissal, preserving thresholds, branch targets and the native continuation,
    // without hooking programmatic closes that use the same dismiss method.
    val closes = listOf(QueuePlayerCloseClickFingerprint) +
        if (is_21_44_or_greater) listOf(
            QueuePlayerControllerFlingFingerprint,
            QueuePlayerControllerReleaseFingerprint
        ) else listOf(
            if (is_21_31_or_greater) QueuePlayerCloseFlingFingerprint
            else QueuePlayerCloseFlingLegacyFingerprint,
            QueuePlayerCloseTouchFingerprint
        )

    closes.forEach { fingerprint ->
        val match = fingerprint.matchSingle()
        val dismiss = match.instructionMatches.first().methodReference
        val observesTouch = fingerprint == QueuePlayerCloseTouchFingerprint && observeTouchMethod != null
        val dismissalHook = if (observesTouch) touchDismissalMethod else extensionMethod
        match.method.findInstructionIndicesReversedOrThrow(methodCall(reference = { dismiss })).forEach { index ->
            // The queue's Undo hook must still be immediately before native teardown.
            val previous = match.method.getInstruction(index - 1) as? ReferenceInstruction
            val insertIndex = if (previous?.reference?.toString() == QUEUE_CLOSE) index - 1 else index
            match.method.addInstructionsAtControlFlowLabel(
                insertIndex,
                "invoke-static {}, $dismissalHook"
            )
        }
        if (observesTouch) {
            val motionEvent = if (MethodUtil.isStatic(match.originalMethod)) "p1" else "p2"
            match.method.addInstruction(0, "invoke-static/range {$motionEvent .. $motionEvent}, $observeTouchMethod")
        }
    }
}
