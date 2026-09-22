/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.layout.branding

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.gms.Constants.MUSIC_MAIN_ACTIVITY_NAME
import app.morphe.patches.music.misc.gms.Constants.MUSIC_PACKAGE_NAME
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.layout.branding.EXTENSION_CLASS
import app.morphe.patches.shared.layout.branding.baseCustomBrandingPatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private val startupAnimationPatch = bytecodePatch {
    execute {
        // The original animation starts with the original logo, which would flash on screen
        // before a branded app. An icon with its own animation plays that instead, and the
        // animation is turned off for a user provided icon.
        CairoSplashAnimationConfigFingerprint.method.apply {
            val literalIndex = indexOfFirstLiteralInstructionOrThrow(
                resourceId(ResourceType.LAYOUT, "main_activity_launch_animation")
            )
            val checkCastIndex = indexOfFirstInstructionOrThrow(literalIndex) {
                opcode == Opcode.CHECK_CAST &&
                        getReference<TypeReference>()?.type == "Lcom/airbnb/lottie/LottieAnimationView;"
            }
            val register = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA

            // A null view bypasses the startup animation.
            addInstructions(
                checkCastIndex,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->getLottieViewOrNull(Landroid/view/View;)Landroid/view/View;
                    move-result-object v$register
                """
            )

            val animationIndex = indexOfFirstLiteralInstructionOrThrow(
                resourceId(ResourceType.RAW, "app_launch")
            )
            val animationRegister = getInstruction<OneRegisterInstruction>(animationIndex).registerA

            addInstructions(
                animationIndex + 1,
                """
                    invoke-static { v$animationRegister }, $EXTENSION_CLASS->getStartupAnimation(I)I
                    move-result v$animationRegister
                """
            )
        }
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
