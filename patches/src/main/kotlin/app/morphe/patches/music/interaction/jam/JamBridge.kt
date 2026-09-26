/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** Small native access adapters. All queue and presentation policy belongs in the extension. */
internal fun ProtoAbi.decode(bytes: String, result: String): String {
  val registry = if (result == "v1") "v2" else "v1"
  return """
        sget-object $result, $defaultInstance
        invoke-static {}, Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;
        move-result-object $registry
        invoke-static {$result, $bytes, $registry}, $parser
        move-result-object $result
        check-cast $result, $type
    """
}

internal fun MutableClass.addBridge(
    name: String,
    parameters: List<String>,
    returnType: String,
    registers: Int,
    accessFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    body: String,
) {
  methods.add(
      ImmutableMethod(
              type,
              name,
              parameters.map { ImmutableMethodParameter(it, null, null) },
              returnType,
              accessFlags,
              null,
              null,
              MutableMethodImplementation(registers),
          )
          .toMutable()
          .apply { addInstructions(0, body) }
  )
}

internal fun BytecodePatchContext.invokeKind(reference: MethodReference): String {
  val owner = classDefByOrNull(reference.definingClass)
  return when {
    reference.name == "<init>" -> "invoke-direct"
    reference is Method && AccessFlags.PRIVATE.isSet(reference.accessFlags) -> "invoke-direct"
    owner != null && AccessFlags.INTERFACE.isSet(owner.accessFlags) -> "invoke-interface"
    else -> "invoke-virtual"
  }
}

/** Expose a zero-argument native accessor, optionally on a held or opaque receiver. */
internal fun BytecodePatchContext.installNativeAccessor(
    owner: MutableClass,
    name: String,
    accessor: MethodReference,
    receiverField: FieldReference? = null,
    opaqueReceiver: Boolean = false,
    resultType: String = accessor.returnType,
) {
  require(accessor.parameterTypes.isEmpty()) {
    "Jam accessor must have no native arguments: $accessor"
  }
  require(receiverField == null || !opaqueReceiver) { "Jam accessor has two receivers" }
  val wide = resultType == "J" || resultType == "D"
  val suffix =
      when {
        wide -> "-wide"
        resultType.startsWith("L") || resultType.startsWith("[") -> "-object"
        else -> ""
      }
  val receiver = if (opaqueReceiver) "p1" else if (receiverField != null) "v0" else "p0"
  owner.addBridge(
      name,
      if (opaqueReceiver) listOf("Ljava/lang/Object;") else emptyList(),
      resultType,
      (if (wide) 2 else 1) + (if (opaqueReceiver) 2 else 1),
      body =
          buildString {
            if (opaqueReceiver) appendLine("check-cast p1, ${accessor.definingClass}")
            if (receiverField != null) appendLine("iget-object v0, p0, $receiverField")
            appendLine("${invokeKind(accessor)} {$receiver}, $accessor")
            appendLine("move-result$suffix v0")
            appendLine("return$suffix v0")
          },
  )
}

/** Expose one native reference field; type adaptation and fallback stay in Java. */
internal fun MutableClass.addReferenceGetter(name: String, field: FieldReference) {
  require(field.type.startsWith("L") || field.type.startsWith("["))
  addBridge(
      name,
      emptyList(),
      field.type,
      2,
      body =
          """
        iget-object v0, p0, $field
        return-object v0
    """,
  )
}
