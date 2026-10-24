/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.fix.proto.ProtobufClassParseByteArrayWithRegistryFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * Adds a method to an app class. [locals] is the number of v registers
 * before the p registers. All parameters must be single register types.
 */
internal fun MutableClass.addBridgeMethod(
    name: String,
    parameters: List<String>,
    returnType: String,
    locals: Int,
    body: String,
    accessFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
): MutableMethod = ImmutableMethod(
    type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
    accessFlags, null, null, MutableMethodImplementation(locals + parameters.size + 1)
).toMutable().apply {
    addInstructionsWithLabels(0, body)
}.also { methods.add(it) }

/**
 * Smali that parses the byte array in [bytes] to a new [messageType] instance in [result],
 * using the app's own protobuf runtime. [registry] is overwritten.
 * All registers must be v0-v15.
 */
internal fun BytecodePatchContext.parseProtoInstructions(
    messageType: String,
    bytes: String,
    result: String,
    registry: String
): String {
    val defaultInstance = protoDefaultInstanceFingerprint(messageType).matchSingle()
        .instructionMatches.single().instruction.getReference<FieldReference>()!!

    // Generated messages share the static parseFrom of the protobuf runtime's message class.
    val parser = ProtobufClassParseByteArrayWithRegistryFingerprint.originalMethod

    return """
        sget-object $result, $defaultInstance
        invoke-static {}, Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;
        move-result-object $registry
        invoke-static {$result, $bytes, $registry}, $parser
        move-result-object $result
    """
}
