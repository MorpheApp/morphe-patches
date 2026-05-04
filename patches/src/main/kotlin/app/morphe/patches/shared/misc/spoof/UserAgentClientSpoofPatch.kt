package app.morphe.patches.shared.misc.spoof

import app.morphe.patcher.extensions.InstructionExtensions.getInstructionOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.findMutableMethodOf
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
        // TODO: Replace this with Fingerprint.matchAll()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/morphe/extension")) return@classDefForEach

            classDef.methods.forEach { method ->

                val instructionsIterable = method.implementation?.instructions ?: return@forEach
                val resourceOrGmsStringInstructionIndex = method.indexOfFirstInstruction {
                    val reference = getReference<StringReference>()
                    opcode == Opcode.CONST_STRING &&
                            (reference?.string == "android.resource://" || reference?.string == "gcore_")
                }

                if (resourceOrGmsStringInstructionIndex >= 0) {
                    return@forEach
                }

                val targetIndices = instructionsIterable.mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.INVOKE_VIRTUAL && instruction is ReferenceInstruction) {
                        val ref = instruction.reference as? MethodReference
                        val isTargetMethod = ref?.definingClass == "Landroid/content/Context;" &&
                                ref.name == "getPackageName" &&
                                ref.parameterTypes.isEmpty() &&
                                ref.returnType == "Ljava/lang/String;"

                        if (isTargetMethod) index else null
                    } else {
                        null
                    }
                }

                if (targetIndices.isNotEmpty()) {
                    val mutableMethod = mutableClassDefBy(classDef.type).findMutableMethodOf(method)

                    targetIndices.reversed().forEach { index ->

                        val moveResultInst = mutableMethod.getInstructionOrNull<Instruction>(index + 1)
                            ?: return@forEach

                        if (moveResultInst.opcode != Opcode.MOVE_RESULT_OBJECT || moveResultInst !is OneRegisterInstruction) {
                            return@forEach
                        }

                        val targetRegister = moveResultInst.registerA
                        val nextInstruction = mutableMethod.getInstructionOrNull<Instruction>(index + 2)
                        val referee = (nextInstruction as? ReferenceInstruction)
                            ?.getReference<MethodReference>()?.toString()

                        if (referee != USER_AGENT_STRING_BUILDER_APPEND_METHOD_REFERENCE) {
                            return@forEach
                        }

                        mutableMethod.replaceInstruction(
                            index + 1,
                            "const-string v$targetRegister, \"$originalPackageName\""
                        )
                    }
                }
            }
        }
    }
}