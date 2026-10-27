/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 * https://github.com/MorpheApp/morphe-patches/pull/4109
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.layout.branding.startupAnimationResourcePatch
import app.morphe.patches.shared.misc.lottie.LOTTIE_ANIMATION_VIEW_CLASS_TYPE
import app.morphe.patches.shared.misc.lottie.LottieAnimationViewSetAnimationIntFingerprint
import app.morphe.patches.shared.misc.lottie.lottieAnimationPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.layout.seekbar.LottieSplashScreenFeatureFlagFingerprint
import app.morphe.patches.youtube.layout.seekbar.LottieSplashScreenFeatureFlagLegacyFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_39_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.fiveRegisters
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/theme/ThemePatch;"

/**
 * Hands the animation the app plays while it starts over to extension code,
 * which decides what is played and with which colors.
 */
val splashAnimationPatch = bytecodePatch {

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lottieAnimationPatch,
        versionCheckPatch,
        startupAnimationResourcePatch
    )

    execute {
        // 21.39+ also has the glow Rive animation.
        val arrayKeySuffix = if (is_21_39_or_greater) "_21_39" else ""

        PreferenceScreen.GENERAL.addPreferences(
            ListPreference(
                key = "morphe_splash_screen_style",
                entriesKey = "morphe_splash_screen_style${arrayKeySuffix}_entries",
                entryValuesKey = "morphe_splash_screen_style${arrayKeySuffix}_entry_values"
            )
        )

        // Lottie splash screen exists in earlier versions, but it may not be always on.
        SplashScreenStyleFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->getLoadingScreenType(I)I"
            )
        }

        ShowSplashScreenFingerprint.let {
            it.method.apply {
                val lastIndex = it.instructionMatches.last().index
                val lastInstruction = getInstruction<TwoRegisterInstruction>(lastIndex)
                val lastRegisterA = lastInstruction.registerA
                val lastRegisterB = lastInstruction.registerB

                addInstructions(
                    lastIndex,
                    """
                        invoke-static { v$lastRegisterA, v$lastRegisterB }, $EXTENSION_CLASS->showSplashScreen(II)I
                        move-result v$lastRegisterA
                    """
                )

                val firstIndex = it.instructionMatches[1].index
                val firstRegister = getInstruction<OneRegisterInstruction>(
                    firstIndex
                ).registerA

                addInstructions(
                    firstIndex + 1,
                    """
                        invoke-static { v$firstRegister }, $EXTENSION_CLASS->showSplashScreen(Z)Z
                        move-result v$firstRegister
                    """
                )
            }
        }

        // Force newer Lottie animation.
        (if (is_20_31_or_greater) {
            LottieSplashScreenFeatureFlagFingerprint
        } else {
            LottieSplashScreenFeatureFlagLegacyFingerprint
        }).matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->useLotteLaunchSplashScreen(Z)Z"
            )
        }

        if (is_21_39_or_greater) {
            arrayOf(
                RiveSplashScreenFeatureFlagFingerprint,
                RiveGlowSplashScreenFeatureFlagFingerprint
            ).forEach { fingerprint ->
                fingerprint.matchAll().forEach {
                    it.method.insertLiteralOverride(
                        it.instructionMatches.first().index,
                        "$EXTENSION_CLASS->useRiveSplashScreen(Z)Z"
                    )
                }
            }

            // The themed Rive animation is used instead of the glow animation if it is on.
            RiveThemedSplashScreenFeatureFlagFingerprint.matchAll().forEach {
                it.method.apply {
                    findInstructionIndicesReversedOrThrow(
                        literal(RIVE_THEMED_SPLASH_SCREEN_FEATURE_FLAG)
                    ).forEach { index ->
                        insertLiteralOverride(index, false)
                    }
                }
            }

            RiveSplashScreenAnimationViewFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val viewRegister = getInstruction<TwoRegisterInstruction>(index).registerA

                    addInstruction(
                        index + 1,
                        "invoke-static/range { v$viewRegister .. v$viewRegister }, " +
                            "$EXTENSION_CLASS->setRiveSplashScreenBackground(Landroid/view/View;)V"
                    )
                }
            }

            // The backdrop of the glow animation is changed to the color of the splash screen
            // when the file is read, and the app keeps the file in memory with a key of that color.
            RiveRawResourceSourceFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[1].index
                    val register = getInstruction<TwoRegisterInstruction>(index).registerA

                    addInstructions(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->getRiveRawResourceKey(Ljava/lang/String;)Ljava/lang/String;
                            move-result-object v$register
                        """
                    )
                }
            }

            RiveReadRawResourceFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructions(
                        index,
                        """
                            invoke-static { v$register, p1 }, $EXTENSION_CLASS->getRiveFile([BI)[B
                            move-result-object v$register
                        """
                    )
                }
            }

            // The Rive animation is loaded in the background, and a home feed that is
            // shown quickly closes the animation before it has drawn anything. The animation
            // is instead closed when it ends, or by the timeout of the app if it never does.
            val riveAnimationShownField = RiveSplashScreenViewFingerprint.instructionMatches[1]
                .instruction.getReference<FieldReference>()!!

            SplashScreenOnStartFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                        iget-boolean v0, p0, $riveAnimationShownField
                        if-eqz v0, :close_on_home_feed
                        return-void
                    """,
                    ExternalLabel("close_on_home_feed", getInstruction(0))
                )
            }
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
                replaceInstruction(
                    index,
                    "invoke-static { ${fiveRegisters(index)} }, " +
                        "$EXTENSION_CLASS->setSplashAnimationLottie(Lcom/airbnb/lottie/LottieAnimationView;I)V"
                )
            }
        }
    }
}
