package app.morphe.patches.reddit.layout.subredditdialog

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBLE_PACKAGE
import app.morphe.patches.reddit.utils.extension.Constants.PATCHES_PATH
import app.morphe.patches.reddit.utils.patch.PatchList.REMOVE_SUBREDDIT_DIALOG
import app.morphe.patches.reddit.utils.settings.settingsPatch
import app.morphe.patches.reddit.utils.settings.updatePatchStatus
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "$PATCHES_PATH/RemoveSubRedditDialogPatch;"

@Suppress("unused")
val subRedditDialogPatch = bytecodePatch(
    REMOVE_SUBREDDIT_DIALOG.title,
    REMOVE_SUBREDDIT_DIALOG.summary,
) {
    compatibleWith(COMPATIBLE_PACKAGE)

    dependsOn(
        settingsPatch
    )

    execute {

        frequentUpdatesHandlerFingerprint
            .method
            .apply {
                listOfUserIsSubscriberInstruction(this)
                    .forEach { targetIndex ->
                        val index =
                            indexOfFirstInstructionReversedOrThrow(targetIndex, Opcode.IF_NEZ)

                        val register =
                            getInstruction<OneRegisterInstruction>(index).registerA

                        addInstructions(
                            index, """
                                invoke-static {v$register}, $EXTENSION_CLASS_DESCRIPTOR->spoofLoggedInStatus(Z)Z
                                move-result v$register
                                """
                        )
                    }
            }

        nsfwAlertEmitFingerprint
            .method
            .apply {
                val hasBeenVisitedIndex = indexOfHasBeenVisitedInstruction(this)
                val hasBeenVisitedRegister =
                    getInstruction<OneRegisterInstruction>(hasBeenVisitedIndex + 1).registerA

                addInstructions(
                    hasBeenVisitedIndex + 2, """
                            invoke-static {v$hasBeenVisitedRegister}, $EXTENSION_CLASS_DESCRIPTOR->spoofHasBeenVisitedStatus(Z)Z
                            move-result v$hasBeenVisitedRegister
                            """
                )

                val isIncognitoIndex = indexOfIsIncognitoInstruction(this)
                val nsfwAlertBuilderIndex = indexOfFirstInstructionOrThrow(isIncognitoIndex) {
                    val reference = getReference<MethodReference>()
                    opcode == Opcode.INVOKE_VIRTUAL &&
                            reference?.returnType == "V" &&
                            reference.parameterTypes.firstOrNull() == "Z"
                }
                val nsfwAlertBuilderReference =
                    getInstruction<ReferenceInstruction>(nsfwAlertBuilderIndex).reference as MethodReference
                val nsfwAlertBuilderClass =
                    nsfwAlertBuilderReference.definingClass

                var hookCount = 0

                classDefForEach { classDef ->
                    if (classDef.type == nsfwAlertBuilderClass) {
                        classDef.methods.forEach { method ->
                            val showIndex = method.indexOfFirstInstruction {
                                opcode == Opcode.INVOKE_VIRTUAL &&
                                        getReference<MethodReference>()?.name == "show"
                            }
                            if (showIndex >= 0) {
                                mutableClassDefBy(classDef)
                                    .findMutableMethodOf(method)
                                    .apply {
                                        val dialogRegister =
                                            getInstruction<OneRegisterInstruction>(showIndex + 1).registerA

                                        addInstruction(
                                            showIndex + 2,
                                            "invoke-static {v$dialogRegister}, $EXTENSION_CLASS_DESCRIPTOR->dismissNSFWDialog(Ljava/lang/Object;)V"
                                        )
                                        hookCount++
                                    }
                            }
                        }
                    }
                }

                if (hookCount == 0) {
                    throw PatchException("Failed to find hook method")
                }
            }

        updatePatchStatus(
            "enableSubRedditDialog",
            REMOVE_SUBREDDIT_DIALOG
        )
    }
}
