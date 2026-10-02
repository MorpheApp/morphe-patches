/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3579
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.font

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ForceSystemFontPatch;"

@Suppress("unused")
val forceSystemFontPatch = bytecodePatch(
    name = "Force system font",
    description = "Adds an option to show the app with the device system font instead of YouTube Sans.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_force_system_font", summary = true)
        )

        TypefaceRegistryFingerprint.method.apply {
            val register = getFreeRegisterProvider(0, 1).getFreeRegister4Bit()

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p3, p4, p5 }, $EXTENSION_CLASS->getSystemTypeface(IILjava/lang/String;)Landroid/graphics/Typeface;
                    move-result-object v$register
                    if-eqz v$register, :original
                    return-object v$register
                    :original
                    nop
                """
            )
        }

        FontResourceLoaderFingerprint.method.apply {
            val callbackType = parameterTypes[4].toString()
            val callbackSuccessMethod = classDefBy(callbackType).methods.first {
                !AccessFlags.ABSTRACT.isSet(it.accessFlags) &&
                        it.returnType == "V" &&
                        it.parameterTypes.map(Any::toString) == listOf("Landroid/graphics/Typeface;")
            }
            val register = getFreeRegisterProvider(0, 1).getFreeRegister4Bit()

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0, p1, p3 }, $EXTENSION_CLASS->getSystemTypeface(Landroid/content/Context;II)Landroid/graphics/Typeface;
                    move-result-object v$register
                    if-eqz v$register, :original
                    if-eqz p4, :return
                    invoke-virtual { p4, v$register }, $callbackSuccessMethod
                    :return
                    return-object v$register
                    :original
                    nop
                """
            )
        }

        FontEnumTypefaceFingerprint.method.apply {
            val register = getFreeRegisterProvider(0, 1).getFreeRegister4Bit()

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0, p2 }, $EXTENSION_CLASS->getSystemTypeface(Ljava/lang/Enum;I)Landroid/graphics/Typeface;
                    move-result-object v$register
                    if-eqz v$register, :original
                    return-object v$register
                    :original
                    nop
                """
            )
        }
    }
}
