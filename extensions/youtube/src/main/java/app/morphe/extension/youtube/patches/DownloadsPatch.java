/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class DownloadsPatch {

    /**
     * Injection point.
     * <p>
     * Called from the in-app download hook,
     * for both the player action button (below the video)
     * and the 'Download video' flyout option for feed videos.
     * <p>
     * Appears to always be called from the main thread.
     */
    public static boolean inAppDownloadButtonOnClick(String videoId) {
        try {
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.EXTERNAL) {
                return false;
            }

            ExternalDownloaderPreference.launchExternalDownloader(
                    videoId, Utils.getActivity(), "https://youtu.be/" + videoId);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "inAppDownloadButtonOnClick failure", ex);
        }
        return false;
    }

}
