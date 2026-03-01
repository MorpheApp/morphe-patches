/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/music/patches/ChangeStartPagePatch;"

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
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    ListPreference(
                        key = "morphe_change_start_page",
                        tag = "app.morphe.extension.shared.settings.preference.SortedListPreference"
                    )
                )
            )
        )

        ColdStartUpFingerprint.let {
            it.method.apply {
                val instructions = implementation!!.instructions.toList()
                val defaultBrowseIdIndex = instructions.indexOfFirst { instr ->
                    instr.opcode == Opcode.CONST_STRING &&
                            (instr as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                                ?.reference.let { ref ->
                                    (ref as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string == "FEmusic_home"
                                }
                }

                val browseIdIndex = instructions.withIndex().reversed().firstOrNull { (index, instr) ->
                    index < defaultBrowseIdIndex &&
                            instr.opcode == Opcode.IGET_OBJECT &&
                            (instr as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                                ?.reference.let { ref ->
                                    (ref as? com.android.tools.smali.dexlib2.iface.reference.FieldReference)?.type == "Ljava/lang/String;"
                                }
                }?.index ?: -1

                if (browseIdIndex != -1) {
                    val browseIdRegister = (instructions[browseIdIndex] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA
                    addInstructions(
                        browseIdIndex + 1,
                        "invoke-static/range {v$browseIdRegister .. v$browseIdRegister}, $EXTENSION_CLASS_DESCRIPTOR->overrideBrowseId(Ljava/lang/String;)Ljava/lang/String;\n" +
                                "move-result-object v$browseIdRegister"
                    )
                } else {
                    val returnIndices = instructions.mapIndexedNotNull { index, instr ->
                        if (instr.opcode == Opcode.RETURN_OBJECT) index else null
                    }

                    for (returnIndex in returnIndices.reversed()) {
                        val returnRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                        addInstructions(
                            returnIndex,
                            "invoke-static/range {v$returnRegister .. v$returnRegister}, $EXTENSION_CLASS_DESCRIPTOR->overrideBrowseId(Ljava/lang/String;)Ljava/lang/String;\n" +
                                    "move-result-object v$returnRegister"
                        )
                    }
                }
            }
        }

        MusicActivityOnCreateFingerprint.let {
            it.method.apply {
                val p0 = implementation!!.registerCount - 2
                val p1 = p0 + 1

                addInstruction(
                    0,
                    "invoke-static/range {v$p0 .. v$p1}, $EXTENSION_CLASS_DESCRIPTOR->overrideIntentActionOnCreate(Landroid/app/Activity;Landroid/os/Bundle;)V"
                )
            }
        }
    }
}