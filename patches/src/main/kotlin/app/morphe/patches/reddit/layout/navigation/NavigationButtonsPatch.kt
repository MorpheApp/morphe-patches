package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.reddit.utils.settings.settingsPatch
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/NavigationButtonsPatch;"

@Suppress("unused")
val navigationButtonsPatch = bytecodePatch(
    name = "Hide navigation buttons",
    description = "Adds options to hide buttons in the navigation bar."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        bottomNavScreenFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(
                methodCall(
                    opcode = Opcode.INVOKE_INTERFACE,
                    smali = "Ljava/util/List;->add(Ljava/lang/Object;)Z"
                )
            ).forEach { index ->
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

            // The game navigation button is behind a feature flag and thus also in a different method.
            // To handle this and other future changes, apply the patch to all methods in this class
            // instead of just the one matching bottomNavScreenFingerprint.
            bottomNavScreenFingerprint.classDef.apply {
                methods.forEach { method ->
                    method.apply {
                        findInstructionIndicesReversed(
                            methodCall(
                                opcode = Opcode.INVOKE_DIRECT,
                                definingClass = "Lcom/reddit/widget/bottomnav/",
                                name = "<init>",
                                parameters = listOf("Ljava/lang/String;", "Landroidx/compose/runtime/")
                            )
                        ).forEach { index ->
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

                        findInstructionIndicesReversed(
                            methodCall(
                                opcode = Opcode.INVOKE_VIRTUAL,
                                smali = "Landroid/content/res/Resources;->getString(I)Ljava/lang/String;"
                            )
                        ).forEach { index ->
                            val idReg = getInstruction<FiveRegisterInstruction>(index).registerD
                            addInstruction(
                                index,
                                "invoke-static { v$idReg }, $EXTENSION_CLASS_DESCRIPTOR->mapResourceId(I)V"
                            )
                        }
                    }
                }
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
