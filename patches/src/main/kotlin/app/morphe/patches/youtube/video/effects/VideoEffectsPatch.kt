/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.effects

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.addPlayerFlyoutMenuPreferences
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.playerFlyoutPreferences
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.loadvideo.loadVideoHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.insertLiteralOverride
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/VideoEffectsPatch;"

private val videoEffectsResourcePatch = patch {
    execute {
        copyResources(
            "videoeffects",
            ResourceGroup("drawable", "morphe_ic_video_effect.xml")
        )
    }
}

@Suppress("unused")
val videoEffectsPatch = patch(
    name = "Video effects",
    description = "Adds an option to change the colors of videos or add visual effects, such as black and white. " +
            "This patch works with YouTube 20.51 and newer.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        flyoutPatch,
        loadVideoHookPatch,
        playerFlyoutPreferences,
        videoEffectsResourcePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // 20.26 has an older video renderer without the passthrough shader.
        if (!is_20_51_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Video effects' requires YouTube 20.51+"
            )
        }

        PreferenceScreen.VIDEO.addPreferences(
            ListPreference("morphe_video_effect")
        )
        addPlayerFlyoutMenuPreferences(
            SwitchPreference("morphe_video_effect_button_flyout")
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        GlVideoRendererFeatureFlagFingerprint.matchAll().forEach { match ->
            match.method.insertLiteralOverride(
                match.instructionMatches.first().index,
                "$EXTENSION_CLASS->useGlVideoRenderer(Z)Z"
            )
        }

        // Match the constructor before the draw method is changed, as it is found through the draw method class.
        PassthroughShaderConstructorFingerprint.let {
            it.method.apply {
                // Last shader first, so the index of the first one stays the same.
                listOf(
                    it.instructionMatches[1].index to "getFragmentShader",
                    it.instructionMatches[0].index to "getVertexShader"
                ).forEach { (index, methodName) ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructions(
                        index + 1,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->$methodName(Ljava/lang/String;)Ljava/lang/String;
                            move-result-object v$register
                        """
                    )
                }
            }
        }

        PassthroughShaderDrawFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val programRegister = getInstruction<FiveRegisterInstruction>(index).registerC

                addInstruction(
                    index + 1,
                    "invoke-static { v$programRegister }, $EXTENSION_CLASS->onUseProgram(I)V"
                )
            }
        }
    }
}
