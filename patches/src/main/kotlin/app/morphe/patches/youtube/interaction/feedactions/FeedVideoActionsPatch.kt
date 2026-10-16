/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3758
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.feedactions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.feedcards.feedVideoCardsHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FeedVideoActionsPatch;"

@Suppress("unused")
val feedVideoActionsPatch = bytecodePatch(
    name = "Feed video actions",
    description = "Adds a Watch later button to video thumbnails in feeds, " +
            "and opens the video menu by tapping and holding anywhere on a video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        // Finds the video id of the menu, and hooks the menu dialogs.
        flyoutPatch,
        // Account header for the Watch later request.
        authHookPatch,
        // Finds the video of each card, to hide the button while it plays in the feed.
        feedVideoCardsHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_feed_long_press_menu", summary = true),
            SwitchPreference("morphe_feed_watch_later_button", summary = true),
        )

        // Feed videos are Litho components and have no views of their own,
        // so the root host of each video is found when it is laid out
        // and the Watch later button is drawn on its overlay.
        FeedComponentHostLayoutFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstruction(
                    index,
                    "invoke-static { p0 }, $EXTENSION_CLASS->" +
                            "onComponentHostLayout(Lcom/facebook/litho/ComponentHost;)V"
                )
            }
        }

        // The touches of the root host decide the long press and the Watch later button.
        // When the extension handles the touch, it is not given to the children.
        FeedComponentHostTouchFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p1 }, $EXTENSION_CLASS->onComponentHostTouch(Lcom/facebook/litho/ComponentHost;Landroid/view/MotionEvent;)Z
                move-result v0
                if-eqz v0, :original
                return v0
                :original
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
