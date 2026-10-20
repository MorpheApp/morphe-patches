/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.startpage

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversed
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/ChangeStartPagePatch;"

val changeStartPagePatch = bytecodePatch(
    name = "Change start page",
    description = "Adds an option to set which page the app opens in instead of the homepage.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            ListPreference(
                key = "morphe_change_start_page",
                tag = "app.morphe.extension.shared.settings.preference.SortedListPreference"
            )
        )

        ColdStartUpFingerprint.method.apply {
            val defaultBrowseIdIndex = indexOfFirstStringInstructionOrThrow("FEmusic_home")

            val browseIdIndex = indexOfFirstInstructionReversed(defaultBrowseIdIndex) {
                opcode == Opcode.IGET_OBJECT &&
                        getReference<FieldReference>()?.type == "Ljava/lang/String;"
            }

            if (browseIdIndex >= 0) {
                val browseIdRegister = getInstruction<TwoRegisterInstruction>(browseIdIndex).registerA
                addInstructions(
                    browseIdIndex + 1,
                    """
                        invoke-static/range { v$browseIdRegister .. v$browseIdRegister }, $EXTENSION_CLASS->overrideBrowseId(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$browseIdRegister
                    """
                )
            } else {
                findInstructionIndicesReversed(Opcode.RETURN_OBJECT).forEach { returnIndex ->
                    val returnRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                    addInstructions(
                        returnIndex,
                        """
                            invoke-static/range { v$returnRegister .. v$returnRegister }, $EXTENSION_CLASS->overrideBrowseId(Ljava/lang/String;)Ljava/lang/String;
                            move-result-object v$returnRegister
                        """
                    )
                }
            }
        }

        MusicActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->overrideIntentActionOnCreate(Landroid/app/Activity;Landroid/os/Bundle;)V"
        )
    }
}
