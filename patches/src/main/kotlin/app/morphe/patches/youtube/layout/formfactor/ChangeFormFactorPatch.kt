/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 */

package app.morphe.patches.youtube.layout.formfactor

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.addClientFormFactorHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
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
        navigationBarHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            ListPreference("morphe_change_form_factor")
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
                        invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->getUniversalFormFactor(I)I
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
                "$EXTENSION_CLASS_DESCRIPTOR->replaceBrokenFormFactor(I)I",
            )
        }
    }
}
