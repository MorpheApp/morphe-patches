/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.playback.speed;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Pair;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Adds a playback speed item to the flyout menu of the Shorts player.
 */
@SuppressWarnings("unused")
public final class ShortsPlaybackSpeedFlyoutPatch {

    /**
     * App icons first, so the item matches the other items of the menu.
     */
    private static final List<String> ICON_NAMES = List.of(
            "yt_outline_experimental_play_arrow_half_circle_vd_theme_24",
            "yt_outline_play_arrow_half_circle_black_24",
            "morphe_ic_slow_motion_video"
    );

    /**
     * Items of the menu of the Shorts player, to tell it apart from the other
     * bottom sheets opened in the Shorts player, such as the share sheet.
     */
    private static final List<String> SHORTS_MENU_BUTTONS = List.of(
            "ALIGN_LEFT", // Description.
            "NERD_STATS", // Stats for nerds.
            "FLAG",       // Report.
            "FEEDBACK"    // Send feedback.
    );

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Called by {@link FlyoutUtils} for every flyout menu that opens.
     *
     * @param isTopFlyout   If the menu is the settings menu of the player.
     *                      The menu of the Shorts player is also detected as one.
     * @param isShortFlyout If the menu belongs to a Short in the feed or on a channel.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex,
                                      boolean isTopFlyout, boolean isShortFlyout) {
        try {
            if (!Settings.SHORTS_PLAYBACK_SPEED_FLYOUT_BUTTON.get() || !ShortsPlayerState.isOpen()) {
                return nextButtonIndex;
            }
            if (isShortFlyout
                    || !isShortsPlayerMenu()
                    || VideoInformation.isLiveStream()
                    // Menus opened inside a panel, such as the menu of a comment.
                    || EngagementPanel.getLastOpenedPanelId() != null) {
                Logger.printDebug(() -> "Shorts speed flyout skipped, top: " + isTopFlyout
                        + " feed short: " + isShortFlyout
                        + " buttons: " + FlyoutUtils.getVisibleFlyoutButtons()
                        + " panel: " + EngagementPanel.getLastOpenedPanelId());
                return nextButtonIndex;
            }

            String title = str("morphe_shorts_playback_speed_flyout_title",
                    VideoInformation.formatSpeedStringX(VideoInformation.getPlaybackSpeed()));

            final int index = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    getIcon(),
                    title,
                    v -> {
                        Context context = v.getContext();
                        FlyoutUtils.dismissFlyout();
                        showSpeedMenu(context);
                    },
                    nextButtonIndex
            );
            Logger.printDebug(() -> "Shorts speed flyout added: " + (index >= 0));
            return index < 0 ? nextButtonIndex : index;
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
            return nextButtonIndex;
        }
    }

    private static boolean isShortsPlayerMenu() {
        for (Pair<String, Integer> button : FlyoutUtils.getVisibleFlyoutButtons()) {
            if (SHORTS_MENU_BUTTONS.contains(button.first)) {
                return true;
            }
        }
        return false;
    }

    private static void showSpeedMenu(Context context) {
        // Wait for the flyout to close, so the speed menu is not dismissed with it.
        Utils.runOnMainThreadDelayed(() -> {
            try {
                CustomPlaybackSpeedPatch.showModernCustomPlaybackSpeedDialog(context);
            } catch (Exception ex) {
                Logger.printException(() -> "showSpeedMenu failure", ex);
            }
        }, 100);
    }

    @Nullable
    private static Drawable getIcon() {
        for (String name : ICON_NAMES) {
            if (ResourceUtils.getIdentifier(ResourceType.DRAWABLE, name) != 0) {
                return ResourceUtils.getDrawable(name);
            }
        }
        return null;
    }
}
