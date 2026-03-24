/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.subredditdialog

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/RemoveSubRedditDialogPatch;"

@Suppress("unused")
val removeSubRedditDialogPatch = bytecodePatch(
    name = "Remove subreddit dialog",
    description = "Adds options to remove the NSFW community warning and notifications suggestion dialogs by dismissing them automatically."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch
    )

    execute {
        mapOf(
            FrequentUpdatesHandlerFingerprint to "spoofLoggedInStatus",
            NSFWAlertEmitFingerprint to "spoofHasBeenVisitedStatus"
        ).forEach { (fingerprint, methodName) ->
            fingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[2].index
                    val register =
                        getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructions(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->$methodName(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        listOf(
            NSFWAlertDialogBuilderFingerprint,
            NSFWAlertDialogInstanceFingerprint
        ).forEach { fingerprint ->
            fingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.first().index
                    val moveResultIndex = index + 1
                    val insertIndex: Int
                    val register: Int

                    if (getInstruction(moveResultIndex).opcode != Opcode.MOVE_RESULT_OBJECT) {
                        // 2026.10.0+
                        insertIndex = moveResultIndex
                        register = getInstruction<FiveRegisterInstruction>(index).registerC
                    } else {
                        insertIndex = moveResultIndex + 1
                        register = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA
                    }

                    addInstruction(
                        insertIndex,
                        "invoke-static { v$register }, " +
                                "$EXTENSION_CLASS_DESCRIPTOR->dismissNSFWDialog(Ljava/lang/Object;)V"
                    )
                }
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
