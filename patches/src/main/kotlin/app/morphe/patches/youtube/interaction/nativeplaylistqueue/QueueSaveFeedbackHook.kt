/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

/** Add a native response action only for the callback created by an owned queue save. */
internal fun installQueueSaveFeedback(context: BytecodePatchContext, endpointType: String) = with(context) {
    val callbackType = QueueSaveEditErrorFingerprint.matchSingle().originalClassDef.type
    val creation = queueSaveEditCreationFingerprint(callbackType, endpointType).matchSingle()
    val success = queueSaveEditSuccessFingerprint(callbackType).matchSingle()
    val responseType = success.originalMethod.parameterTypes.single().toString()
    val callback = mutableClassDefBy(callbackType)
    callback.interfaces.add(SAVE_CALLBACK_INTERFACE)
    val selection = ImmutableField(callbackType, "patch_queueSaveSelection", SAVE_SELECTION_CLASS,
        AccessFlags.PRIVATE.value, null, null, null).toMutable()
    callback.fields.add(selection)
    callback.addBridgeMethod("patch_getQueueSaveSelection", emptyList(), SAVE_SELECTION_CLASS, 1, """
        iget-object v0, p0, $selection
        return-object v0
    """)

    callback.addBridgeMethod("patch_setQueueSaveSelection", listOf(SAVE_SELECTION_CLASS), "V", 0, """
        iput-object p1, p0, $selection
        return-void
    """)

    callback.addBridgeMethod("patch_parseQueueSaveResult", listOf("[B"), "Lcom/google/protobuf/MessageLite;", 2, """
        ${parseProtoInstructions(responseType, bytes = "p1", result = "v0", registry = "v1")}
        return-object v0
    """)

    val construct = creation.instructionMatches.single()
    val reference = construct.instruction.getReference<MethodReference>()!!
    check(reference.definingClass == callbackType && reference.name == "<init>")
    val receiver = when (val instruction = construct.instruction) {
        is RegisterRangeInstruction -> instruction.startRegister
        is FiveRegisterInstruction -> instruction.registerC
        else -> error("Native playlist queue: unsupported save callback constructor encoding")
    }

    val endpoint = creation.originalMethod.p0Register + 1
    check(receiver <= 15 && endpoint <= 15) { "Native playlist queue: save context requires low registers" }

    // The constructor has completed. The original endpoint parameter is still
    // live here, before the resolver proceeds to either native request branch.
    creation.method.addInstructionsAtControlFlowLabel(construct.index + 1, """
        invoke-static {v$receiver, v$endpoint}, $SAVE_ACTIONS_CLASS->onEditCreated(${SAVE_CALLBACK_INTERFACE}Lcom/google/protobuf/MessageLite;)V
    """)

    success.method.addInstructionsAtControlFlowLabel(0, """
        invoke-static/range {p0 .. p1}, $SAVE_ACTIONS_CLASS->onEditResult(${SAVE_CALLBACK_INTERFACE}Lcom/google/protobuf/MessageLite;)Lcom/google/protobuf/MessageLite;
        move-result-object p1
        check-cast p1, $responseType
    """)
}

/** Use the native builder and scheduler for owned Save and queue-clear confirmations. */
internal fun installQueueSaveConfirmationDuration(context: BytecodePatchContext) = with(context) {
    val builderType = QueueSnackbarModernFactoryFingerprint.originalMethod.returnType
    val durationSetter = queueSnackbarDurationFingerprint(builderType).matchSingle()
        .instructionMatches[4].instruction.getReference<MethodReference>()!!

    // Keep the input renderer even when the native factory reuses its parameters.
    QueueSnackbarModernFactoryFingerprint.method.cloneParameters()
    QueueSnackbarModernFactoryFingerprint.clearMatch()
    val factory = QueueSnackbarModernFactoryFingerprint.matchSingle()

    val exit = factory.instructionMatches.last()
    val builder = exit.getInstruction<OneRegisterInstruction>().registerA
    val duration = factory.method.findFreeRegister(exit.index)
    check(builder <= 15 && duration <= 15) { "Native playlist queue: snackbar duration requires low registers" }
    factory.method.addInstructionsAtControlFlowLabel(exit.index, """
        invoke-static/range {p1 .. p1}, $SAVE_FEEDBACK_CLASS->confirmationDuration(Lcom/google/protobuf/MessageLite;)I
        move-result v$duration
        if-lez v$duration, :native_duration
        invoke-virtual {v$builder, v$duration}, $durationSetter

        :native_duration
        nop
    """)
}
