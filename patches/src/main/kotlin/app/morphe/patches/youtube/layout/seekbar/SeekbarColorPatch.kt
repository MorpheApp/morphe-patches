/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.seekbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.layout.theme.lithoColorHookPatch
import app.morphe.patches.shared.layout.theme.lithoColorOverrideHook
import app.morphe.patches.youtube.layout.theme.splashAnimationPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_34_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_21_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/theme/SeekbarColorPatch;"

val seekbarColorPatch = bytecodePatch(
    description = "Hide or set a custom seekbar color",
) {
    dependsOn(
        sharedExtensionPatch,
        // Owns the hook that loads the splash screen animation.
        splashAnimationPatch,
        versionCheckPatch,
        lithoColorHookPatch { is_21_30_or_greater }
    )

    execute {
        fun MutableMethod.addColorChangeInstructions(index: Int) {
            insertLiteralOverride(
                index,
                "$EXTENSION_CLASS->getVideoPlayerSeekbarColor(I)I"
            )
        }

        PlayerSeekbarColorFingerprint.let {
            it.method.apply {
                addColorChangeInstructions(it.instructionMatches.last().index)
                addColorChangeInstructions(it.instructionMatches.first().index)
            }
        }

        ShortsSeekbarColorFingerprint.let {
            it.method.addColorChangeInstructions(it.instructionMatches.first().index)
        }

        SetSeekbarClickedColorFingerprint.instructionMatches[1].getMethodCalled().apply {
            val colorRegister = getInstruction<TwoRegisterInstruction>(0).registerA
            addInstructions(
                0,
                """
                    invoke-static { v$colorRegister }, $EXTENSION_CLASS->getVideoPlayerSeekbarClickedColor(I)I
                    move-result v$colorRegister
                """
            )
        }

        lithoColorOverrideHook(EXTENSION_CLASS, "getLithoColor")

        if (is_21_21_or_greater) {
            ShortsWhiteSeekbarFeatureFlagFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    false
                )
            }
        }

        var handleBarColorFingerprints = mutableListOf<Fingerprint>(PlayerSeekbarHandle1ColorFingerprint)
        if (!is_20_34_or_greater) {
            handleBarColorFingerprints += PlayerSeekbarHandle2ColorFingerprint
        }
        handleBarColorFingerprints.forEach {
            it.method.addColorChangeInstructions(it.instructionMatches.last().index)
        }

        // If hiding feed seekbar thumbnails, then turn off the gradient of the
        // watch history menu items. They use the same gradient as the player,
        // and there is no easy way to tell which of the two is being drawn.
        WatchHistoryMenuUseProgressDrawableFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[1].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->showWatchHistoryProgressDrawable(Z)Z
                        move-result v$register            
                    """
                )
            }
        }

        LithoLinearGradientFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p4 .. p5 },  $EXTENSION_CLASS->getLithoLinearGradient([I[F)[I
                move-result-object p4   
            """
        )

        PlayerLinearGradientFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                       invoke-static { v$register, p0, p1 }, $EXTENSION_CLASS->getPlayerLinearGradient([III)[I
                       move-result-object v$register
                    """
                )
            }
        }
    }
}
