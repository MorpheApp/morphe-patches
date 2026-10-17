/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3579
 * https://github.com/MorpheApp/morphe-patches/pull/3673
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.font

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.layout.font.EXTENSION_CLASS
import app.morphe.patches.shared.layout.font.baseCustomFontPatch
import app.morphe.patches.youtube.misc.extension.hooks.YouTubeApplicationInitFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_32_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val customFontPatch = baseCustomFontPatch(
    preferenceScreen = PreferenceScreen.GENERAL,
    applicationInitFingerprint = YouTubeApplicationInitFingerprint,
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch,
        )

        compatibleWith(COMPATIBILITY_YOUTUBE)
    },
    executeBlock = {
        // Typeface registry does not exist before 21.32.
        if (is_21_32_or_greater) {
            TypefaceRegistryFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.first().index
                    val call = getInstruction<FiveRegisterInstruction>(index)
                    val weightRegister = call.registerE
                    val styleRegister = call.registerF
                    val fontSettingsRegister = call.registerG
                    val resultRegister = getInstruction<OneRegisterInstruction>(index + 1).registerA
                    if (resultRegister in listOf(weightRegister, styleRegister, fontSettingsRegister)) {
                        throw PatchException("Font provider result overwrites a parameter register")
                    }

                    addInstructions(
                        index + 2,
                        """
                            invoke-static { v$resultRegister, v$weightRegister, v$styleRegister, v$fontSettingsRegister }, $EXTENSION_CLASS->getCustomTypeface(Landroid/graphics/Typeface;IILjava/lang/String;)Landroid/graphics/Typeface;
                            move-result-object v$resultRegister
                        """
                    )
                }
            }
        }
    }
)
