/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.mediaviewer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_38_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.p0Register
import app.morphe.util.registersUsed
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/MediaViewerFadePatch;"

private const val SCRIM_STYLE_CLASS = "Lcom/reddit/fullbleedplayer/FbpChromeScrimStyle;"

@Suppress("unused")
val mediaViewerFadePatch = bytecodePatch(
    name = "Media viewer fade",
    description = "Adds an option to lower or remove the dark fade behind the caption when viewing images and videos."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Media viewer fade' requires Reddit 2026.24.0+"
            )
        }

        if (is_2026_38_0_or_greater) {
            // Newer versions pick between several fade styles. Only the legacy gradient
            // has the color stops patched below.
            MediaViewerChromeFingerprint.method.apply {
                // The method is static and has no wide parameters before the style.
                val styleRegister = p0Register + parameterTypes.indexOfFirst { it.toString() == SCRIM_STYLE_CLASS }
                val freeRegister = findFreeRegister(0)
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, $EXTENSION_CLASS->useLegacyFadeStyle()Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :keep_fade_style
                        sget-object v$styleRegister, $SCRIM_STYLE_CLASS->LEGACY_GRADIENT:$SCRIM_STYLE_CLASS
                        :keep_fade_style
                        nop
                    """
                )
            }
        } else {
            // Reddit can swap in a darker static gradient right after building the default one.
            MediaViewerChromeLegacyFingerprint.let {
                it.method.apply {
                    val alternateIndex = it.instructionMatches[3].index
                    // The default gradient is still live in this register when the swap is skipped.
                    val brushRegister = getInstruction<OneRegisterInstruction>(alternateIndex).registerA
                    val freeRegister = findFreeRegister(alternateIndex, brushRegister)
                    addInstructionsWithLabels(
                        alternateIndex,
                        """
                            invoke-static { }, $EXTENSION_CLASS->useAlternateFade()Z
                            move-result v$freeRegister
                            if-eqz v$freeRegister, :keep_default_fade
                        """,
                        ExternalLabel("keep_default_fade", getInstruction(alternateIndex + 1))
                    )
                }
            }
        }

        // The fade is a vertical gradient built from an array of (position, color) pairs.
        // Each color stop is made with Color.copy(alpha).
        MediaViewerChromeFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(mediaViewerFadeAlphaFilter).forEach { index ->
                // Color.copy(long, float): the long uses 2 registers, then the alpha.
                val alphaRegister = getInstruction(index).registersUsed[2]

                addInstructions(
                    index,
                    """
                        invoke-static/range { v$alphaRegister .. v$alphaRegister }, $EXTENSION_CLASS->scaleFadeAlpha(F)F
                        move-result v$alphaRegister
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
