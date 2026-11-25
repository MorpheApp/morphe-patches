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
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/VideoPlaybackSpeedPatch;"

@Suppress("unused")
val videoPlaybackSpeedPatch = patch(
    name = "Video playback speed",
    description = "Adds an option to show Reddit's playback speed controls in the full-screen video player. " +
            "This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Video playback speed' requires Reddit 2026.40.0+"
            )
        }

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
