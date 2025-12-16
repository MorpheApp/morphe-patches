package app.morphe.patches.shared.misc.debugging

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/patches/EnableDebuggingPatch;"

/**
 * Patch shared with YouTube and YT Music.
 */
internal fun enableDebuggingPatch(
    block: BytecodePatchBuilder.() -> Unit = {},
    executeBlock: BytecodePatchContext.() -> Unit = {},
    hookStringFeatureFlag: Boolean,
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
                        // Action buttons.
                        "morphe_settings_copy_all.xml",
                        "morphe_settings_deselect_all.xml",
                        "morphe_settings_select_all.xml",
                        // Move buttons.
                        "morphe_settings_arrow_left_double.xml",
                        "morphe_settings_arrow_left_one.xml",
                        "morphe_settings_arrow_right_double.xml",
                        "morphe_settings_arrow_right_one.xml"
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
                SwitchPreference("morphe_debug_stacktrace"),
                SwitchPreference("morphe_debug_toast_on_error"),
                NonInteractivePreference(
                    "morphe_debug_export_logs_to_clipboard",
                    tag = "app.morphe.extension.shared.settings.preference.ExportLogToClipboardPreference",
                    selectable = true
                ),
                NonInteractivePreference(
                    "morphe_debug_logs_clear_buffer",
                    tag = "app.morphe.extension.shared.settings.preference.ClearLogBufferPreference",
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

        // Hook the methods that look up if a feature flag is active.
        ExperimentalBooleanFeatureFlagFingerprint.match(
            ExperimentalFeatureFlagParentFingerprint.originalClassDef
        ).method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register, p1 }, $EXTENSION_CLASS_DESCRIPTOR->isBooleanFeatureFlagEnabled(ZLjava/lang/Long;)Z
                        move-result v$register
                    """
                )
            }
        }

        ExperimentalDoubleFeatureFlagFingerprint.match(
            ExperimentalFeatureFlagParentFingerprint.originalClassDef
        ).method.apply {
            val insertIndex = indexOfFirstInstructionOrThrow(Opcode.MOVE_RESULT_WIDE)

            addInstructions(
                insertIndex,
                """
                    move-result-wide v0     # Also clobbers v1 (p0) since result is wide.
                    invoke-static/range { v0 .. v5 }, $EXTENSION_CLASS_DESCRIPTOR->isDoubleFeatureFlagEnabled(DJD)D
                    move-result-wide v0
                    return-wide v0
                """
            )
        }

        ExperimentalLongFeatureFlagFingerprint.match(
            ExperimentalFeatureFlagParentFingerprint.originalClassDef
        ).method.apply {
            val insertIndex = indexOfFirstInstructionOrThrow(Opcode.MOVE_RESULT_WIDE)

            addInstructions(
                insertIndex,
                """
                    move-result-wide v0
                    invoke-static/range { v0 .. v5 }, $EXTENSION_CLASS_DESCRIPTOR->isLongFeatureFlagEnabled(JJJ)J
                    move-result-wide v0
                    return-wide v0
                """
            )
        }

        if (hookStringFeatureFlag) ExperimentalStringFeatureFlagFingerprint.match(
            ExperimentalFeatureFlagParentFingerprint.originalClassDef
        ).method.apply {
            val insertIndex = indexOfFirstInstructionReversedOrThrow(Opcode.MOVE_RESULT_OBJECT)

            addInstructions(
                insertIndex,
                """
                    move-result-object v0
                    invoke-static { v0, p1, p2, p3 }, $EXTENSION_CLASS_DESCRIPTOR->isStringFeatureFlagEnabled(Ljava/lang/String;JLjava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    return-object v0
                """
            )
        }

        // There exists other experimental accessor methods for byte[]
        // and wrappers for obfuscated classes, but currently none of those are hooked.
    }
}
