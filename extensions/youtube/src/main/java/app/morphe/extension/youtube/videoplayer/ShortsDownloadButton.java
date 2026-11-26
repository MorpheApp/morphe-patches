/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.patches.DownloadsPatch;
import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.patches.components.LayoutComponentsFilter;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Adds a download item to the menus of Shorts, which the app has no download option for,
 * and to the menus of videos that have no download item, such as videos only Premium members can download.
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
     * Duration of a video, such as 6:01 or 1:02:03, at the start of a text of the card.
     */
    private static final Pattern DURATION_PATTERN = Pattern.compile("(?:^|❙)\\d{1,2}(?::[0-5]\\d){1,2}");

    /**
     * Time for the menu to close, so a menu or dialog opened by the download is not dismissed with it.
     */
    private static final long DOWNLOAD_DELAY_MILLISECONDS = 100;

    @Nullable
    private static Drawable downloadIcon;

    private ShortsDownloadButton() {
    }

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Called by {@link FlyoutUtils} for the menu of the Shorts player and the menus of videos and Shorts in the feed.
     *
     * @param videoId    The video of the menu, or an empty string if unknown.
     * @param isShort    If the menu is of a Short.
     * @param menuBuffer The buffer of the card the menu belongs to, if the menu has one.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex, String videoId, boolean isShort,
                                      @Nullable byte[] menuBuffer) {
        try {
            if (videoId.isEmpty()) {
                return nextButtonIndex;
            }
            BooleanSetting setting = isShort
                    ? Settings.SHORTS_DOWNLOAD_FLYOUT_BUTTON
                    : Settings.FEED_DOWNLOAD_FLYOUT_BUTTON;
            if (!setting.get() || !setting.isAvailable()
                    // Only if the menu has no download item of the app, otherwise there are two items
                    // for the same action. The menus of playlists have no video to download.
                    || (!isShort && (FlyoutUtils.hasAppDownloadButton() || !FlyoutUtils.getFlyoutPlaylistId().isEmpty()
                    // Live streams and upcoming videos cannot be downloaded,
                    // and unlike the other videos their card has no duration.
                    || menuBuffer == null
                    || !DURATION_PATTERN.matcher(new BufferAsciiStrings(menuBuffer).getStrings()).find()))) {
                return nextButtonIndex;
            }

            String text = str("morphe_download");
            // The menus leave out the download item of the app when its text is hidden
            // by "Hide feed flyout menu", so this item is left out as well.
            if (!isShort && Settings.HIDE_FEED_FLYOUT_MENU.get()
                    && (LayoutComponentsFilter.isFlyoutMenuFiltered(text)
                    || LayoutComponentsFilter.isFlyoutMenuFiltered(Objects.requireNonNull(
                            ResourceUtils.getString("menu_offline_placeholder"))))) {
                return nextButtonIndex;
            }

            if (downloadIcon == null) {
                downloadIcon = ResourceUtils.getDrawable(DOWNLOAD_ICON);
            }

            View.OnClickListener onClick = v -> {
                FlyoutUtils.dismissFlyout();
                Utils.runOnMainThreadDelayed(
                        () -> DownloadsPatch.downloadVideoWithoutAppButton(videoId),
                        DOWNLOAD_DELAY_MILLISECONDS
                );
            };
            // The app can bind its download item after this item is added.
            return isShort
                    ? FlyoutUtils.addFlyoutButton(flyoutPanel, downloadIcon, text, onClick, nextButtonIndex)
                    : FlyoutUtils.addFlyoutSaveButton(flyoutPanel, downloadIcon, text, onClick, nextButtonIndex, true);
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
            return nextButtonIndex;
        }
    }
}
