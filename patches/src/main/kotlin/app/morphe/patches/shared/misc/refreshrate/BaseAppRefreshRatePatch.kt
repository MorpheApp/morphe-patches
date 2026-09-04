/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.refreshrate

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/BaseAppRefreshRatePatch;"

fun baseAppRefreshRatePatch(
    preferenceScreen: BasePreferenceScreen.Screen,
    useRefreshRateType: Boolean,
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {},
) = bytecodePatch(
    name = "App refresh rate",
    description = "Adds an option to change the app refresh rate."
) {
    block()

    execute {
        val refreshPreference = NonInteractivePreference(
            key = "morphe_app_refresh_rate",
            summaryKey = null,
            tag = "app.morphe.extension.shared.settings.preference.AppRefreshRateListPreference",
            selectable = true
        )
        preferenceScreen.addPreferences(
            if (useRefreshRateType) {
                noTitleUnsortedPreferenceCategory(
                    refreshPreference,
                    ListPreference("morphe_app_refresh_rate_type")
                )
            } else {
                refreshPreference
            }
        )

        ActivityOnCreateFingerprint.matchAll().forEach {
            it.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                        "setActivityRefreshRate(Landroid/app/Activity;)V"
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        executeBlock()
    }
}
