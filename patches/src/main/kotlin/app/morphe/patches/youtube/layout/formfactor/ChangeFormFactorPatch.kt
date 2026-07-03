/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.formfactor

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.addClientFormFactorHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.fix.videoactionbar.restoreOldVideoActionBarPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ChangeFormFactorPatch;"

@Suppress("unused")
val changeFormFactorPatch = bytecodePatch(
    name = "Change form factor",
    description = "Adds an option to change the UI appearance to a phone, tablet, or automotive device.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        clientContextHookPatch,
        navigationBarHookPatch,
        restoreOldVideoActionBarPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            noTitleUnsortedPreferenceCategory(
                ListPreference("morphe_change_form_factor"),
                SwitchPreference("morphe_tablet_layout_in_player", summary = true)
            )
        )

        val createPlayerRequestBodyWithModelFingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "L",
            parameters = listOf(),
            filters = listOf(
                fieldAccess(smali = "Landroid/os/Build;->MODEL:Ljava/lang/String;"),
                fieldAccess(
                    definingClass = FormFactorEnumConstructorFingerprint.originalClassDef.type,
                    type = "I",
                    location = MatchAfterWithin(50)
                )
            )
        )

        createPlayerRequestBodyWithModelFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->getUniversalFormFactor(I)I
                        move-result v$register
                    """
                )
            }
        }

        setOf(
            Endpoint.GET_WATCH,
            Endpoint.NEXT,
            Endpoint.GUIDE,
            Endpoint.REEL,
        ).forEach { endpoint ->
            addClientFormFactorHook(
                endpoint,
                "$EXTENSION_CLASS->replaceBrokenFormFactor(I)I",
            )
        }

        PlayerLithoElementsListFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<BuilderInstruction22c>(index).registerA
                val free = findFreeRegister(index, register)

                addInstructionsWithLabels(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->checkPlayerLithoElementsListSize(Ljava/util/List;)Z
                        move-result v$free
                        if-eqz v$free, :empty_list_check
                        return-void
                        :empty_list_check
                        nop
                    """
                )
            }
        }
    }
}
