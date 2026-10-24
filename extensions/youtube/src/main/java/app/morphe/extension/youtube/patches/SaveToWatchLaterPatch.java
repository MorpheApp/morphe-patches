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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.youtube.patches.utils.requests.SaveToWatchLaterRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class SaveToWatchLaterPatch {

    /**
     * Matches the playlist id of a full playlist URL,
     * such as https://youtube.com/playlist?list=PLxxxxxxxx or a share link.
     */
    private static final Pattern PLAYLIST_URL_ID_PATTERN = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");

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
     * Called from code injected into the stock edit playlist request builder, and by
     * {@link SaveToWatchLaterRequest} since the extension makes its own edit playlist requests.
     */
    public static String getRedirectPlaylistId(String playlistId) {
        try {
            if (!"WL".equals(playlistId)) {
                // Only redirect saves into the stock Watch later playlist.
                return playlistId;
            }

            final String redirectId = extractPlaylistId(
                    Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_ID.get());
            if (redirectId != null) {
                return redirectId;
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not get redirect playlist id", ex);
        }

        return playlistId;
    }

    /**
     * @return The playlist id of the input, which may be a raw playlist id
     * or a full playlist URL such as youtube.com/playlist?list=PLxxxxxxxx.
     * Null if the input is empty or has no usable playlist id.
     */
    @Nullable
    private static String extractPlaylistId(@Nullable String input) {
        if (input == null) {
            return null;
        }

        String value = input.trim();
        if (value.isEmpty() || "WL".equals(value)) {
            return null;
        }

        Matcher matcher = PLAYLIST_URL_ID_PATTERN.matcher(value);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // Assume the input is already a playlist id, such as a playlist id starting with PL.
        return value;
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
