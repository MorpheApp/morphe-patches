/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.ad.hideFullscreenAdsPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/HideAdsPatch;"

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Adds options to hide fullscreen ads, Premium promotions and video ads."
) {
    dependsOn(
        sharedExtensionPatch,
        hideFullscreenAdsPatch(PreferenceScreen.ADS),
        settingsPatch,
        resourceMappingPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.ADS.addPreferences(
            SwitchPreference("morphe_music_hide_get_premium_label"),
            SwitchPreference("morphe_music_hide_music_premium_promotions"),
            SwitchPreference("morphe_music_hide_video_ads"),
        )

        // Hide 'Get Music Premium' label
        HideGetPremiumFingerprint.method.apply {
            val insertIndex = HideGetPremiumFingerprint.instructionMatches.last().index

            val setVisibilityInstruction = getInstruction<FiveRegisterInstruction>(insertIndex)
            val getPremiumViewRegister = setVisibilityInstruction.registerC
            val visibilityRegister = setVisibilityInstruction.registerD

            replaceInstruction(
                insertIndex,
                "const/16 v$visibilityRegister, 0x8",
            )

            addInstruction(
                insertIndex + 1,
                "invoke-virtual {v$getPremiumViewRegister, v$visibilityRegister}, " +
                        "Landroid/view/View;->setVisibility(I)V",
            )
        }

        MembershipSettingsFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->hideGetPremiumLabel()Z
                move-result v0
                if-eqz v0, :show
                const/4 v0, 0x0
                return-object v0
                :show
                nop
            """
        )

        // Hide video ads
        ShowVideoAdsFingerprint.instructionMatches[1].getMethodCalled().addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->hideVideoAds(Z)Z
                move-result p1
            """
        )

        // Hide Music Premium promotions
        FloatingLayoutFingerprint.method.apply {
            val insertIndex = FloatingLayoutFingerprint.instructionMatches.last().index
            val viewRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

            addInstruction(
                insertIndex + 1,
                "invoke-static { v$viewRegister }, $EXTENSION_CLASS->" +
                        "hidePremiumPromotionBottomSheet(Landroid/view/View;)V"
            )
        }
    }
}
