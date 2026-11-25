/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4869
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val NSFW_BOTTOM_SHEET_BACK_CALLBACK_CLASS =
    $$"Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet$onInitialize$1$1;"

@Suppress("unused")
val fixNsfwWarningPatch = patch(
    name = "Fix NSFW warning",
    description = "Makes the NSFW warning and the back gesture return to the previous screen instead of the home screen. " +
            "This patch works with Reddit 2026.24.0 and newer. " +
            "With Reddit 2026.40.0 and newer, also prevents NSFW posts and profiles from briefly showing before the warning.",
    default = true
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch, preventNsfwFlashingPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Fix NSFW warning' requires Reddit 2026.24.0+"
            )
        }

        // Replace the blocked screen with the warning, instead of showing the warning on top
        // of the blocked screen. Then going back cannot show the blocked screen.
        ShowNsfwBottomSheetFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val call = getInstruction<FiveRegisterInstruction>(index)

                addInstructions(
                    index,
                    """
                        invoke-static { v${call.registerC}, v${call.registerD} }, ${ReplaceScreenFingerprint.originalMethod}
                        return-void
                    """
                )
            }
        }

        // Go back when the warning is declined. Must be done before the signed in session
        // check, because signed in sessions clear the navigation stack and open the home screen.
        val navigateBackField = NsfwBottomSheetViewModelConstructorFingerprint
            .instructionMatches.first().getFieldAccessed()

        NsfwBottomSheetHandleEventFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val unitField = it.instructionMatches.last().getFieldAccessed()
                val freeRegister = findFreeRegister(index)

                addInstructions(
                    index,
                    """
                        iget-object v$freeRegister, p0, $navigateBackField
                        invoke-interface/range { v$freeRegister .. v$freeRegister }, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;
                        sget-object v$freeRegister, $unitField
                        return-object v$freeRegister
                    """
                )
            }
        }

        // The warning has a disabled back handler that does nothing.
        // Enable it and use the go back callback of the warning.
        NsfwBottomSheetBackHandlerFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val call = getInstruction<FiveRegisterInstruction>(index)
                val callbackRegister = call.registerF
                val enabledRegister = call.registerG

                addInstructions(
                    index,
                    """
                        move-object/from16 v$enabledRegister, p0
                        new-instance v$callbackRegister, $NSFW_BOTTOM_SHEET_BACK_CALLBACK_CLASS
                        invoke-direct { v$callbackRegister, v$enabledRegister }, $NSFW_BOTTOM_SHEET_BACK_CALLBACK_CLASS-><init>(Ljava/lang/Object;)V
                        const/4 v$enabledRegister, 0x1
                    """
                )
            }
        }

        // Change the "No thanks" button to "Go back".
        val goBackLabel = resourceId(ResourceType.STRING, "action_go_back")
        stringResourceFingerprint("nsfw_no_thanks").matchAll(1..4).forEach { match ->
            match.method.apply {
                val index = match.instructionMatches.first().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    "const v$register, $goBackLabel"
                )
            }
        }
    }
}
