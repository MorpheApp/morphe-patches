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
        LIVESTREAMS_ONLY_WITH_TIME_DESCENDING("UULV", true),
        LIVESTREAMS_ONLY_WITH_POPULAR_DESCENDING("UUPV", true),
        ALL_MEMBERSHIPS_CONTENTS("UUMO", true),
        MEMBERSHIPS_VIDEOS_ONLY("UUMF", true),
        MEMBERSHIPS_SHORTS_ONLY("UUMS", true),
        MEMBERSHIPS_LIVESTREAMS_ONLY("UUMV", true);

        @NonNull
        public final String prefixId;

        public final boolean useChannelId;

        PlaylistIDPrefix(@NonNull String prefixId, boolean useChannelId) {
            this.prefixId = prefixId;
            this.useChannelId = useChannelId;
        }
    }

    static {
        if (Settings.PLAY_ALL_BUTTON.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    @Nullable
    private static LegacyPlayerControlButton legacy;

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_play_all_button",
                    null,
                    "morphe_play_all_button",
                    Settings.PLAY_ALL_BUTTON::get,
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
     * injection point.
     */
    public static void setVisibilityNegatedImmediate() {
        if (legacy != null) legacy.setVisibilityNegatedImmediate();
    }

    /**
     * injection point.
     */
    public static void setVisibilityImmediate(boolean visible) {
        if (legacy != null) legacy.setVisibilityImmediate(visible);
    }

    /**
     * injection point.
     */
    public static void setVisibility(boolean visible, boolean animated) {
        if (legacy != null) legacy.setVisibility(visible, animated);
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

            if (playlistIdPrefix != null) {
                sb.append(timeInSeconds > 0 ? "&" : "?").append("list=").append(playlistIdPrefix.prefixId);

                if (playlistIdPrefix.useChannelId) {
                    if (channelId.startsWith("UC")) {
                        String baseId = channelId.substring(2);
                        sb.append(baseId);
                    } else {
                        Logger.printDebug(() -> "Play all button: Invalid or missing Channel ID: " + channelId);
                        Utils.showToastShort(str("morphe_play_all_button_not_available_toast"));
                        return;
                    }
                } else {
                    sb.append(videoId);
                }
            }

            Context activityContext = view.getContext();
            if (activityContext == null) {
                Logger.printDebug(() -> "Play all button: Activity Context is null. Cannot launch Intent.");
                return;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(sb.toString()));
            intent.setPackage(activityContext.getPackageName());
            activityContext.startActivity(intent);

        } catch (Exception e) {
            Logger.printException(() -> "Failed to launch play all intent", e);
        }
    }
}