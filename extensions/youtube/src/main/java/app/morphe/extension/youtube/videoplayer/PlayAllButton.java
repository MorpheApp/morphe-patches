package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.DisablePlaylistAutoplayPatch;
import app.morphe.extension.youtube.patches.LocalQueuePatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class PlayAllButton {

    public enum PlaylistIDPrefix {
        ALL_CONTENTS_WITH_TIME_ASCENDING("UL", false),
        ALL_CONTENTS_WITH_TIME_DESCENDING("UU", true),
        ALL_CONTENTS_WITH_POPULAR_DESCENDING("PU", true),
        VIDEOS_ONLY_WITH_TIME_DESCENDING("UULF", true),
        VIDEOS_ONLY_WITH_POPULAR_DESCENDING("UULP", true),
        SHORTS_ONLY_WITH_TIME_DESCENDING("UUSH", true),
        SHORTS_ONLY_WITH_POPULAR_DESCENDING("UUPS", true),
        LIVE_STREAMS_ONLY_WITH_TIME_DESCENDING("UULV", true),
        LIVE_STREAMS_ONLY_WITH_POPULAR_DESCENDING("UUPV", true),
        ALL_MEMBERSHIPS_CONTENTS("UUMO", true),
        MEMBERSHIPS_VIDEOS_ONLY("UUMF", true),
        MEMBERSHIPS_SHORTS_ONLY("UUMS", true),
        MEMBERSHIPS_LIVE_STREAMS_ONLY("UUMV", true);

        @NonNull
        public final String prefixId;

        public final boolean useChannelId;

        PlaylistIDPrefix(@NonNull String prefixId, boolean useChannelId) {
            this.prefixId = prefixId;
            this.useChannelId = useChannelId;
        }
    }

    private static final String NAVIGATION_INTENT_AUTOPLAY = "AUTOPLAY";
    private static final String NAVIGATION_INTENT_PREVIOUS = "PREVIOUS";

    /**
     * Playlist generated with Reverse order on, or null.
     */
    @Nullable
    private static volatile String reversePlaylistId;

    static {
        if (Settings.PLAY_ALL_BUTTON.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    /**
     * Injection point. Called when the playlist queue looks up the video of a navigation,
     * such as to show the upcoming video or to autoplay it when the current video ends.
     *
     * @return PREVIOUS in place of AUTOPLAY in a playlist generated with Reverse order on,
     *         so the playlist plays from the last video to the first.
     */
    public static Enum<?> getNavigationIntent(Enum<?> navigationIntent) {
        try {
            String playlistId = reversePlaylistId;
            if (playlistId == null || !Settings.PLAY_ALL_BUTTON_REVERSE.get()
                    || !NAVIGATION_INTENT_AUTOPLAY.equals(navigationIntent.name())) {
                return navigationIntent;
            }
            // YouTube also uses AUTOPLAY to repeat the current video, and the local queue plays its next video.
            if (DisablePlaylistAutoplayPatch.isRepeatingVideo()
                    || LocalQueuePatch.hasQueuedVideos()
                    || !playlistId.equals(VideoInformation.getPlaylistId())) {
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

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_play_all_button",
                    null,
                    "morphe_play_all_button",
                    Settings.PLAY_ALL_BUTTON,
                    view -> openVideo(view, Settings.PLAY_ALL_BUTTON_TYPE.get()),
                    view -> {
                        openVideo(view, null);
                        return true;
                    }
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /**
     * Generates the YouTube URL and launches the Intent natively.
     */
    private static void openVideo(View view, @Nullable PlaylistIDPrefix playlistIdPrefix) {
        try {
            String videoId = VideoInformation.getVideoId();
            long timeInSeconds = VideoInformation.getVideoTime() / 1000;
            String channelId = VideoInformation.getChannelId();

            if (videoId.isEmpty()) {
                Logger.printDebug(() -> "Play all button: Video ID is null or empty. Cannot generate URL.");
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("https://youtu.be/").append(videoId);

            if (timeInSeconds > 0) {
                sb.append("?t=").append(timeInSeconds);
            }

            String playlistId = null;
            if (playlistIdPrefix != null) {
                if (playlistIdPrefix.useChannelId) {
                    if (channelId.startsWith("UC")) {
                        String baseId = channelId.substring(2);
                        playlistId = playlistIdPrefix.prefixId + baseId;
                    } else {
                        Logger.printDebug(() -> "Play all button: Invalid or missing Channel ID: " + channelId);
                        Utils.showSnackbarShort(str("morphe_play_all_button_not_available_toast"));
                        return;
                    }
                } else {
                    playlistId = playlistIdPrefix.prefixId + videoId;
                }

                sb.append(timeInSeconds > 0 ? "&" : "?").append("list=").append(playlistId);
            }

            Context activityContext = view.getContext();
            if (activityContext == null) {
                Logger.printDebug(() -> "Play all button: Activity Context is null. Cannot launch Intent.");
                return;
            }

            reversePlaylistId = playlistId != null && Settings.PLAY_ALL_BUTTON_REVERSE.get()
                    ? playlistId
                    : null;

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(sb.toString()));
            intent.setPackage(activityContext.getPackageName());
            activityContext.startActivity(intent);

        } catch (Exception e) {
            Logger.printException(() -> "Failed to launch play all intent", e);
        }
    }
}
