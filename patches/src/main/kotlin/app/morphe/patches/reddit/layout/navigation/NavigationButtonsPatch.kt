package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBLE_PACKAGE
import app.morphe.patches.reddit.utils.extension.Constants.PATCHES_PATH
import app.morphe.patches.reddit.utils.patch.PatchList.HIDE_NAVIGATION_BUTTONS
import app.morphe.patches.reddit.utils.settings.settingsPatch
import app.morphe.patches.reddit.utils.settings.updatePatchStatus
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "$PATCHES_PATH/NavigationButtonsPatch;"

/*
// FIXME: Figure out the reason why the extension code is getting invalid resource IDs.
@Suppress("unused")
val navigationButtonsPatch = bytecodePatch(
    HIDE_NAVIGATION_BUTTONS.title,
    HIDE_NAVIGATION_BUTTONS.summary,
) {
    compatibleWith(COMPATIBLE_PACKAGE)

    dependsOn(settingsPatch)

    execute {
        bottomNavScreenFingerprint
            .method.
            .apply {
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
                        val instruction =
                            getInstruction<FiveRegisterInstruction>(index)

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
                        val reference =
                            (instruction as? ReferenceInstruction)?.reference
                        instruction.opcode == Opcode.INVOKE_DIRECT &&
                                reference is MethodReference &&
                                reference.definingClass.startsWith("Lcom/reddit/widget/bottomnav/") &&
                                reference.name == "<init>" &&
                                reference.parameterTypes.firstOrNull() == "Ljava/lang/String;"
                    }
                    .map { (index, _) -> index }
                    .reversed()
                    .forEach { index ->
                        val instruction =
                            getInstruction<FiveRegisterInstruction>(index)

                        val objectRegister = instruction.registerC
                        val labelRegister = instruction.registerD

                        addInstruction(
                            index + 1,
                            "invoke-static { v$objectRegister, v$labelRegister }, " +
                                    "$EXTENSION_CLASS_DESCRIPTOR->" +
                                    "setNavigationMap(Ljava/lang/Object;Ljava/lang/String;)V"
                        )
                    }

                addInstruction(
                    0,
                    "invoke-static/range { p1 .. p1 }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->setResources(Landroid/content/res/Resources;)V"
                )
            }

        updatePatchStatus(
            "enableNavigationButtons",
            HIDE_NAVIGATION_BUTTONS
        )
    }
}
*/
