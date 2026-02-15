package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/HideNavigationButtonsPatch;"

private const val EXTENSION_HEADER_ITEM_INTERFACE =
    "Lapp/morphe/extension/reddit/patches/HideNavigationButtonsPatch\$NavigationButtonInterface;"

@Suppress("unused")
val hideNavigationButtonsPatch = bytecodePatch(
    name = "Hide navigation buttons",
    description = "Adds options to hide buttons in the navigation bar."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {

        val navigationButtonInnerMethod = BottomNavScreenFingerprint.instructionMatches[1]
            .instruction.getReference<MethodReference>()!!

        mutableClassDefBy(navigationButtonInnerMethod.definingClass).apply {
            // Add interface and helper methods to allow extension code to call obfuscated methods.
            interfaces.add(EXTENSION_HEADER_ITEM_INTERFACE)
            // Add methods to access obfuscated navigation button fields.
            methods.add(
                ImmutableMethod(
                    type,
                    "patch_getLabel",
                    listOf(),
                    "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    val labelField = fields.single { field ->
                        field.type == "Ljava/lang/String;"
                    }

                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, $labelField
                            return-object v0
                        """
                    )
                }
            )
        }

        BottomNavScreenFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(ADD_METHOD_CALL).forEach { index ->
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

            findInstructionIndicesReversedOrThrow(GET_STRING_METHOD_CALL).forEach { index ->
                val idReg = getInstruction<FiveRegisterInstruction>(index).registerD

                addInstruction(
                    index,
                    "invoke-static { v$idReg }, $EXTENSION_CLASS_DESCRIPTOR->mapResourceId(I)V"
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
