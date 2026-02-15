package app.morphe.patches.reddit.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.extension.hooks.redditActivityOnCreateHook
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.shared.misc.checks.experimentalAppNoticePatch
import app.morphe.util.findFreeRegister
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/settings/RedditActivityHook;"

var is_2026_04_or_greater = false
    private set

val settingsPatch = bytecodePatch(
    name = "Settings for Reddit",
    description = "Applies mandatory patches to implement Morphe settings into the application."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        sharedExtensionPatch,
        experimentalAppNoticePatch(
            mainActivityFingerprint = redditActivityOnCreateHook.fingerprint,
            recommendedAppVersion = COMPATIBILITY_REDDIT.second.first()
        )
    )

    execute {
        /**
         * Set version info
         */
        val versionNumber = RedditInternalFeaturesFingerprint.instructionMatches[1].instruction
            .getReference<StringReference>()!!.string.replace(".", "").toInt()

        is_2026_04_or_greater = 2026040 <= versionNumber

        /**
         * Replace settings label and icon
         */
        PreferenceManagerFingerprint.let {
            it.method.apply {
                val labelIndex = it.instructionMatches[5].index
                val labelRegister =
                    getInstruction<OneRegisterInstruction>(labelIndex).registerA

                addInstructions(
                    labelIndex + 1,
                    """
                        invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->getSettingLabel()Ljava/lang/String;
                        move-result-object v$labelRegister
                    """
                )

                val iconIndex = it.instructionMatches[2].index
                val iconRegister =
                    getInstruction<OneRegisterInstruction>(iconIndex).registerA

                addInstructions(
                    iconIndex + 1,
                    """
                        invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->getSettingIcon()Landroid/graphics/drawable/Drawable;
                        move-result-object v$iconRegister
                    """
                )
            }
        }

        /**
         * Initialize settings activity
         */
        val getActivityMethod = FragmentHostCallbackFingerprint.method
        PreferenceDestinationFingerprint.let {
            it.method.apply {
                val fragmentIndex = it.instructionMatches[1].index
                val fragmentRegister =
                    getInstruction<FiveRegisterInstruction>(fragmentIndex).registerC
                val registerProvider =
                    getFreeRegisterProvider(fragmentIndex, 1)
                val freeRegister = registerProvider.getFreeRegister()

                addInstructionsWithLabels(
                    fragmentIndex,
                    """
                        invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->isAcknowledgment(Ljava/lang/Enum;)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :ignore
                        invoke-virtual { v$fragmentRegister }, $getActivityMethod
                        move-result-object v$freeRegister
                        invoke-static { v$freeRegister }, $EXTENSION_CLASS_DESCRIPTOR->initializeByIntent(Landroid/content/Context;)Landroid/content/Intent;
                        move-result-object v$freeRegister
                        invoke-virtual { v$fragmentRegister, v$freeRegister }, ${getActivityMethod.definingClass}->startActivity(Landroid/content/Intent;)V
                        return-void
                        :ignore
                        nop
                    """
                )
            }
        }

        WebBrowserActivityOnCreateFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches.first().index
                val freeRegister = findFreeRegister(insertIndex)

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->hook(Landroid/app/Activity;)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :ignore
                        return-void
                        :ignore
                        nop
                    """
                )
            }
        }
    }
}
