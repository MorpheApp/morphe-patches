/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3579
 * https://github.com/MorpheApp/morphe-patches/pull/3673
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.layout.font

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/CustomFontPatch;"

/**
 * @param applicationInitFingerprint Application onCreate method.
 */
fun baseCustomFontPatch(
    preferenceScreen: BasePreferenceScreen.Screen,
    applicationInitFingerprint: Fingerprint,
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {},
) = bytecodePatch(
    name = "Custom font",
    description = "Adds an option to show the app with the device system font or a custom TTF " +
            "or OTF font file instead of YouTube Sans.",
) {
    block()

    execute {
        preferenceScreen.addPreferences(
            noTitleUnsortedPreferenceCategory(
                ListPreference(
                    key = "morphe_custom_font_type",
                    tag = "app.morphe.extension.shared.settings.preference.CustomFontTypePreference"
                ),
                NonInteractivePreference(
                    key = "morphe_custom_font_file",
                    tag = "app.morphe.extension.shared.settings.preference.CustomFontFilePreference",
                    selectable = true
                )
            )
        )

        // Most text uses the platform default typeface, so the default typefaces
        // must be replaced with the custom font before any view is created.
        applicationInitFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->applyCustomFont(Landroid/content/Context;)V"
        )

        // YouTube Sans is loaded from bundled fonts and does not use the platform default typeface.

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
                    invoke-static { p0, p1, p3 }, $EXTENSION_CLASS->getCustomTypeface(Landroid/content/Context;II)Landroid/graphics/Typeface;
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
                invoke-static { p0, p2 }, $EXTENSION_CLASS->getCustomTypeface(Ljava/lang/Enum;I)Landroid/graphics/Typeface;
                move-result-object v0
                if-eqz v0, :original
                return-object v0
                :original
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        executeBlock()
    }
}
