/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */
package app.morphe.patches.youtube.layout.shortsnoresume

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_03_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisableShortsResumingOnStartupPatch;"

@Suppress("unused")
val disableShortsResumingOnStartupPatch = bytecodePatch(
    name = "Disable Shorts resuming on startup",
    description = "Adds an option to disable Shorts from resuming on app startup when Shorts were last being watched.",
) {
    category("Layout")

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_disable_shorts_resuming_on_startup"),
        )

        if (is_21_03_or_greater) {
            UserWasInShortsEvaluateFingerprint.let { fingerprint ->
                fingerprint.method.apply {
                    val match = fingerprint.instructionMatches.first()
                    val instruction = match.instruction as RegisterRangeInstruction
                    val zMRegister = instruction.startRegister + 2

                    addInstructions(
                        match.index,
                        """
                            invoke-static { v$zMRegister }, $EXTENSION_CLASS->disableShortsResumingOnStartup(Z)Z
                            move-result v$zMRegister
                        """
                    )
                }
            }
        } else {
            UserWasInShortsListenerFingerprint.let { fingerprint ->
                fingerprint.method.apply {
                    val match = fingerprint.instructionMatches[2]
                    val insertIndex = match.index + 1
                    val register = match.getInstruction<OneRegisterInstruction>().registerA

                    addInstructions(
                        insertIndex,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->disableShortsResumingOnStartup(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        UserWasInShortsConfigFingerprint.matchAll().forEach {
            // 21.30+ inlines the flag lookup and must patch ~2 places.
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->disableShortsResumingOnStartup(Z)Z"
            )
        }
    }
}
