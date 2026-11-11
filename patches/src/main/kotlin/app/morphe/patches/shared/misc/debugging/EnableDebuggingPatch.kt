/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2638
 * https://github.com/MorpheApp/morphe-patches/pull/4416
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.debugging

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/EnableDebuggingPatch;"

/**
 * Patch shared with YouTube and YT Music.
 */
internal fun enableDebuggingPatch(
    block: PatchDefinition.() -> Unit = {},
    executeBlock: () -> Unit = {},
    hookIndexedBooleanFeatureFlag: PatchDefinition.() -> Boolean,
    hookStringFeatureFlag: PatchDefinition.() -> Boolean,
    hookLongFeatureFlag: PatchDefinition.() -> Boolean,
    hookDoubleFeatureFlag: PatchDefinition.() -> Boolean,
    hookHotFeatureFlags: PatchDefinition.() -> Boolean,
    preferenceScreen: BasePreferenceScreen.Screen,
    additionalDebugPreferences: List<BasePreference> = emptyList()
) = patch(
    name = "Enable debugging",
    description = "Adds options for debugging and exporting Morphe logs to the clipboard.",
) {

    dependsOn(
        patch {
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

        fun MutableMethod.firstLongParameterRegister(): Int {
            // Parameter types can be CharSequence objects that are not Strings.
            val types = parameterTypes.map { it.toString() }
            val longIndex = types.indexOf("J")
            check(longIndex >= 0) { "Method has no long parameter: $this" }

            return p0Register +
                    (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) +
                    types.take(longIndex).sumOf { if (it == "J" || it == "D") 2 else 1 }
        }

        fun overrideBooleanFeatureValue(fingerprint: Fingerprint) {
            fingerprint.method.cloneParameters().apply {
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
        }

        overrideBooleanFeatureValue(ExperimentalBooleanFeatureFlagFingerprint)

        if (hookIndexedBooleanFeatureFlag()) overrideBooleanFeatureValue(
            ExperimentalIndexedBooleanFeatureFlagFingerprint
        )

        /**
         * @param hasDefaultParameter If the accessor has a default value parameter
         *                            after the flag. Accessors without one default to zero.
         */
        fun overrideWideFeatureValue(
            fingerprint: Fingerprint,
            extensionMethod: String,
            hasDefaultParameter: Boolean = true
        ) {
            fingerprint.method.cloneParameters().apply {
                val longParameter = firstLongParameterRegister()
                val setDefaultValue = if (hasDefaultParameter) {
                    "move-wide/from16 v4, v${longParameter + 2}"
                } else {
                    "const-wide/16 v4, 0x0"
                }

                findInstructionIndicesReversedOrThrow(Opcode.RETURN_WIDE).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            move-wide/from16 v0, v$register
                            move-wide/from16 v2, v$longParameter
                            $setDefaultValue
                            invoke-static/range { v0 .. v5 }, $extensionMethod
                            move-result-wide v$register
                        """
                    )
                }
            }
        }

        /**
         * @param hasDefaultParameter If the accessor has a default value parameter
         *                            after the flag. Accessors without one default to an empty string.
         */
        fun overrideStringFeatureValue(fingerprint: Fingerprint, hasDefaultParameter: Boolean = true) {
            fingerprint.method.cloneParameters().apply {
                val longParameter = firstLongParameterRegister()
                val setDefaultValue = if (hasDefaultParameter) {
                    "move-object/from16 v3, v${longParameter + 2}"
                } else {
                    "const-string v3, \"\""
                }

                findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            move-object/from16 v0, v$register
                            move-wide/from16 v1, v$longParameter
                            $setDefaultValue
                            invoke-static/range { v0 .. v3 }, $EXTENSION_CLASS->isStringFeatureFlagEnabled(Ljava/lang/String;JLjava/lang/String;)Ljava/lang/String;
                            move-result-object v$register
                        """
                    )
                }
            }
        }

        val doubleExtensionMethod = "$EXTENSION_CLASS->isDoubleFeatureFlagEnabled(DJD)D"
        val longExtensionMethod = "$EXTENSION_CLASS->isLongFeatureFlagEnabled(JJJ)J"

        if (hookDoubleFeatureFlag()) overrideWideFeatureValue(
            ExperimentalDoubleFeatureFlagFingerprint,
            doubleExtensionMethod
        )

        if (hookLongFeatureFlag()) overrideWideFeatureValue(
            ExperimentalLongFeatureFlagFingerprint,
            longExtensionMethod
        )

        if (hookStringFeatureFlag()) overrideStringFeatureValue(ExperimentalStringFeatureFlagFingerprint)

        if (hookHotFeatureFlags()) {
            overrideBooleanFeatureValue(ExperimentalHotBooleanFeatureFlagFingerprint)

            if (hookDoubleFeatureFlag()) overrideWideFeatureValue(
                ExperimentalHotDoubleFeatureFlagFingerprint,
                doubleExtensionMethod,
                hasDefaultParameter = false
            )

            if (hookLongFeatureFlag()) overrideWideFeatureValue(
                ExperimentalHotLongFeatureFlagFingerprint,
                longExtensionMethod,
                hasDefaultParameter = false
            )

            if (hookStringFeatureFlag()) overrideStringFeatureValue(
                ExperimentalHotStringFeatureFlagFingerprint,
                hasDefaultParameter = false
            )
        }

        // Bootstrap store flags, which are read on startup before the server config is loaded.
        BootstrapStoreReadFingerprint.method.cloneParameters().apply {
            val flagParameter = p0Register + 1

            findInstructionIndicesReversedOrThrow(Opcode.RETURN_WIDE).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        move-wide/from16 v0, v$register
                        move/from16 v2, v$flagParameter
                        invoke-static/range { v0 .. v2 }, $EXTENSION_CLASS->isBootstrapFeatureFlagEnabled(JI)J
                        move-result-wide v$register
                    """
                )
            }
        }

        // There exists other experimental accessor methods for byte[]
        // and wrappers for obfuscated classes, but currently none of those are hooked.
    }
}
