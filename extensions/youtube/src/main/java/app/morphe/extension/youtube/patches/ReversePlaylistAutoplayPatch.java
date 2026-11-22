/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5171
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
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

    private static final String NAVIGATION_INTENT_AUTOPLAY = "AUTOPLAY";
    private static final String NAVIGATION_INTENT_PREVIOUS = "PREVIOUS";

    /**
     * Injection point. Called when the playlist queue looks up the video of a navigation,
     * such as to show the upcoming video or to autoplay it when the current video ends.
     *
     * @return PREVIOUS in place of AUTOPLAY in a reversed playlist.
     */
    public static Enum<?> getNavigationIntent(Enum<?> navigationIntent) {
        try {
            if (!NAVIGATION_INTENT_AUTOPLAY.equals(navigationIntent.name())) {
                return navigationIntent;
            }
            String playlistId = VideoInformation.getPlaylistId();
            // A Mix only continues forward.
            final boolean reverse = Settings.REVERSE_PLAYLIST_AUTOPLAY.get()
                    ? !AutoplayRule.isMix(playlistId)
                    : PlayAllButton.isReversePlaylist(playlistId);
            // YouTube also uses AUTOPLAY to repeat the current video, and the local queue plays its next video.
            if (!reverse || DisablePlaylistAutoplayPatch.isRepeatingVideo() || LocalQueuePatch.hasQueuedVideos()) {
                return navigationIntent;
            }
            return previousNavigationIntent(navigationIntent);
        } catch (Exception ex) {
            Logger.printException(() -> "getNavigationIntent failure", ex);
            return navigationIntent;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> previousNavigationIntent(Enum<?> navigationIntent) {
        return Enum.valueOf((Class) navigationIntent.getDeclaringClass(), NAVIGATION_INTENT_PREVIOUS);
    }
}
