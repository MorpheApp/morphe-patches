package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.reddit.utils.settings.settingsPatch
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/reddit/patches/NavigationButtonsPatch;"

@Suppress("unused")
val navigationButtonsPatch = bytecodePatch(
    name = "Hide navigation buttons",
    description = "Adds options to hide buttons in the navigation bar."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        bottomNavScreenFingerprint.method.apply {
                implementation!!.instructions
                    .withIndex()
                    .filter { (_, instruction) ->
                        val reference =
                            (instruction as? ReferenceInstruction)?.reference
                        instruction.opcode == Opcode.INVOKE_INTERFACE &&
                                reference is MethodReference &&
                                reference.toString() == "Ljava/util/List;->add(Ljava/lang/Object;)Z"
                    }
                    .map { (index, _) -> index }
                    .reversed()
                    .forEach { index ->
                        val instruction = getInstruction<FiveRegisterInstruction>(index)

                        val listRegister = instruction.registerC
                        val objectRegister = instruction.registerD

                        replaceInstruction(
                            index,
                            "invoke-static { v$listRegister, v$objectRegister }, " +
                                    "$EXTENSION_CLASS_DESCRIPTOR->" +
                                    "hideNavigationButtons(Ljava/util/List;Ljava/lang/Object;)V"
                        )
                    }

                implementation!!.instructions
                    .withIndex()
                    .filter { (_, instruction) ->
                        val reference = (instruction as? ReferenceInstruction)?.reference
                        instruction.opcode == Opcode.INVOKE_DIRECT &&
                                reference is MethodReference &&
                                reference.definingClass.startsWith("Lcom/reddit/widget/bottomnav/") &&
                                reference.name == "<init>" &&
                                reference.parameterTypes.firstOrNull() == "Ljava/lang/String;"
                    }
                    .map { (index, _) -> index }
                    .reversed()
                    .forEach { index ->
                        val instruction = getInstruction<FiveRegisterInstruction>(index)

                        val objectRegister = instruction.registerC
                        val labelRegister = instruction.registerD

                        addInstruction(
                            index + 1,
                            "invoke-static { v$objectRegister, v$labelRegister }, " +
                                    "$EXTENSION_CLASS_DESCRIPTOR->" +
                                    "setNavigationMap(Ljava/lang/Object;Ljava/lang/String;)V"
                        )
                    }

                val getResStringFilter = methodCall(
                    smali = "Landroid/content/res/Resources;->getString(I)Ljava/lang/String;",
                    opcode = Opcode.INVOKE_VIRTUAL
                )
                val matchingInstructions = instructions.mapIndexedNotNull { index, instruction ->
                    if (getResStringFilter.matches(this, instruction)) {
                        return@mapIndexedNotNull index to instruction
                    }
                    return@mapIndexedNotNull null
                }

                matchingInstructions.reversed().forEach { value ->
                    val (index, instruction) = value
                    val idReg = (instruction as FiveRegisterInstruction).registerD
                    addInstruction(
                        index,
                        "invoke-static { v$idReg }, " +
                                "$EXTENSION_CLASS_DESCRIPTOR->mapResourceId(I)V"
                    )
                }

                addInstruction(
                    0,
                    "invoke-static/range { p1 .. p1 }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->setResources(Landroid/content/res/Resources;)V"
                )
            }

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
