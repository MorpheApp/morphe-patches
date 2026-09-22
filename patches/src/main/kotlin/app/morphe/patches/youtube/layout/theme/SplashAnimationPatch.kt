/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.lottie.LOTTIE_ANIMATION_VIEW_CLASS_TYPE
import app.morphe.patches.shared.misc.lottie.LottieAnimationViewSetAnimationIntFingerprint
import app.morphe.patches.shared.misc.lottie.lottieAnimationPatch
import app.morphe.patches.youtube.layout.seekbar.LottieSplashScreenFeatureFlagFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/theme/ThemePatch;"

/**
 * Hands the animation the app plays while it starts over to extension code,
 * which decides what is played and with which colors.
 */
val splashAnimationPatch = bytecodePatch {

    dependsOn(sharedExtensionPatch, lottieAnimationPatch)

    execute {
        // Force newer Lottie animation.
        LottieSplashScreenFeatureFlagFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->useLotteLaunchSplashScreen(Z)Z"
            )
        }

        YouTubeActivityOnCreateFingerprint.method.apply {
            val setAnimationIntMethodName =
                LottieAnimationViewSetAnimationIntFingerprint.originalMethod.name

            findInstructionIndicesReversedOrThrow(
                methodCall(
                    definingClass = LOTTIE_ANIMATION_VIEW_CLASS_TYPE,
                    name = setAnimationIntMethodName
                )
            ).forEach { index ->
                val instruction = getInstruction<FiveRegisterInstruction>(index)

                replaceInstruction(
                    index,
                    "invoke-static { v${instruction.registerC}, v${instruction.registerD} }, " +
                        "$EXTENSION_CLASS->setSplashAnimationLottie(Lcom/airbnb/lottie/LottieAnimationView;I)V"
                )
            }
        }
    }
}
