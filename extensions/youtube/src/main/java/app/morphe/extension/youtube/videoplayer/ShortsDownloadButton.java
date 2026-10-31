/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.patches.DownloadsPatch;
import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Adds a download item to the menus of Shorts, which the app has no download option for.
 * The item downloads with the download mode that is set, with an external downloader or in-app.
 */
@SuppressWarnings("unused")
public final class ShortsDownloadButton {

    private static final boolean USE_EXPERIMENTAL_ICONS = VersionCheckPatch.IS_20_31_OR_GREATER
            && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");

    static final String DOWNLOAD_ICON = USE_EXPERIMENTAL_ICONS
            ? "yt_outline_experimental_download_vd_theme_24"
            : "yt_outline_download_black_24";

    /**
     * Time for the menu to close, so a menu or dialog opened by the download is not dismissed with it.
     */
    private static final long DOWNLOAD_DELAY_MILLISECONDS = 100;

    @Nullable
    private static Drawable downloadIcon;

    private ShortsDownloadButton() {
    }

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Called by {@link FlyoutUtils} for the menu of the Shorts player and the menus of Shorts in the feed.
     *
     * @param videoId The Short of the menu, or an empty string if unknown.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex, String videoId) {
        try {
            if (videoId.isEmpty()
                    || !Settings.SHORTS_DOWNLOAD_FLYOUT_BUTTON.get()
                    || !Settings.SHORTS_DOWNLOAD_FLYOUT_BUTTON.isAvailable()) {
                return nextButtonIndex;
            }

            if (downloadIcon == null) {
                downloadIcon = ResourceUtils.getDrawable(DOWNLOAD_ICON);
            }

            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    downloadIcon,
                    str("morphe_shorts_download_flyout_title"),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        Utils.runOnMainThreadDelayed(
                                () -> DownloadsPatch.downloadVideoWithoutAppButton(videoId),
                                DOWNLOAD_DELAY_MILLISECONDS
                        );
                    },
                    nextButtonIndex
            );
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
            return nextButtonIndex;
        }
    }
}
