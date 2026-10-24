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
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
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

        fun MutableMethod.openSystemShareSheet(index: Int, endpointRegister: Int) {
            val freeRegister = findFreeRegister(index, endpointRegister)

            addInstructionsWithLabels(
                index,
                """
                    invoke-interface/range { v$endpointRegister .. v$endpointRegister }, Lcom/google/protobuf/MessageLite;->toByteArray()[B
                    move-result-object v$freeRegister
                    invoke-static/range { v$freeRegister .. v$freeRegister }, $EXTENSION_CLASS->openSystemShareSheet([B)Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :open_in_app_share_sheet
                    return-void
                    :open_in_app_share_sheet
                    nop
                """
            )
        }

        // The endpoint is unpacked from the command with the extension descriptor,
        // a static field of the endpoint class, and then cast to the endpoint class.
        // Returns false if the method does not unpack the endpoint.
        fun MutableMethod.openSystemShareSheetAfterEndpointCast(): Boolean {
            val staticFieldClasses = mutableSetOf<String>()
            val checkCastIndex = instructions.indexOfFirst { instruction ->
                if (instruction.opcode == Opcode.SGET_OBJECT) {
                    staticFieldClasses += instruction.getReference<FieldReference>()!!.definingClass
                }
                instruction.opcode == Opcode.CHECK_CAST &&
                        instruction.getReference<TypeReference>()!!.type in staticFieldClasses
            }
            if (checkCastIndex < 0) return false

            openSystemShareSheet(
                checkCastIndex + 1,
                getInstruction<OneRegisterInstruction>(checkCastIndex).registerA
            )
            return true
        }

        // Open the system share sheet and skip the share panel request,
        // so the in-app share sheet is never opened.
        if (!ShareEndpointCommandFingerprint.method.openSystemShareSheetAfterEndpointCast()) {
            throw PatchException("Could not find share endpoint cast")
        }

        ShareEntityEndpointCommandFingerprint.method.apply {
            // 20.x unpacks the endpoint in this method, newer versions get the endpoint as p1.
            if (!openSystemShareSheetAfterEndpointCast()) {
                openSystemShareSheet(0, p0Register + 1)
            }
        }
    }
}
