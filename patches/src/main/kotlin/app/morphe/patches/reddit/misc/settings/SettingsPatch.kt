/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import app.morphe.patches.all.misc.resources.addAppResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.all.misc.resources.localesReddit
import app.morphe.patches.all.misc.resources.setAddResourceLocale
import app.morphe.patches.all.misc.updates.disablePlayStoreUpdatesPatch
import app.morphe.patches.reddit.misc.extension.hooks.redditActivityOnCreateHook
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.shared.misc.checks.experimentalAppNoticePatch
import app.morphe.util.ResourceGroup
import app.morphe.util.cloneMutableAndPreserveParameters
import app.morphe.util.copyResources
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/settings/RedditActivityHook;"

var is_2026_04_or_greater = false
    private set
var is_2026_11_or_greater = false
    private set

val settingsPatch = bytecodePatch(
    description = "Applies mandatory patches to implement Morphe settings into the application."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        sharedExtensionPatch,
        disablePlayStoreUpdatesPatch,
        addResourcesPatch,
        experimentalAppNoticePatch(
            mainActivityFingerprint = redditActivityOnCreateHook.fingerprint,
            recommendedAppVersion = COMPATIBILITY_REDDIT.targets.first { !it.isExperimental }.version!!
        ),
        resourcePatch {
            execute {
                copyResources(
                    "settings",
                    ResourceGroup("drawable",
                        "morphe_ic_dialog_alert.xml",
                        "morphe_settings_custom_checkmark.xml",
                        "morphe_settings_custom_checkmark_bold.xml",
                    ),
                    ResourceGroup("layout",
                        "morphe_custom_list_item_checked.xml"
                    )
                )
            }
        }
    )

    execute {
        setAddResourceLocale(localesReddit)
        addAppResources("shared")
        addAppResources("reddit")

        /**
         * Set version info
         */
        val versionNumber = RedditInternalFeaturesFingerprint.instructionMatches[1].instruction
            .getReference<StringReference>()!!.string.replace(".", "").toInt()

        is_2026_04_or_greater = 2026040 <= versionNumber
        is_2026_11_or_greater = 2026110 <= versionNumber

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

        PreferenceDestinationFingerprint.let {
            val getActivityMethod = Fingerprint(
                accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                returnType = RedditActivityFingerprint.originalClassDef.type,
                parameters = listOf()
            ).method

            val startActivityMethod = Fingerprint(
                definingClass = getActivityMethod.definingClass,
                returnType = "V",
                parameters = listOf(
                    "Landroid/content/Intent",
                    "I",
                    "Landroid/os/Bundle;"
                ),
                filters = listOf(
                    string(" not attached to Activity"),
                )
            ).method

            it.method.cloneMutableAndPreserveParameters().addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->isAcknowledgment(Ljava/lang/Enum;)Z
                    move-result v0
                    if-eqz v0, :ignore
                    
                    invoke-virtual { p0 }, $getActivityMethod
                    move-result-object v0
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->initializeByIntent(Landroid/content/Context;)Landroid/content/Intent;
                    move-result-object v0
                    
                    const/4 v1, -1
                    const/4 v2, 0x0
                    invoke-virtual { p0, v0, v1, v2 }, ${getActivityMethod.definingClass}->${startActivityMethod.name}(Landroid/content/Intent;ILandroid/os/Bundle;)V
                    return-void
                    
                    :ignore
                    nop
                """
            )
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
