/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton.ButtonVisibility;

@SuppressWarnings("unused")
public class ExternalDownloadButton {

    static {
        if (isEnabled()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_external_download_button",
                    null,
                    "morphe_yt_download_button",
                    () -> isEnabled()
                            ? ButtonVisibility.ENABLED
                            : ButtonVisibility.DISABLED,
                    ExternalDownloadButton::onDownloadClick,
                    null
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    private static boolean isEnabled() {
        return Settings.EXTERNAL_DOWNLOADER_OVERLAY_BUTTON.get();
    }

    private static void onDownloadClick(View view) {
        ExternalDownloaderPreference.launchExternalDownloader(VideoInformation.getVideoId(),
                view.getContext(), "https://youtu.be/" + VideoInformation.getVideoId());
    }
}
