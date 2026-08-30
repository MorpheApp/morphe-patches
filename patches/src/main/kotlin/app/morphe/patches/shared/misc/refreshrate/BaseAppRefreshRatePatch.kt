/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.refreshrate

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.matchAllMethodIndicesForEach
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

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

        VideoFrameReleaseHelperSetFrameRateFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getRefreshRateOverride(F)F
                move-result p1
            """
        )

        listOf(
            DisplayGetRefreshRateFingerprint,
            DisplayModeGetRefreshRateFingerprint
        ).forEach { fingerprint ->
            fingerprint.matchAllMethodIndicesForEach(requireMatches = false) { index ->
                val moveResultIndex = index + 1
                val instruction = getInstruction(moveResultIndex)
                if (instruction.opcode != Opcode.MOVE_RESULT) {
                    return@matchAllMethodIndicesForEach
                }
                val register = (instruction as OneRegisterInstruction).registerA

                addInstructions(
                    moveResultIndex + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->getRefreshRateOverride(F)F
                        move-result v$register
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        executeBlock()
    }
}
