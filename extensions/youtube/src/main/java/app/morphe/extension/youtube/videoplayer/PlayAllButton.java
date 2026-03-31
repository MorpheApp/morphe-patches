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

    /**
     * Injection point.
     */
    public static void initializeButton(View sourceButton) {
        try {
            if (!isButtonEnabled()) return;

            PlayerOverlayButton.addButton(
                    sourceButton,
                    "morphe_play_all_button",
                    PlayAllButton::onClick,
                    view -> {
                        onLongClick(view);
                        return true;
                    }
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    private static boolean isButtonEnabled() {
        return Settings.PLAY_ALL_BUTTON.get();
    }

    private static void onClick(View view) {
        openVideo(Settings.PLAY_ALL_BUTTON_TYPE.get());
    }

    private static void onLongClick(View view) {
        openVideo(null);
    }

    /**
     * Generates the YouTube URL and launches the Intent natively.
     */
    private static void openVideo(@Nullable PlaylistIDPrefix playlistIdPrefix) {
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

            Context context = Utils.getContext();
            if (context == null) {
                Logger.printDebug(() -> "Play all button: Context is null. Cannot launch Intent.");
                return;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(sb.toString()));
            intent.setPackage(context.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);

        } catch (Exception e) {
            Logger.printException(() -> "Failed to launch play all intent", e);
        }
    }
}