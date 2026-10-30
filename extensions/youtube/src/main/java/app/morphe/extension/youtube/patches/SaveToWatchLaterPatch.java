/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1274
 * https://github.com/MorpheApp/morphe-patches/pull/4016
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.text.TextUtils;

import com.google.protobuf.MessageLite;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditAction;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditActionType;
import app.morphe.extension.youtube.patches.utils.requests.SaveToWatchLaterRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class SaveToWatchLaterPatch {

    public static final String WATCH_LATER_PLAYLIST_ID = "WL";

    /**
     * If the player is not active, the layout may break.
     * Use it only when it is guaranteed to be used in situations where the player is active.
     */
    private static volatile SaveToWatchLaterRequest saveToWatchLaterRequest;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * @return The playlist that Watch later saves are added to.
     */
    public static String getWatchLaterPlaylistId() {
        String redirectId = Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_ID.get();
        return redirectId.isEmpty() ? WATCH_LATER_PLAYLIST_ID : redirectId;
    }

    /**
     * Injection point.
     *
     * @param actions Edit actions of the request.
     * @return The playlist the edit request is sent to.
     */
    public static String getEditPlaylistId(String playlistId, List<MessageLite> actions) {
        try {
            if (!WATCH_LATER_PLAYLIST_ID.equals(playlistId)) {
                return playlistId;
            }
            String redirectId = Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_ID.get();
            if (redirectId.isEmpty()) {
                return playlistId;
            }

            // Only redirect video additions. Removals reference the
            // video entry of the Watch later playlist and fail with any other playlist.
            for (MessageLite action : actions) {
                if (PlaylistEditAction.parseFrom(action.toByteArray()).getAction()
                        != PlaylistEditActionType.ACTION_ADD_VIDEO) {
                    return playlistId;
                }
            }

            return redirectId;
        } catch (Exception ex) {
            Logger.printException(() -> "getEditPlaylistId failure", ex);
        }

        return playlistId;
    }

    public static void saveVideo(String videoId) {
        // Prevent a new request until the previous (if exists) is not done.
        if (saveToWatchLaterRequest != null && !saveToWatchLaterRequest.fetchIsDone()) {
            return;
        }
        if (AuthUtils.isNotLoggedIn()) {
            Utils.showSnackbarShort(str("morphe_queue_manager_check_failed_auth"));
            return;
        }
        if (TextUtils.isEmpty(videoId)) {
            Utils.showSnackbarShort(str("morphe_queue_manager_check_failed_video_id"));
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
                    Utils.showSnackbarShort(str(result
                            ? "morphe_save_to_watch_later_success_toast"
                            : "morphe_save_to_watch_later_already_exists_toast"));
                } else {
                    Logger.printDebug(() -> "Could not save video, save to Watch later results are null: " + videoId);
                }
            });
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch save to Watch later request", ex);
            Utils.showSnackbarShort(str("morphe_save_to_watch_later_error_toast"));
        }
    }
}
