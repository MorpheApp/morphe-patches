/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditAction;
import app.morphe.extension.youtube.patches.utils.requests.SaveToWatchLaterRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class SaveToWatchLaterPatch {

    /**
     * If the player is not active, the layout may break.
     * Use it only when it is guaranteed to be used in situations where the player is active.
     */
    private static volatile SaveToWatchLaterRequest saveToWatchLaterRequest;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Playlist id that the 'Save to Watch later' actions save videos into.
     *
     * If the request targets the internal Watch later playlist, returns the id of the
     * redirect playlist configured in {@link Settings#SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_ID}.
     * Otherwise returns the id untouched.
     *
     * Used by {@link SaveToWatchLaterRequest}, which only adds videos.
     */
    public static String getRedirectPlaylistId(String playlistId) {
        return getRedirectPlaylistId(playlistId, null);
    }

    public static String getRedirectPlaylistId(String playlistId, @Nullable List<MessageLite> actions) {
        try {
            if (!"WL".equals(playlistId)) {
                // Only redirect saves into the stock Watch later playlist.
                return playlistId;
            }

            final String redirectId = Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_ID.get();
            if (!redirectId.isEmpty()) {
                if (actions != null) {
                    if (actions.isEmpty()) return playlistId;
                    for (MessageLite action : actions) {
                        // ACTION_ADD_VIDEO is 1; other edits must retain their destination.
                        if (PlaylistEditAction.parseFrom(action.toByteArray()).getAction() != 1) return playlistId;
                    }
                }
                return redirectId;
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not get redirect playlist id", ex);
        }

        return playlistId;
    }

    public static void saveVideo(String videoId) {
        // Prevent a new request until the previous (if exists) is not done.
        if (saveToWatchLaterRequest != null && !saveToWatchLaterRequest.fetchIsDone()) {
            return;
        }
        if (AuthUtils.isNotLoggedIn()) {
            Utils.showToastShort(str("morphe_queue_manager_check_failed_auth"));
            return;
        }
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
                    Logger.printDebug(() -> "Could not save video, save to Watch later results are null: " + videoId);
                }
            });
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch save to Watch later request", ex);
            Utils.showToastShort(str("morphe_save_to_watch_later_error_toast"));
        }
    }
}
