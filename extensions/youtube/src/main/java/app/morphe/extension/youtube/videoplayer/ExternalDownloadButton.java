/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.DownloadsPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class ExternalDownloadButton {

    static {
        if (Settings.EXTERNAL_DOWNLOADER.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    @Nullable
    private static LegacyPlayerControlButton legacy;

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_external_download_button",
                    null,
                    "morphe_yt_download_button",
                    Settings.EXTERNAL_DOWNLOADER::get,
                    ExternalDownloadButton::onDownloadClick,
                    null
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /**
     * injection point.
     */
    public static void setVisibilityNegatedImmediate() {
        if (legacy != null) legacy.setVisibilityNegatedImmediate();
    }

    /**
     * injection point.
     */
    public static void setVisibilityImmediate(boolean visible) {
        if (legacy != null) legacy.setVisibilityImmediate(visible);
    }

    /**
     * injection point.
     */
    public static void setVisibility(boolean visible, boolean animated) {
        if (legacy != null) legacy.setVisibility(visible, animated);
    }

    private static void onDownloadClick(View view) {
        DownloadsPatch.launchExternalDownloader(
                VideoInformation.getVideoId(),
                view.getContext(),
                true);
    }
}

