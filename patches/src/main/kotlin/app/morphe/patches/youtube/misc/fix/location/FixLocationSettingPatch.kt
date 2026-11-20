/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.location

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.playservice.is_21_16_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal val fixLocationSettingPatch = patch(
    description = "Fixes the Location setting changing back to the country of the device."
) {
    dependsOn(versionCheckPatch)

    execute {
        if (!is_21_16_or_greater) return@execute

        // If the server enables it, the country of the requests is the country stored when the app starts,
        // before the Location setting. The country stored is the country of the requests, so a country chosen
        // in the Location setting is never sent, and the settings of the server then set the Location setting
        // back to the stored country (#5071). The stored country is not used, as if the server does not enable it.
        RequestCountryFingerprint.let {
            val moveResultIndex = it.instructionMatches.last().index
            val register = it.method.getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

            it.method.addInstruction(
                moveResultIndex + 1,
                "const/4 v$register, 0x0"
            )
        }
    }
}
