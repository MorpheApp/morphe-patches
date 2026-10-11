/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3579
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.font

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_32_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ForceSystemFontPatch;"

@Suppress("unused")
val forceSystemFontPatch = bytecodePatch(
    name = "Force system font",
    description = "Adds an option to show the app with the device system font instead of YouTube Sans.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_force_system_font", summary = true)
        )

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
                    if (resultRegister in listOf(
                            weightRegister,
                            styleRegister,
                            fontSettingsRegister
                        )
                    ) {
                        throw PatchException("Font provider result overwrites a parameter register")
                    }

                    addInstructions(
                        index + 2,
                        """
                            invoke-static { v$resultRegister, v$weightRegister, v$styleRegister, v$fontSettingsRegister }, $EXTENSION_CLASS->getSystemTypeface(Landroid/graphics/Typeface;IILjava/lang/String;)Landroid/graphics/Typeface;
                            move-result-object v$resultRegister
                        """
                    )
                }
            }
        }

        FontResourceLoaderFingerprint.method.apply {
            val callbackSuccessMethod = Fingerprint(
                definingClass = parameterTypes[4].toString(),
                returnType = "V",
                parameters = listOf("Landroid/graphics/Typeface;"),
                custom = { method, _ ->
                    !AccessFlags.ABSTRACT.isSet(method.accessFlags)
                }
            ).originalMethod

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0, p1, p3 }, $EXTENSION_CLASS->getSystemTypeface(Landroid/content/Context;II)Landroid/graphics/Typeface;
                    move-result-object v0
                    if-eqz v0, :original                   
                    if-eqz p4, :callback_done
                    invoke-virtual { p4, v0 }, $callbackSuccessMethod
                    :callback_done
                    return-object v0
                    :original
                    nop
                """
            )
        }

        FontEnumTypefaceFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p2 }, $EXTENSION_CLASS->getSystemTypeface(Ljava/lang/Enum;I)Landroid/graphics/Typeface;
                move-result-object v0
                if-eqz v0, :original
                return-object v0
                :original
                nop
            """
        )
    }
}
