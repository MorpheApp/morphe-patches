/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4916
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.sharecontent

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_44_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SharePostContentPatch;"

val sharePostContentPatch = patch(
    name = "Share post content",
    description = "Adds an option to share the image, video or text of a post instead of its link " +
        "when using app shortcuts or \"Share via…\" in the share sheet."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        val appTargetFingerprint = if (is_2026_44_0_or_greater) {
            SharePostAppTargetFingerprint
        } else {
            SharePostAppTargetLegacyFingerprint
        }

        SharePostSubjectFingerprint.matchAll().forEach { subject ->
            // Matched again for each handler class.
            appTargetFingerprint.clearMatch()
            appTargetFingerprint.match(subject.classDef).let {
                val launch = it.instructionMatches.last()
                val registers = launch.instruction.registersUsed
                // Launcher (context, intent, package), or Activity.startActivity(intent).
                val contextRegister = registers[if (is_2026_44_0_or_greater) 1 else 0]
                val intentRegister = registers[if (is_2026_44_0_or_greater) 2 else 1]
                val freeRegister = it.method.findFreeRegister(launch.index, contextRegister, intentRegister)
                val afterLaunch = it.method.getInstruction(launch.index + 1)

                it.method.addInstructionsWithLabels(
                    launch.index,
                    """
                        invoke-static { v$contextRegister, v$intentRegister }, $EXTENSION_CLASS->onShare(Landroid/content/Context;Landroid/content/Intent;)Z
                        move-result v$freeRegister
                        if-nez v$freeRegister, :handled
                    """,
                    ExternalLabel("handled", afterLaunch)
                )
            }

            val checkCast = subject.instructionMatches.first()
            val linkRegister = checkCast.getInstruction<OneRegisterInstruction>().registerA

            subject.method.addInstruction(
                checkCast.index + 1,
                "invoke-static/range { v$linkRegister .. v$linkRegister }, $EXTENSION_CLASS->" +
                    "setLink(Lcom/reddit/domain/model/Link;)V"
            )
            // Posts are only loaded for post shares. Clear the post of an earlier share
            // that did not open a share target.
            subject.method.addInstruction(
                0,
                "invoke-static { }, $EXTENSION_CLASS->clearLink()V"
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
