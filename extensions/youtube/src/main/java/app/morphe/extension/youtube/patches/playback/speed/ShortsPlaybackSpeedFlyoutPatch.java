/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3982
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.playback.speed;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;

/**
 * Adds a playback speed item to the flyout menu of the Shorts player.
 */
@SuppressWarnings("unused")
public final class ShortsPlaybackSpeedFlyoutPatch {

    /**
     * Time for the flyout to close, so the speed menu is not dismissed with it.
     */
    private static final long SPEED_MENU_DELAY_MILLISECONDS = 100;

    private static final String ICON_NAME = YouTubeActivityHook.USE_BOLD_ICONS
            ? "yt_outline_experimental_play_circle_half_dashed_vd_theme_24"
            : "yt_outline_play_arrow_half_circle_black_24";

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Called by {@link FlyoutUtils} for the menu of the Shorts player.
     *
     * @return If the menu gets the playback speed item.
     */
    public static boolean showFlyoutButton() {
        return Settings.SHORTS_PLAYBACK_SPEED_FLYOUT_BUTTON.get()
                && !VideoInformation.isLiveStream();
    }

    /**
     * Called by {@link FlyoutUtils} if {@link #showFlyoutButton()} is true.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex) {
        try {
            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    ResourceUtils.getDrawable(ICON_NAME),
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
