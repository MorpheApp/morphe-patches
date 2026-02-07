package app.morphe.patches.shared.misc.debugging

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.ResourceGroup
import app.morphe.util.cloneMutable
import app.morphe.util.copyResources
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/patches/EnableDebuggingPatch;"

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
            ExperimentFlagUtilFingerprint.originalClassDef
        ).let {
            it.method.apply {
                // In some versions, freeRegister is not available.
                // The easiest workaround is to copy the method to minimize modifications to the instructions.

                // Copy the method.
                val helperMethod = cloneMutable(name = "patch_getBooleanFeatureFlag")

                // Add the method.
                it.classDef.methods.add(helperMethod)

                addInstructions(
                    0,
                    """
                        # Invoke the copied method (helper method).
                        invoke-static { p0, p1, p2, p3 }, $helperMethod
                        move-result p0
                        
                        # Convert the flag value to 'Long' format to pass it to the extension.
                        invoke-static { p1, p2 }, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
                        move-result-object p1
                        
                        # Redefine boolean in the extension.
                        invoke-static { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->isBooleanFeatureFlagEnabled(ZLjava/lang/Long;)Z
                        move-result p0
                        
                        # Since the copied method (helper method) has already been invoked, it just returns.
                        return p0
                    """
                )
            }
        }

        // In some versions, the classes for 'ExperimentalBooleanFeatureFlagFingerprint' and
        // 'ExperimentalDoubleFeatureFlagFingerprint, ExperimentalLongFeatureFlagFingerprint, ExperimentalStringFeatureFlagFingerprint'
        // are different.
        // To handle this, declare parent fingerprints.
        val experimentalFeatureFlagParentFingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "Z",
            parameters = listOf("J", "Z"),
            filters = listOf(
                methodCall(
                    // Due to the structure of the patcher, once a fingerprint is solved, it is cached.
                    // This means there is no need to refer to the parent fingerprint (ExperimentFlagUtilFingerprint).
                    reference = ExperimentalBooleanFeatureFlagFingerprint.method,
                )
            )
        )

        if (hookDoubleFeatureFlag()) ExperimentalDoubleFeatureFlagFingerprint.match(
            experimentalFeatureFlagParentFingerprint.originalClassDef
        ).let {
            it.method.apply {
                // In some versions, freeRegister is not available.
                // The easiest workaround is to copy the method to minimize modifications to the instructions.
                if (implementation!!.registerCount < 8) {
                    throw PatchException("Target method has less than 8 registers")
                }

                // Copy the method.
                val helperMethod = cloneMutable(name = "patch_getDoubleFeatureFlag")

                // Add the method.
                it.classDef.methods.add(helperMethod)

                addInstructions(
                    0,
                    """
                        # Invoke the copied method (helper method).
                        invoke-static/range { p0 .. p4 }, $helperMethod
                        move-result-wide v0
                        
                        # Move parameter registers to lower register range to use invoke-static/range.
                        move-wide v2, p1
                        move-wide v4, p3

                        invoke-static/range { v0 .. v5 }, $EXTENSION_CLASS_DESCRIPTOR->isDoubleFeatureFlagEnabled(DJD)D
                        move-result-wide v0

                        # Since the copied method (helper method) has already been invoked, it just returns.
                        return-wide v0
                    """
                )
            }
        }

        if (hookLongFeatureFlag()) ExperimentalLongFeatureFlagFingerprint.match(
            experimentalFeatureFlagParentFingerprint.originalClassDef
        ).let {
            it.method.apply {
                // In some versions, freeRegister is not available.
                // The easiest workaround is to copy the method to minimize modifications to the instructions.
                if (implementation!!.registerCount < 8) {
                    throw PatchException("Target method has less than 8 registers")
                }

                // Copy the method.
                val helperMethod = cloneMutable(name = "patch_getLongFeatureFlag")

                // Add the method.
                it.classDef.methods.add(helperMethod)

                addInstructions(
                    0,
                    """
                        # Invoke the copied method (helper method).
                        invoke-static/range { p0 .. p4 }, $helperMethod
                        move-result-wide v0
                        
                        # Move parameter registers to lower register range to use invoke-static/range.
                        move-wide v2, p1
                        move-wide v4, p3

                        invoke-static/range { v0 .. v5 }, $EXTENSION_CLASS_DESCRIPTOR->isLongFeatureFlagEnabled(JJJ)J
                        move-result-wide v0

                        # Since the copied method (helper method) has already been invoked, it just returns.
                        return-wide v0
                    """
                )
            }
        }

        if (hookStringFeatureFlag()) ExperimentalStringFeatureFlagFingerprint.match(
            experimentalFeatureFlagParentFingerprint.originalClassDef
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
