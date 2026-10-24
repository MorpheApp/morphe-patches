/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Publish queue capabilities before native notification actions and playback state are built. */
internal fun installPlaylistNotificationControls(context: BytecodePatchContext, ownerType: String) = with(context) {
    val modelType = NotificationSkipActionsFingerprint.matchSingle()
        .instructionMatches.first().instruction.getReference<FieldReference>()!!.type
    val setter = notificationCapabilitiesSetterFingerprint(modelType).matchSingle().originalMethod

    // The original adapter returns V. The newer adapter returns its change flags.
    val adapters = notificationCapabilitiesFingerprint(setter).matchAll()
    check(adapters.map { it.originalMethod.returnType }.sorted() == listOf("I", "V")) {
        "Native playlist queue: expected both native notification capability adapters"
    }

    val playbackState = notificationPlaybackStateFingerprint(
        adapters.single { it.originalMethod.returnType == "V" }.originalClassDef.type
    ).matchSingle().originalMethod

    adapters.forEach { match ->
        val adapterType = match.originalClassDef.type
        val adapter = mutableClassDefBy(adapterType)
        val constructor = notificationAdapterConstructorFingerprint(adapterType, ownerType).matchSingle()
        val owner = constructor.instructionMatches.single().instruction.getReference<FieldReference>()!!
        val computation = match.originalMethod
        val dispatch = if (computation.returnType == "I") {
            notificationCapabilitiesDispatchFingerprint(computation).matchAll().map {
                it.instructionMatches.last().instruction.getReference<MethodReference>()!!
            }.distinct().single()
        } else null

        adapter.interfaces.add(NOTIFICATION_CONTROLS_INTERFACE)
        adapter.addBridgeMethod("patch_getQueueMediaControls", emptyList(), MEDIA_CONTROLS_INTERFACE, 1, """
            iget-object v0, p0, $owner
            return-object v0
        """)

        // Recompute from the original adapter inputs on every refresh. Releasing
        // the queue therefore restores native capabilities without caching flags.
        adapter.addBridgeMethod("patch_refreshQueueNotification", emptyList(), "V", 1, """
            invoke-direct {p0}, $computation
            ${if (dispatch == null) "" else "move-result v0\ninvoke-direct {p0, v0}, $dispatch"}
            return-void
        """)

        constructor.method.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            constructor.method.addInstructionsAtControlFlowLabel(
                index,
                "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onNotificationControlsCreated($NOTIFICATION_CONTROLS_INTERFACE)V"
            )
        }

        // NEW clears the model's skip flags after the sequencer commits selection.
        // In background playback there need not be another watch-screen update.
        // Recompute only after the original state handler finishes clearing its
        // model and delegates; preserve its receiver because native code reuses p0.
        val stateMethod = notificationPlaybackStateFingerprint(adapterType, playbackState).matchSingle()
            .method.cloneParameters()
        stateMethod.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            stateMethod.addInstructionsAtControlFlowLabel(
                index,
                "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onNotificationPlaybackChanged($NOTIFICATION_CONTROLS_INTERFACE)V"
            )
        }

        // Both original computations reuse p0 before the setter. Keep the receiver
        // and match the call again after cloning instead of retaining its index.
        match.method.cloneParameters()
        val update = notificationCapabilitiesFingerprint(setter, adapterType).matchSingle()
        val method = update.method
        val index = update.instructionMatches.single().index
        val call = method.getInstruction<FiveRegisterInstruction>(index)
        val previous = call.registerD
        val next = call.registerE
        val capabilities = method.findFreeRegister(index)
        method.addInstructionsAtControlFlowLabel(index, """
            invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->notificationNavigationAvailability($NOTIFICATION_CONTROLS_INTERFACE)I
            move-result v$capabilities
            if-ltz v$capabilities, :native_capabilities
            and-int/lit8 v$previous, v$capabilities, 0x1
            ushr-int/lit8 v$next, v$capabilities, 0x1

            :native_capabilities
            nop
        """)
    }
}
