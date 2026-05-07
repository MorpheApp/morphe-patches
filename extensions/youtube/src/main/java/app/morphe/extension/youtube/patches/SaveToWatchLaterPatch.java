/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.PlayerRoutes;
import app.morphe.extension.shared.spoof.requests.StreamOrDetailsDataRequest;

@SuppressWarnings("unused")
public final class SaveToWatchLaterPatch {

    /**
     * If the player is not active, the layout may break.
     * Use it only when it is guaranteed to be used in situations where the player is active.
     */
    public static void saveVideo() {
        try {
            String videoId = VideoInformation.getVideoId();
            StreamOrDetailsDataRequest request = SpoofVideoStreamsPatch.fetchDetails(
                    PlayerRoutes.SEND_SAVE_VIDEO_TO_PLAYLIST,
                    videoId
            );

            if (request == null) {
                Logger.printDebug(() -> "Could not save video, fetch details are null: " + videoId);
                return;
            }

            Utils.runOnBackgroundThread(() -> {
                String saveToWatchLaterResponse = (String) request.getStreamOrDetails();
                if (saveToWatchLaterResponse != null && !saveToWatchLaterResponse.isEmpty()) {
                    Logger.printDebug(() -> "watch later response: " + saveToWatchLaterResponse);

                    if (saveToWatchLaterResponse.contains("STATUS_SUCCEEDED")) {
                        Utils.showToastShort(str(
                                saveToWatchLaterResponse.contains("\"playlistEditResults\"")
                                        ? "morphe_save_to_watch_later_success_toast"
                                        : "morphe_save_to_watch_later_already_exists_toast"));
                    }
                }
            });
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch video details", ex);
            Utils.showToastShort(str("morphe_save_to_watch_later_error_toast"));
        }
    }
}
