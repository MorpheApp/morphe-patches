/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.text.TextUtils;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.youtube.patches.utils.requests.SaveToWatchLaterRequest;

@SuppressWarnings("unused")
public final class SaveToWatchLaterPatch {

    /**
     * If the player is not active, the layout may break.
     * Use it only when it is guaranteed to be used in situations where the player is active.
     */
    private static volatile SaveToWatchLaterRequest saveToWatchLaterRequest;

    public static void saveVideo() {
        // Prevent a new request until the previous (if exists) is not done.
        if (saveToWatchLaterRequest != null && !saveToWatchLaterRequest.fetchIsDone()) {
            return;
        }
        if (AuthUtils.isNotLoggedIn()) {
            Utils.showToastShort(str("morphe_queue_manager_check_failed_auth"));
            return;
        }
        String videoId = VideoInformation.getVideoId();
        if (TextUtils.isEmpty(videoId)) {
            Utils.showToastShort(str("morphe_queue_manager_check_failed_video_id"));
            return;
        }
        try {
            Utils.runOnBackgroundThread(() -> {
                saveToWatchLaterRequest = SaveToWatchLaterRequest.fetchRequest(
                        videoId,
                        AuthUtils.getRequestHeader()
                );
                Boolean result = saveToWatchLaterRequest.getResult();
                if (result != null) {
                    Utils.showToastShort(str(result
                            ? "morphe_save_to_watch_later_success_toast"
                            : "morphe_save_to_watch_later_already_exists_toast"));
                } else {
                    Logger.printDebug(() -> "Could not save video, save to watch later results are null: " + videoId);
                }
            });
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch save to watch later request", ex);
            Utils.showToastShort(str("morphe_save_to_watch_later_error_toast"));
        }
    }
}
