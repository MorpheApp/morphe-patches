package app.morphe.patches.shared.misc.spoof

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstructionOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val USER_AGENT_STRING_BUILDER_APPEND_METHOD_REFERENCE =
    "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;"

fun userAgentClientSpoofPatch(originalPackageName: String) = bytecodePatch(
    description = "Spoofs the user agent client by replacing the application package name."
) {
    execute {
        val getPackageNameCall = methodCall(
            definingClass = "Landroid/content/Context;",
            name = "getPackageName",
            parameters = emptyList(),
            returnType = "Ljava/lang/String;"
        )

        Fingerprint(
            filters = listOf(getPackageNameCall),
            custom = { _, classDef -> !classDef.type.startsWith("Lapp/morphe/extension") }
        ).matchAll().forEach { match ->
            match.method.apply {
                val resourceOrGmsStringInstructionIndex = indexOfFirstInstruction {
                    val reference = getReference<StringReference>()
                    opcode == Opcode.CONST_STRING &&
                            (reference?.string == "android.resource://" || reference?.string == "gcore_")
                }

                if (resourceOrGmsStringInstructionIndex >= 0) {
                    return@apply
                }

                findInstructionIndicesReversedOrThrow(getPackageNameCall).forEach { index ->
                    val moveResultInst = getInstructionOrNull<Instruction>(index + 1)
                        ?: return@forEach

                    if (moveResultInst.opcode != Opcode.MOVE_RESULT_OBJECT || moveResultInst !is OneRegisterInstruction) {
                        return@forEach
                    }

                    val targetRegister = moveResultInst.registerA
                    val nextInstruction = getInstructionOrNull<Instruction>(index + 2)
                    val referee = (nextInstruction as? ReferenceInstruction)
                        ?.getReference<MethodReference>()?.toString()

                    if (referee != USER_AGENT_STRING_BUILDER_APPEND_METHOD_REFERENCE) {
                        return@forEach
                    }

                    replaceInstruction(
                        index + 1,
                        "const-string v$targetRegister, \"$originalPackageName\""
                    )
                }
            }
        }
    }
}