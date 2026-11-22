/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5171
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.queue.NativePlaylistQueuePatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.PlayAllButton;

/**
 * Autoplays a playlist from the last video to the first, with "Reverse playlist autoplay"
 * or with the Reverse order of the Play all button.
 */
@SuppressWarnings("unused")
public final class ReversePlaylistAutoplayPatch {
    private ReversePlaylistAutoplayPatch() {
    }

    /**
     * Injection point. Called when the playlist queue looks up the video of a navigation,
     * such as to show the upcoming video or to autoplay it when the current video ends.
     *
     * @return If the queue picks the previous video in place of the next video to autoplay.
     */
    public static boolean shouldPlayPrevious(Enum<?> navigationIntent) {
        try {
            if (!"AUTOPLAY".equals(navigationIntent.name())) {
                return false;
            }
            String playlistId = VideoInformation.getPlaylistId();
            if (playlistId.isEmpty()) {
                return false;
            }
            // A Mix only continues forward.
            final boolean reverse = Settings.REVERSE_PLAYLIST_AUTOPLAY.get()
                    ? !AutoplayRule.isMix(playlistId)
                    : PlayAllButton.isReversePlaylist(playlistId);
            // YouTube also uses AUTOPLAY to repeat the current video, and the queues play their own next video.
            return reverse
                    && !DisablePlaylistAutoplayPatch.isRepeatingVideo()
                    && !LocalQueuePatch.hasQueuedVideos()
                    && !NativePlaylistQueuePatch.isQueuePlaying();
        } catch (Exception ex) {
            Logger.printException(() -> "shouldPlayPrevious failure", ex);
            return false;
        }
    }
}
