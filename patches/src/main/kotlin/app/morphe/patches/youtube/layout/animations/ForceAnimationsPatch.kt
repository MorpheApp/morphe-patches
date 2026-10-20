/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3872
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.animations

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_41_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_49_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ForceAnimationsPatch;"
private const val EXTENSION_ANIMATIONS_MODE_CLASS =
    $$"Lapp/morphe/extension/youtube/patches/ForceAnimationsPatch$AnimationsMode;"
private const val EXTENSION_SETTINGS_CLASS = "Lapp/morphe/extension/youtube/settings/Settings;"
private const val EXTENSION_ENUM_SETTING_CLASS = "Lapp/morphe/extension/shared/settings/EnumSetting;"

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

        // A/B test: the list below the player stops animating its content when it loads.
        // The flag is turned off when the animations are always on, and on when they are always off.
        if (is_20_49_or_greater) {
            val overrideMethodName = "patch_useFlowLayoutWatchPanel"

            mutableClassDefBy(EXTENSION_CLASS).methods.add(
                ImmutableMethod(
                    EXTENSION_CLASS,
                    overrideMethodName,
                    listOf(ImmutableMethodParameter("Z", null, null)),
                    "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    MutableMethodImplementation(3),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            sget-object v0, $EXTENSION_SETTINGS_CLASS->FORCE_ANIMATIONS:$EXTENSION_ENUM_SETTING_CLASS
                            invoke-virtual { v0 }, $EXTENSION_ENUM_SETTING_CLASS->get()Ljava/lang/Enum;
                            move-result-object v0
                            sget-object v1, $EXTENSION_ANIMATIONS_MODE_CLASS->ALWAYS_ON:$EXTENSION_ANIMATIONS_MODE_CLASS
                            if-ne v0, v1, :not_always_on
                            const/4 p0, 0x0
                            return p0
                            :not_always_on
                            sget-object v1, $EXTENSION_ANIMATIONS_MODE_CLASS->ALWAYS_OFF:$EXTENSION_ANIMATIONS_MODE_CLASS
                            if-ne v0, v1, :server_value
                            const/4 p0, 0x1
                            :server_value
                            return p0
                        """
                    )
                }
            )

            FlowLayoutWatchPanelFeatureFlagFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    FLOW_LAYOUT_WATCH_PANEL_FEATURE_FLAG,
                    "$EXTENSION_CLASS->$overrideMethodName(Z)Z"
                )
            }
        }
    }
}
