/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS;

import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class CopyVideoURLButton {
    private static final boolean COPY_VIDEO_URL_BUTTON_TIMESTAMP = Settings.COPY_VIDEO_URL_BUTTON_TIMESTAMP.get();

    @Nullable
    private static LegacyPlayerControlButton legacy;

    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
            if (RESTORE_OLD_PLAYER_BUTTONS || !Settings.COPY_VIDEO_URL_BUTTON.get()) {
                return;
            }

            PlayerOverlayButton.addButton(controlsView,
                    COPY_VIDEO_URL_BUTTON_TIMESTAMP ? "morphe_yt_copy_timestamp_bold" : "morphe_yt_copy_bold",
                    view -> copyURL(COPY_VIDEO_URL_BUTTON_TIMESTAMP),
                    view -> {
                        copyURL(!COPY_VIDEO_URL_BUTTON_TIMESTAMP);
                        return true;
                    }
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }


    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            if (!RESTORE_OLD_PLAYER_BUTTONS) {
                return;
            }

            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_copy_video_url_button",
                    null,
                    COPY_VIDEO_URL_BUTTON_TIMESTAMP
                            ? "morphe_yt_copy_timestamp"
                            : "morphe_yt_copy",
                    Settings.COPY_VIDEO_URL_BUTTON::get,
                    view -> copyURL(COPY_VIDEO_URL_BUTTON_TIMESTAMP),
                    view -> {
                        copyURL(!COPY_VIDEO_URL_BUTTON_TIMESTAMP);
                        return true;
                    }
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /**`
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

    public static void copyURL(boolean withTimestamp) {
        try {
            StringBuilder builder = new StringBuilder("https://youtu.be/");
            builder.append(VideoInformation.getVideoId());
            final long currentVideoTimeInSeconds = appendCurrentVideoTimeInSeconds(withTimestamp, builder);

            Utils.setClipboard(builder.toString());
            // Do not show a toast if using Android 13+ as it shows its own toast.
            // But if the user copied with a timestamp then show a toast.
            // Unfortunately this will show 2 toasts on Android 13+, but no way around this.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
                    || (withTimestamp && currentVideoTimeInSeconds > 0)) {
                Utils.showToastShort(withTimestamp && currentVideoTimeInSeconds > 0
                        ? str("morphe_share_copy_url_button_timestamp_success")
                        : str("morphe_share_copy_url_button_success"));
            }
        } catch (Exception e) {
            Logger.printException(() -> "Failed to generate video URL", e);
        }
    }

    private static long appendCurrentVideoTimeInSeconds(boolean withTimestamp, StringBuilder builder) {
        final long currentVideoTimeInSeconds = VideoInformation.getVideoTime() / 1000;
        if (withTimestamp && currentVideoTimeInSeconds > 0) {
            final long hour = currentVideoTimeInSeconds / (60 * 60);
            final long minute = (currentVideoTimeInSeconds / 60) % 60;
            final long second = currentVideoTimeInSeconds % 60;
            builder.append("?t=");
            if (hour > 0) {
                builder.append(hour).append("h");
            }
            if (minute > 0) {
                builder.append(minute).append("m");
            }
            if (second > 0) {
                builder.append(second).append("s");
            }
        }
        return currentVideoTimeInSeconds;
    }
}