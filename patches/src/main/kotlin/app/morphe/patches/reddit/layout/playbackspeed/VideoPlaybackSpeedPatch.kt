/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5308
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.playbackspeed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/VideoPlaybackSpeedPatch;"

@Suppress("unused")
val videoPlaybackSpeedPatch = patch(
    name = "Video playback speed",
    description = "Adds an option to show Reddit's playback speed controls in the full-screen video player."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        VideoPlaybackSpeedParserFingerprint.let {
            val returnMatch = it.instructionMatches[4]
            val returnRegister = returnMatch.getInstruction<OneRegisterInstruction>().registerA

            it.method.addInstructions(
                returnMatch.index,
                """
                    invoke-static/range { v$returnRegister .. v$returnRegister }, $EXTENSION_CLASS->getVariant(Lcom/reddit/features/VideoPlaybackSpeedVariant;)Lcom/reddit/features/VideoPlaybackSpeedVariant;
                    move-result-object v$returnRegister
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
