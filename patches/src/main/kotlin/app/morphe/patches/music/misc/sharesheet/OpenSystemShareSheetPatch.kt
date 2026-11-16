/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4755
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.sharesheet

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/OpenSystemShareSheetPatch;"

@Suppress("unused")
val openSystemShareSheetPatch = patch(
    name = "Open system share sheet",
    description = "Adds an option to always open the system share sheet instead of the in-app share sheet."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_open_system_share_sheet", summary = true)
        )

        // Without a network connection, the app shares the link of the share entity
        // with the system share sheet. Report no connection when a link can be built.
        ShareEntityServiceEndpointCommandFingerprint.let {
            it.method.apply {
                val endpointRegister = it.instructionMatches[0]
                    .getInstruction<OneRegisterInstruction>().registerA
                val networkConnectedMatch = it.instructionMatches[2]
                val networkConnectedRegister = networkConnectedMatch
                    .getInstruction<OneRegisterInstruction>().registerA
                val serializedShareEntityMatch = it.instructionMatches[3]
                val serializedShareEntityRegister = serializedShareEntityMatch
                    .getInstruction<TwoRegisterInstruction>().registerA

                // The serialized share entity of the endpoint can be empty, with the share entity
                // in a share entity endpoint nested in the command of the endpoint.
                addInstructions(
                    serializedShareEntityMatch.index + 1,
                    """
                        invoke-static { v$serializedShareEntityRegister, v$endpointRegister }, $EXTENSION_CLASS->getSerializedShareEntity(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
                        move-result-object v$serializedShareEntityRegister
                    """
                )

                addInstructions(
                    networkConnectedMatch.index + 1,
                    """
                        invoke-static { v$networkConnectedRegister, v$endpointRegister }, $EXTENSION_CLASS->useInAppShareSheet(ZLjava/lang/Object;)Z
                        move-result v$networkConnectedRegister
                    """
                )
            }
        }
    }
}
