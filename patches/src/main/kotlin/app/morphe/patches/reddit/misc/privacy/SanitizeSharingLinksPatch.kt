/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SanitizeSharingLinksPatch;"

private const val SHORT_URL_ERROR_CLASS =
    "Lcom/reddit/sharing/custom/url/shortening/ShortUrlError;"

val sanitizeSharingLinksPatch = patch(
    name = "Sanitize sharing links",
    description = "Adds an option to sanitize sharing links by removing tracking query parameters."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        ShareLinkFormatterFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS->stripQueryParameters()Z
                move-result v0
                if-eqz v0, :off
                return-object p0
                :off
                nop
            """
        )

        // Do not request short links, so the full link is shared instead.
        GetShortUrlFingerprint.method.apply {
            // 2026.24+ returns a result that is either the short link or a ShortUrlError.
            // Older versions return the short link, or null if the request failed.
            val errorField = implementation!!.instructions.mapNotNull { instruction ->
                if (instruction.opcode != Opcode.SGET_OBJECT) return@mapNotNull null
                (instruction as ReferenceInstruction).getReference<FieldReference>()
                    ?.takeIf { it.type == SHORT_URL_ERROR_CLASS }
            }.let { errorFields ->
                errorFields.firstOrNull { it.name == "UNKNOWN_ERROR" } ?: errorFields.firstOrNull()
            }

            val failedResult = if (errorField == null) {
                """
                    const/4 v0, 0x0
                    return-object v0
                """
            } else {
                // The class that wraps an error, created right before the error is read or right after.
                val errorIndex = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.SGET_OBJECT && getReference<FieldReference>()?.type == SHORT_URL_ERROR_CLASS
                }
                val errorConstructor = (errorIndex..errorIndex + 3).firstNotNullOfOrNull { index ->
                    getInstruction(index).takeIf { it.opcode == Opcode.INVOKE_DIRECT }
                        ?.let { (it as ReferenceInstruction).getReference<MethodReference>() }
                        ?.takeIf { it.name == "<init>" && it.parameterTypes.size == 1 }
                } ?: throw PatchException("Could not find the short link error result")

                """
                    sget-object v0, $errorField
                    new-instance v1, ${errorConstructor.definingClass}
                    invoke-direct { v1, v0 }, $errorConstructor
                    return-object v1
                """
            }

            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS->disableShortLinks()Z
                    move-result v0
                    if-eqz v0, :off
                    $failedResult
                    :off
                    nop
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
