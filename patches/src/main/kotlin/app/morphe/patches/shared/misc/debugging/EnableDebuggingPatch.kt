/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2638
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.debugging

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneParameters
import app.morphe.util.copyResources
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.numberOfParameterRegistersLogical
import app.morphe.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/EnableDebuggingPatch;"

/**
 * Patch shared with YouTube and YT Music.
 */
internal fun enableDebuggingPatch(
    block: BytecodePatchBuilder.() -> Unit = {},
    executeBlock: BytecodePatchContext.() -> Unit = {},
    hookStringFeatureFlag: BytecodePatchBuilder.() -> Boolean,
    hookLongFeatureFlag: BytecodePatchBuilder.() -> Boolean,
    hookDoubleFeatureFlag: BytecodePatchBuilder.() -> Boolean,
    preferenceScreen: BasePreferenceScreen.Screen,
    additionalDebugPreferences: List<BasePreference> = emptyList()
) = bytecodePatch(
    name = "Enable debugging",
    description = "Adds options for debugging and exporting Morphe logs to the clipboard.",
) {

    dependsOn(
        resourcePatch {
            execute {
                copyResources(
                    "settings",
                    ResourceGroup("drawable",
                        // Feature flags manager buttons.
                        "morphe_settings_bisect.xml",
                        "morphe_settings_copy_all.xml",
                        "morphe_settings_deselect_all.xml",
                        "morphe_settings_import_export.xml",
                        "morphe_settings_select_all.xml"
                    )
                )
            }
        }
    )

    block()

    execute {
        executeBlock()

        val preferences = mutableSetOf<BasePreference>(
            SwitchPreference("morphe_debug"),
        )

        preferences.addAll(additionalDebugPreferences)

        preferences.addAll(
            listOf(
                SwitchPreference("morphe_debug_stacktrace", summary = true),
                SwitchPreference("morphe_debug_toast_on_error"),
                NonInteractivePreference(
                    "morphe_debug_export_logs",
                    tag = "app.morphe.extension.shared.settings.preference.ExportLogToClipboardPreference",
                    selectable = true
                ),
                NonInteractivePreference(
                    "morphe_debug_feature_flags_manager",
                    tag = "app.morphe.extension.shared.settings.preference.FeatureFlagsManagerPreference",
                    selectable = true
                )
            )
        )

        preferenceScreen.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_debug_screen",
                sorting = Sorting.UNSORTED,
                preferences = preferences,
            )
        )

        fun MutableMethod.firstLongParameterRegister(): Int = numberOfParameterRegistersLogical +
                p0Register +
                parameterTypes.indexOf("J") -1

        ExperimentalBooleanFeatureFlagFingerprint.method.cloneParameters().apply {
            val longParameter1 = firstLongParameterRegister()
            val longParameter2 = longParameter1 + 1

            findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """ 
                        invoke-static { v$register, v$longParameter1, v$longParameter2 }, $EXTENSION_CLASS->isBooleanFeatureFlagEnabled(ZJ)Z
                        move-result v$register
                    """
                )
            }
        }

        fun overrideWideFeatureValue(fingerprint: Fingerprint, extensionMethod: String) {
            fingerprint.method.cloneParameters().apply {
                val longParameter = firstLongParameterRegister()

                findInstructionIndicesReversedOrThrow(Opcode.RETURN_WIDE).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            move-wide/from16 v0, v$register
                            move-wide/from16 v2, v$longParameter
                            move-wide/from16 v4, v${longParameter + 2}
                            invoke-static/range { v0 .. v5 }, $extensionMethod
                            move-result-wide v$register
                        """
                    )
                }
            }
        }

        if (hookDoubleFeatureFlag()) overrideWideFeatureValue(
            ExperimentalDoubleFeatureFlagFingerprint,
            "$EXTENSION_CLASS->isDoubleFeatureFlagEnabled(DJD)D"
        )

        if (hookLongFeatureFlag()) overrideWideFeatureValue(
            ExperimentalLongFeatureFlagFingerprint,
            "$EXTENSION_CLASS->isLongFeatureFlagEnabled(JJJ)J"
        )

        if (hookStringFeatureFlag()) ExperimentalStringFeatureFlagFingerprint.method.cloneParameters().apply {
            val longParameter = firstLongParameterRegister()

            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """ 
                        move-object/from16 v0, v$register
                        move-wide/from16 v1, v$longParameter
                        move-object/from16 v3, v${longParameter + 2}
                            
                        invoke-static/range { v0 .. v3 }, $EXTENSION_CLASS->isStringFeatureFlagEnabled(Ljava/lang/String;JLjava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """
                )
            }
        }

        // There exists other experimental accessor methods for byte[]
        // and wrappers for obfuscated classes, but currently none of those are hooked.
    }
}
