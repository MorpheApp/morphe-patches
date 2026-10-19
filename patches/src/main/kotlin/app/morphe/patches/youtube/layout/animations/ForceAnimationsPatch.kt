/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3872
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.animations

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_41_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ForceAnimationsPatch;"

@Suppress("unused")
val forceAnimationsPatch = bytecodePatch(
    name = "Force animations",
    description = "Adds an option to always turn on or off the app animations " +
            "that YouTube can turn off, such as the player transitions.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            ListPreference("morphe_force_animations")
        )

        // The server can throttle or turn off each animation category, and then the app skips
        // the animation. For the watch transitions the player then jumps between the miniplayer,
        // the maximized player and its other states.
        AnimationDecisionForCategoryFingerprint.method.apply {
            val decisionType = returnType

            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->getAnimationDecision(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$register
                        check-cast v$register, $decisionType
                    """
                )
            }
        }

        // A/B tests can make the page transitions and the Shorts player skip the decision
        // above. Ask for it whenever the decision is overridden.
        buildList {
            add(PageTransitionAnimationHintsFeatureFlagFingerprint to PAGE_TRANSITION_ANIMATION_HINTS_FEATURE_FLAG)
            if (is_20_41_or_greater) {
                add(ShortsAnimationHintsFeatureFlagFingerprint to SHORTS_ANIMATION_HINTS_FEATURE_FLAG)
            }
        }.forEach { (fingerprint, flag) ->
            fingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    flag,
                    "$EXTENSION_CLASS->useAnimationDecision(Z)Z"
                )
            }
        }
    }
}
