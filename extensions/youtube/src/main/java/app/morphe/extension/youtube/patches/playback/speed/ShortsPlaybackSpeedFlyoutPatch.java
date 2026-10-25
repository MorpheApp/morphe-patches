/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3982
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.playback.speed;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.Pair;

import java.util.List;

import app.morphe.extension.shared.Logger;
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
     * Items of the menu of the Shorts player, to tell it apart from the other
     * bottom sheets opened in the Shorts player, such as the share sheet.
     */
    private static final List<String> SHORTS_MENU_BUTTONS = List.of(
            "ALIGN_LEFT", // Description.
            "NERD_STATS", // Stats for nerds.
            "FLAG",       // Report.
            "FEEDBACK"    // Send feedback.
    );

    /**
     * Time for the flyout to close, so the speed menu is not dismissed with it.
     */
    private static final long SPEED_MENU_DELAY_MILLISECONDS = 100;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Called by {@link FlyoutUtils} for every flyout menu that opens.
     *
     * @param isShortFlyout If the menu belongs to a Short in the feed or on a channel.
     * @return If the menu is the menu of the Shorts player and gets the playback speed item.
     */
    public static boolean showFlyoutButton(boolean isShortFlyout) {
        return Settings.SHORTS_PLAYBACK_SPEED_FLYOUT_BUTTON.get()
                && ShortsPlayerState.isOpen()
                && !isShortFlyout
                && !VideoInformation.isLiveStream()
                // Menus opened inside a panel, such as the menu of a comment.
                && EngagementPanel.getLastOpenedPanelId() == null
                && isShortsPlayerMenu();
    }

    private static boolean isShortsPlayerMenu() {
        for (Pair<String, Integer> button : FlyoutUtils.getVisibleFlyoutButtons()) {
            if (SHORTS_MENU_BUTTONS.contains(button.first)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called by {@link FlyoutUtils} if {@link #showFlyoutButton(boolean)} is true.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex) {
        try {
            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    ResourceUtils.getDrawable("yt_outline_play_arrow_half_circle_black_24"),
                    str("morphe_shorts_playback_speed_flyout_title",
                            VideoInformation.formatSpeedStringX(VideoInformation.getPlaybackSpeed())),
                    v -> {
                        Context context = v.getContext();
                        FlyoutUtils.dismissFlyout();
                        showSpeedMenu(context);
                    },
                    nextButtonIndex
            );
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
            return nextButtonIndex;
        }
    }

    private static void showSpeedMenu(Context context) {
        Utils.runOnMainThreadDelayed(() -> {
            try {
                CustomPlaybackSpeedPatch.showModernCustomPlaybackSpeedDialog(context);
            } catch (Exception ex) {
                Logger.printException(() -> "showSpeedMenu failure", ex);
            }
        }, SPEED_MENU_DELAY_MILLISECONDS);
    }
}
