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

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.DownloadsPatch;
import app.morphe.extension.youtube.patches.VideoInformation;

@SuppressWarnings("unused")
public class ExternalDownloadButton {
    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
//            Settings.EXTERNAL_DOWNLOADER::get
            PlayerOverlayButton.addButton(controlsView,
                    "morphe_yt_download_button",
                    ExternalDownloadButton::onDownloadClick,
                    null
            );

        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    private static void onDownloadClick(View view) {
        DownloadsPatch.launchExternalDownloader(
                VideoInformation.getVideoId(),
                view.getContext(),
                true);
    }
}

