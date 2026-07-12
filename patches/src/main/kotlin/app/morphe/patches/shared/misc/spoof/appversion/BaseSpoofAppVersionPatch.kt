/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.spoof.appversion

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
fun baseSpoofAppVersionPatch(
    defaultTargetString: () -> String,
    preferenceScreen: BasePreferenceScreen.Screen,
    listPreference: () -> ListPreference,
    sharedExtensionClass: String = "Lapp/morphe/extension/shared/spoof/SpoofAppVersionPatch;",
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {}
) = bytecodePatch(
    name = "Spoof app version",
    description = "Adds an option to trick the app into thinking you are running an older version."
) {
    block()

    execute {
        SpoofAppVersionFingerprint.apply {
            val index = instructionMatches.first().index
            val register = method.getInstruction<OneRegisterInstruction>(index).registerA

            method.addInstructions(
                index + 1,
                """
                    invoke-static { v$register }, $sharedExtensionClass->getUniversalAppVersionOverride(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                """
            )
        }

        SpoofAppVersionExtensionDefaultTargetFingerprint.method.returnEarly(defaultTargetString())

        preferenceScreen.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_spoof_app_version", summary = true),
                listPreference()
            )
        )

        executeBlock()
    }
}
