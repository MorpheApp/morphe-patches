/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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

        // region legacy method

        val navigationButtonInnerMethod = BottomNavScreenResourceBuilderFingerprint.instructionMatches[0]
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

        BottomNavScreenResourceBuilderFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(ADD_METHOD_CALL).forEach { index ->
                val instruction = getInstruction<FiveRegisterInstruction>(index)

                val listRegister = instruction.registerC
                val objectRegister = instruction.registerD

                replaceInstruction(
                    index,
                    "invoke-static { v$listRegister, v$objectRegister }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->" +
                            "hideNavigationButtonsLegacy(Ljava/util/List;Ljava/lang/Object;)V"
                )
            }
        }

        // endregion

        // region modern method

        BottomNavScreenListBuilderFingerprint.let {
            it.method.apply {
                val enumIndex = it.instructionMatches[2].index
                val enumRegister =
                    getInstruction<TwoRegisterInstruction>(enumIndex).registerA

                val freeIndex = it.instructionMatches.last().index
                val freeRegister =
                    getInstruction<OneRegisterInstruction>(freeIndex).registerA

                addInstructionsWithLabels(
                    enumIndex + 1,
                    """
                        invoke-static { v$enumRegister }, $EXTENSION_CLASS_DESCRIPTOR->hideNavigationTab(Ljava/lang/Enum;)Z
                        move-result v$freeRegister
                        if-nez v$freeRegister, :jump
                    """, ExternalLabel("jump", it.instructionMatches[1].instruction)
                )
            }
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
