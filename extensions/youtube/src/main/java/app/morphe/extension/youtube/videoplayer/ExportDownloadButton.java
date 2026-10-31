/*
 * Copyright 2026 Morphe.
 *https://github.com/MorpheApp/morphe-patches/pull/4211
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.youtube.patches.OfflineVideoExporter;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Adds an item to the menus of saved videos, that exports the video to a file on the device.
 */
@SuppressWarnings("unused")
public final class ExportDownloadButton {

    /**
     * The delete item of the menus of the downloads list.
     */
    private static final String DELETE_DOWNLOAD_BUTTON = "OFFLINE_REMOVE";

    @Nullable
    private static Drawable exportIcon;

    private ExportDownloadButton() {
    }

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Called by {@link FlyoutUtils} for the menus of videos.
     *
     * @param menuVideoId The video of the menu, or an empty string if unknown.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex, String menuVideoId) {
        try {
            // Videos are only saved in the app with these download modes.
            if (!Settings.EXPORT_DOWNLOAD_FOLDER.isAvailable()) {
                return nextButtonIndex;
            }

            // The menus of the downloads list have no video id, but they have a delete item.
            final String videoId = menuVideoId.isEmpty()
                    && FlyoutUtils.hasVisibleFlyoutButton(DELETE_DOWNLOAD_BUTTON)
                    ? FlyoutUtils.getFlyoutThumbnailVideoId()
                    : menuVideoId;
            if (videoId.isEmpty() || !OfflineVideoExporter.isExportable(videoId)) {
                return nextButtonIndex;
            }

            if (exportIcon == null) {
                exportIcon = ResourceUtils.getDrawable(ShortsDownloadButton.DOWNLOAD_ICON);
            }

            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    exportIcon,
                    str("morphe_export_download_flyout_title"),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        OfflineVideoExporter.exportVideo(videoId);
                    },
                    nextButtonIndex
            );
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
            return nextButtonIndex;
        }
    }
}
