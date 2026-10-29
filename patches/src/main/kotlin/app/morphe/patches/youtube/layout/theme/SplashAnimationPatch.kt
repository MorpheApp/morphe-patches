/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 * https://github.com/MorpheApp/morphe-patches/pull/4109
 * https://github.com/MorpheApp/morphe-patches/pull/4135
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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.shared.layout.branding.startupAnimationResourcePatch
import app.morphe.patches.shared.misc.lottie.LOTTIE_ANIMATION_VIEW_CLASS_TYPE
import app.morphe.patches.shared.misc.lottie.LottieAnimationViewSetAnimationIntFingerprint
import app.morphe.patches.shared.misc.lottie.lottieAnimationPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.layout.seekbar.LottieSplashScreenFeatureFlagFingerprint
import app.morphe.patches.youtube.layout.seekbar.LottieSplashScreenFeatureFlagLegacyFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_32_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_35_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_39_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.fiveRegisters
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

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
        startupAnimationResourcePatch,
        glowMorpheAnimationResourcePatch
    )

    execute {
        // 21.30+ also has the Morphe glow Rive animation, and 21.39+ has the glow animation
        // with the YouTube logo as well.
        val arrayKeySuffix = when {
            is_21_39_or_greater -> "_21_39"
            is_21_30_or_greater -> "_21_30"
            else -> ""
        }

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

        // The Rive styles are not available before 21.30:
        // - 21.21 and earlier do not include the Rive library.
        // - 21.22 - 21.28 play the Rive startup animation with an older Rive view, but 21.22 - 21.23
        //   crash when the view is created, because the app build removed Java code that the native
        //   Rive library calls. 21.24 - 21.28 are only experimental targets and are not supported.
        // - 21.29 does not include the native library of the older view, and does not have
        //   the newer Rive startup animation of 21.30+.
        if (is_21_30_or_greater) {
            // The Morphe glow animation is the default style.
            SplashAnimationIsRiveIncludedFingerprint.method.returnEarly(true)

            RiveSplashScreenFeatureFlagFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->useRiveSplashScreen(Z)Z"
                )
            }

            // 21.39+ plays the glow animation if this flag is on, and otherwise the startup animation
            // of the light or the dark theme, which the Morphe Glow style replaces. Before 21.39 the flag
            // selects the older Rive view, which crashes because its library is not included.
            RiveGlowSplashScreenFeatureFlagFingerprint.matchAll().forEach {
                if (is_21_39_or_greater) {
                    it.method.insertLiteralOverride(
                        it.instructionMatches.first().index,
                        "$EXTENSION_CLASS->useRiveGlowSplashScreen(Z)Z"
                    )
                } else {
                    it.method.insertLiteralOverride(it.instructionMatches.first().index, false)
                }
            }

            // The themed Rive animation of 21.32+ is used instead of the glow animation if it is on.
            if (is_21_32_or_greater) {
                RiveThemedSplashScreenFeatureFlagFingerprint.matchAll().forEach {
                    it.method.apply {
                        findInstructionIndicesReversedOrThrow(
                            literal(RIVE_THEMED_SPLASH_SCREEN_FEATURE_FLAG)
                        ).forEach { index ->
                            insertLiteralOverride(index, false)
                        }
                    }
                }
            }

            // The match of the instruction that sets the view field.
            val (animationViewFingerprint, animationViewMatchIndex) =
                if (is_21_32_or_greater && !is_21_35_or_greater) {
                    RiveSplashScreenAnimationViewLegacyFingerprint to 3
                } else {
                    RiveSplashScreenAnimationViewFingerprint to 4
                }

            animationViewFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[animationViewMatchIndex].index
                    val viewRegister = getInstruction<TwoRegisterInstruction>(index).registerA

                    addInstruction(
                        index + 1,
                        "invoke-static/range { v$viewRegister .. v$viewRegister }, " +
                            "$EXTENSION_CLASS->setRiveSplashScreenBackground(Landroid/view/View;)V"
                    )
                }
            }

            // The backdrop of the glow animation is changed to the color of the splash screen
            // when the file is read, and the animation is given the shape of the screen.
            // The app keeps the file in memory with a key of that color and shape.
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

            // The system splash screen is kept until the glow animation has drawn,
            // otherwise a blank screen is shown while the animation loads.
            RiveCanvasSessionSetLatestBitmapFingerprint.method.addInstruction(
                0,
                "invoke-static { }, $EXTENSION_CLASS->onRiveFrameAvailable()V"
            )

            SplashScreenOnStopFingerprint.method.addInstruction(
                0,
                "invoke-static { }, $EXTENSION_CLASS->onSplashScreenStop()V"
            )

            // The splash screen is a Runnable that removes it, so the extension can close
            // the Morphe glow animation when it has played.
            SplashScreenRemoveFingerprint.let {
                it.classDef.apply {
                    interfaces.add("Ljava/lang/Runnable;")

                    ImmutableMethod(
                        type,
                        "run",
                        emptyList(),
                        "V",
                        AccessFlags.PUBLIC.value,
                        null,
                        null,
                        MutableMethodImplementation(1)
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                invoke-virtual { p0 }, ${it.method}
                                return-void
                            """
                        )
                    }.let(methods::add)
                }
            }

            // The Rive animation is loaded in the background, and a home feed that is
            // shown quickly closes the animation before it has drawn anything. On the first
            // start the animation is instead closed by the timeout of the app, or by the
            // extension when the Morphe glow animation has played.
            val riveAnimationShownField = RiveSplashScreenViewFingerprint.instructionMatches[1]
                .instruction.getReference<FieldReference>()!!

            SplashScreenOnStartFingerprint.method.addInstructionsWithLabels(
                0,
                """
                    iget-boolean v0, p0, $riveAnimationShownField
                    invoke-static { p0, v0 }, $EXTENSION_CLASS->skipSplashScreenCloseOnHomeFeed(Ljava/lang/Runnable;Z)Z
                    move-result v0
                    if-eqz v0, :close_on_home_feed
                    return-void
                    :close_on_home_feed
                    nop
                """
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
                replaceInstruction(
                    index,
                    "invoke-static { ${fiveRegisters(index)} }, " +
                        "$EXTENSION_CLASS->setSplashAnimationLottie(Lcom/airbnb/lottie/LottieAnimationView;I)V"
                )
            }
        }
    }
}
