/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.layout.branding

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.gms.Constants.MUSIC_MAIN_ACTIVITY_NAME
import app.morphe.patches.music.misc.gms.Constants.MUSIC_PACKAGE_NAME
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.layout.branding.EXTENSION_CLASS
import app.morphe.patches.shared.layout.branding.baseCustomBrandingPatch
import app.morphe.patches.shared.misc.lottie.LOTTIE_ANIMATION_VIEW_CLASS_TYPE
import app.morphe.patches.shared.misc.lottie.lottieAnimationPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STARTUP_ANIMATION_EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/StartupAnimationPatch;"

private val startupAnimationPatch = bytecodePatch {
    dependsOn(settingsPatch, lottieAnimationPatch)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            ListPreference("morphe_splash_screen_animation_style")
        )

        // The original animation starts with the original logo, which would flash on screen
        // before a branded app. An icon with its own animation plays that instead, and the
        // animation is turned off for a user provided icon.
        CairoSplashAnimationConfigFingerprint.let {
            it.method.apply {
                // The extension decides which animation is played and with which colors.
                val animationIndex = indexOfFirstInstructionOrThrow(it.instructionMatches.last().index) {
                    val reference = getReference<MethodReference>()
                    opcode == Opcode.INVOKE_VIRTUAL &&
                            reference?.definingClass == LOTTIE_ANIMATION_VIEW_CLASS_TYPE
                }
                val animationInstruction = getInstruction<FiveRegisterInstruction>(animationIndex)

                replaceInstruction(
                    animationIndex,
                    "invoke-static { v${animationInstruction.registerC}, v${animationInstruction.registerD} }, " +
                        "$STARTUP_ANIMATION_EXTENSION_CLASS->setSplashAnimationLottie(Lcom/airbnb/lottie/LottieAnimationView;I)V"
                )

                val checkCastIndex = it.instructionMatches[1].index
                val register = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA

                // A null view bypasses the startup animation.
                addInstructions(
                    checkCastIndex,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->getLottieViewOrNull(Landroid/view/View;)Landroid/view/View;
                        move-result-object v$register
                    """
                )
            }
        }

        // With a warm cache the first content is ready before the delayed animation draws a frame.
        // The end of the animation or the app timeout removes it instead.
        SplashAnimationContentReadyFingerprint.method.returnEarly()
    }
}

@Suppress("unused")
val customBrandingPatch = baseCustomBrandingPatch(
    originalLauncherIconName = "ic_launcher_release",
    originalNotificationIconName = "music_push_notification_white",
    originalAppName = "@string/app_launcher_name",
    originalAppPackageName = MUSIC_PACKAGE_NAME,
    isYouTubeMusic = true,
    numberOfPresetAppNames = 5,
    mainActivityOnCreateFingerprint = MusicActivityOnCreateFingerprint,
    mainActivityName = MUSIC_MAIN_ACTIVITY_NAME,
    activityAliasNameWithIntents = MUSIC_MAIN_ACTIVITY_NAME,
    preferenceScreen = PreferenceScreen.GENERAL,

    block = {
        dependsOn(
            sharedExtensionPatch,
            startupAnimationPatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    }
)
