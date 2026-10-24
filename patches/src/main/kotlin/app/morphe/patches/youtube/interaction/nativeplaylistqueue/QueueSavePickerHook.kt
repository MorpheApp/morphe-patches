/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.p0Register
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val LIST = "Ljava/util/List;"

/** Supply a real batch only to a picker launched by our queue header; keep native callbacks. */
internal fun installQueueSavePicker(context: BytecodePatchContext, endpointType: String) = with(context) {
    installQueueSaveFeedback(context, endpointType)

    val request = QueueSaveRequestFingerprint.matchSingle().originalClassDef
    val picker = queueSavePickerFingerprint(request.type).matchSingle()
    val factory = queueSavePickerFactoryFingerprint(picker.originalClassDef.type, endpointType).matchSingle()

    val serialize = factory.instructionMatches[1]
    val serializeCall = serialize.getInstruction<FiveRegisterInstruction>()
    val putArguments = factory.instructionMatches.last().getInstruction<FiveRegisterInstruction>()
    check(serializeCall.registerC == factory.originalMethod.p0Register + 1 &&
            serializeCall.registerC <= 15 && putArguments.registerC <= 15) {
        "Native playlist queue: picker must serialize its original endpoint after constructing arguments"
    }

    // p1 is overwritten by the serialized bytes immediately afterwards. Transfer the
    // owned snapshot before that write, on both the existing/new Bundle branches.
    factory.method.addInstructionsAtControlFlowLabel(serialize.index, """
        invoke-static {v${serializeCall.registerC}, v${putArguments.registerC}}, $EXTENSION_CLASS->onQueueSaveArguments(Lcom/google/protobuf/MessageLite;Landroid/os/Bundle;)V
    """)

    val argumentsField = picker.instructionMatches.first().instruction.getReference<FieldReference>()!!
    val mutablePicker = mutableClassDefBy(picker.originalClassDef.type)
    mutablePicker.interfaces.add(SAVE_PICKER_INTERFACE)
    mutablePicker.addBridgeMethod("patch_getQueueSaveVideoIds", emptyList(), LIST, 1, """
        iget-object v0, p0, $argumentsField
        invoke-static {v0}, $EXTENSION_CLASS->getQueueSaveVideoIds(Landroid/os/Bundle;)$LIST
        move-result-object v0
        return-object v0
    """)

    // Leave the native picker request entirely unchanged: its batch variation
    // returns HTTP 400 before the UI can open on the verified client.
    val response = QueueSaveResponseFingerprint.matchSingle()
    val callbackPicker = queueSaveResponsePickerFingerprint(mutablePicker.type).matchSingle()
        .instructionMatches.single().instruction.getReference<FieldReference>()!!

    val responseCast = response.instructionMatches.first()
    val responseType = responseCast.instruction.getReference<TypeReference>()!!.type
    val responseRegister = (responseCast.instruction as OneRegisterInstruction).registerA
    val responsePickerRegister = response.method.findFreeRegister(responseCast.index + 1, responseRegister, response.method.p0Register)
    check(responsePickerRegister <= 15 && responseRegister <= 15 && response.method.p0Register <= 15)
    response.method.addInstructionsAtControlFlowLabel(responseCast.index + 1, """
        iget-object v$responsePickerRegister, p0, $callbackPicker
        invoke-static {v$responsePickerRegister, v$responseRegister}, $SAVE_ACTIONS_CLASS->onPickerResponse(${SAVE_PICKER_INTERFACE}Lcom/google/protobuf/MessageLite;)Lcom/google/protobuf/MessageLite;
        move-result-object v$responseRegister
        check-cast v$responseRegister, $responseType
    """)

    mutablePicker.addBridgeMethod("patch_parseQueueSaveResponse", listOf("[B"), "Lcom/google/protobuf/MessageLite;", 2, """
        ${parseProtoInstructions(responseType, bytes = "p1", result = "v0", registry = "v1")}
        return-object v0
    """)
}
