/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3337
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.statusbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/HideStatusBarPatch;"

@Suppress("unused")
val hideStatusBarPatch = bytecodePatch(
    name = "Hide status bar",
    description = "Adds an option to hide the system status bar. Swipe down from the top edge to show it for a moment.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_hide_status_bar", summary = true),
            SwitchPreference("morphe_fix_status_bar_layout", summary = true)
        )

        // https://github.com/MorpheApp/morphe-patches/issues/2969
        // The flag is read by many classes, and on some devices they do not end up with the same value.
        // The window is then drawn behind the status bar, but the top bars are not given the status bar inset,
        // so the status bar and its background view cover them. Every class uses the first value read,
        // instead of always turning the flag off: the edge to edge layout is the stock layout of most accounts
        // (transparent status bar over Shorts and the player). The flag is turned off only when the
        // status bar is hidden, as it then leaves an empty bar above the player.
        EdgeToEdgeTopBarFeatureFlagFingerprint.matchAll().forEach {
            // Overrides every read of the flag in the method.
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->useEdgeToEdgeTopBar(Z)Z"
            )
        }

        // The status bar color that follows the scrolling top bar needs the edge to edge top bar.
        StatusBarScrollColorFeatureFlagFingerprint.matchAllOrNull()?.forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->useStatusBarScrollColor(Z)Z"
            )
        }

        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize(Landroid/app/Activity;)V",
        )

        // The status bar background view keeps the height of the hidden status bar
        // and is drawn over the top bars, making them invisible or clipped.
        StatusBarBackgroundShowFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val viewRegister = getInstruction<FiveRegisterInstruction>(index).registerC

                addInstruction(
                    index + 1,
                    "invoke-static { v$viewRegister }, $EXTENSION_CLASS->hideStatusBarBackground(Landroid/view/View;)V"
                )
            }
        }

        // Without the edge to edge top bar, the top bars are laid out with the stable insets,
        // which keep the height of the hidden status bar.
        SystemWindowInsetsChangedFingerprint.let {
            it.method.apply {
                val insetsRegister = it.instructionMatches.first()
                    .getInstruction<FiveRegisterInstruction>().registerC
                val viewIndex = it.instructionMatches.last().index
                val viewRegister = getInstruction<TwoRegisterInstruction>(viewIndex).registerA

                addInstruction(
                    viewIndex + 1,
                    "invoke-static { v$insetsRegister, v$viewRegister }, " +
                            "$EXTENSION_CLASS->setSystemWindowInsetTop(Landroid/graphics/Rect;Landroid/view/View;)V"
                )
            }
        }
    }
}
