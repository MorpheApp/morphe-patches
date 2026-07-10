/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.auth

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_02_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.request.buildRequestPatch
import app.morphe.patches.youtube.misc.request.hookBuildRequest
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/innertube/utils/AuthUtils;"

internal val authHookPatch = bytecodePatch(
    description = "Hook to get the parameters required for account authentication"
) {
    dependsOn(
        sharedExtensionPatch,
        buildRequestPatch,
        versionCheckPatch,
    )

    execute {
        val (pageIdField, incognitoField) =
            with(AccountIdentityToStringFingerprint.method) {
                Pair(
                    findFieldFromToString(GET_PAGE_ID_STRING),
                    findFieldFromToString(IS_INCOGNITO_STRING)
                )
            }

        val pageIdFingerprints = mutableListOf(getPageIdFingerprint(pageIdField))

        if (is_21_02_or_greater) {
            pageIdFingerprints += isEmptyPageIdFingerprint(pageIdField)
        }

        pageIdFingerprints.forEach {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->setPageId(Ljava/lang/String;)V"
                )
            }
        }

        getIncognitoStatusFingerprint(incognitoField).matchAll().forEach {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->setIncognitoStatus(Z)V"
                )
            }
        }

        hookBuildRequest("$EXTENSION_CLASS->setRequestHeaders(Ljava/lang/String;Ljava/util/Map;)V")
    }
}
