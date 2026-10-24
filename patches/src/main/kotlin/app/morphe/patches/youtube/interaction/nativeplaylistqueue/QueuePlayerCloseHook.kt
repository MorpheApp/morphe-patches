/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patches.youtube.misc.loadvideo.MiniAppOpenYtContentCommandEndpointFingerprint
import app.morphe.patches.youtube.misc.playservice.is_21_32_or_greater
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Observe the user close before teardown; keep the original native listener and dismissal. */
internal fun installQueuePlayerClose(
    context: BytecodePatchContext,
    endpointType: String,
    dispatch: MethodReference
) = with(context) {
    val dismiss = MiniAppOpenYtContentCommandEndpointFingerprint.matchSingle()
        .instructionMatches.last().instruction.getReference<MethodReference>()!!

    // Before 21.32 the fling has a second accepted-dismiss branch. Hook every user
    // dismissal, preserving thresholds, branch targets and the native continuation,
    // without hooking programmatic closes that use the same dismiss method.
    val closes = listOf(
        queuePlayerCloseClickFingerprint(dismiss),
        if (is_21_32_or_greater) queuePlayerCloseFlingFingerprint(dismiss)
        else queuePlayerCloseFlingLegacyFingerprint(dismiss),
        queuePlayerCloseTouchFingerprint(dismiss)
    )
    closes.forEach { fingerprint ->
        val match = fingerprint.matchSingle()

        match.instructionMatches.asReversed().forEach { call ->
            match.method.addInstructionsAtControlFlowLabel(
                call.index,
                "invoke-static {}, $EXTENSION_CLASS->onQueuePlayerClosing()V"
            )
        }
    }

    // This dispatcher belongs to the activity, not the disposed playback backend.
    // Reading it remains valid after the watch view detaches. No listener is replaced.
    val controllerType = PlaylistControllerConstructorFingerprint.matchSingle().originalClassDef.type
    val dispatcher = playlistControllerDispatcherFingerprint(controllerType, dispatch.definingClass)
        .matchSingle().instructionMatches.first().instruction.getReference<FieldReference>()!!

    mutableClassDefBy(controllerType).addBridgeMethod("patch_showQueueFeedback", listOf("[B"), "V", 3, """
        ${parseProtoInstructions(endpointType, bytes = "p1", result = "v0", registry = "v1")}
        check-cast v0, $endpointType
        iget-object v1, p0, $dispatcher
        invoke-static {}, Ljava/util/Collections;->emptyMap()Ljava/util/Map;
        move-result-object v2
        invoke-interface {v1, v0, v2}, $dispatch
        return-void
    """)
}
