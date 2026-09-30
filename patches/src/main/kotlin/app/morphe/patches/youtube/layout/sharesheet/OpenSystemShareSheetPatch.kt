/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.sharesheet

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/OpenSystemShareSheetPatch;"
private const val EXTENSION_ACTION_SHEET_CONTROLLER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/OpenSystemShareSheetPatch$ActionSheetControllerInterface;"


@Suppress("unused")
internal fun openSystemShareSheetPatch(
) = bytecodePatch(
    name = "Open system share sheet",
    description = "Adds an option to always open the system share sheet instead of the in-app share sheet."
) {

    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_open_system_share_sheet", summary = true)
        )

        // Add interface method to dismiss the action sheet.
        ShowActionSheetCommandFingerprint.let {
            // The only method of the class that takes an optional sheet id.
            val dismissActionSheetMethod = it.classDef.methods.single { method ->
                method.returnType == "V" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes.first().endsWith("/util/Optional;")
            }
            val optionalType = dismissActionSheetMethod.parameterTypes.first()

            it.classDef.apply {
                interfaces.add(EXTENSION_ACTION_SHEET_CONTROLLER_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_dismissActionSheet",
                        listOf(),
                        "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                invoke-static { }, $optionalType->empty()$optionalType
                                move-result-object v0
                                invoke-virtual { p0, v0 }, $dismissActionSheetMethod
                                return-void
                            """
                        )
                    }
                )
            }

            it.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setActionSheetController($EXTENSION_ACTION_SHEET_CONTROLLER_INTERFACE)V"
            )
        }

        // Open the system share sheet and skip the share panel request,
        // so the in-app share sheet is never opened.
        ShareEndpointCommandFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->openSystemShareSheet()Z
                move-result v0
                if-eqz v0, :open_in_app_share_sheet
                return-void
                :open_in_app_share_sheet
                nop
            """
        )
    }
}
