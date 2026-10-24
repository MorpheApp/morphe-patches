/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Parse feedback with the native registry and chain its existing snackbar callback. */
internal fun installQueueUndo(context: BytecodePatchContext, callback: MethodReference) = with(context) {
    val endpointType = callback.parameterTypes.first().toString()
    val endpoint = mutableClassDefBy(endpointType)
    endpoint.interfaces.add(UNDO_COMMAND_INTERFACE)
    endpoint.addBridgeMethod("patch_parseQueueCommand", listOf("[B"), "Lcom/google/protobuf/MessageLite;", 2, """
        ${parseProtoInstructions(endpointType, bytes = "p1", result = "v0", registry = "v1")}
        return-object v0
    """)

    val modern = QueueSnackbarModernFactoryFingerprint.matchSingle()
    val listenerType = modern.instructionMatches.first().instruction.getReference<MethodReference>()!!.definingClass

    installQueueSaveConfirmationDuration(context)
    installQueuePlayerClose(this, endpointType, callback)

    val dispatcher = queueSnackbarDispatcherFingerprint(callback.definingClass).matchSingle()
        .instructionMatches.single().instruction.getReference<FieldReference>()!!

    // Modern buttons can carry service, navigation and command endpoints. Intercept
    // only the three dispatches supplied by this snackbar factory, not other lambda cases.
    val click = queueUndoClickFingerprint(listenerType, dispatcher, callback).matchSingle()
    click.instructionMatches.filterIndexed { index, _ -> index > 0 && index % 2 == 0 }.asReversed().forEach { call ->
        val registers = call.getInstruction<FiveRegisterInstruction>()
        val consumed = click.method.findFreeRegister(call.index)
        click.method.addInstructionsAtControlFlowLabel(
            call.index,
            """
                invoke-static {v${registers.registerD}}, $EXTENSION_CLASS->onQueueUndoCommand(Lcom/google/protobuf/MessageLite;)Z
                move-result v$consumed
                if-nez v$consumed, :local_undo_handled
            """,
            ExternalLabel("local_undo_handled", click.method.getInstruction(call.index + 1))
        )
    }
}
