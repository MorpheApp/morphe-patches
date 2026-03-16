/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class ReloadVideoPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface PlayerInterface {
        // Method is added during patching.
        void patch_dismissPlayer();
    }

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static WeakReference<PlayerInterface> playerInterfaceRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void setMainActivity(Activity mainActivity) {
        activityRef = new WeakReference<>(mainActivity);
    }

    /**
     * Injection point.
     */
    public static void initialize(@NonNull PlayerInterface playerInterface) {
        playerInterfaceRef = new WeakReference<>(Objects.requireNonNull(playerInterface));
    }

    /**
     * If the player is not active, the layout may break.
     * Use it only when it is guaranteed to be used in situations where the player is active.
     */
    public static void reloadVideo() {
        try {
            PlayerInterface playerInterface = playerInterfaceRef.get();
            if (playerInterface == null) {
                Utils.showToastShort(str("morphe_dismiss_player_not_available_toast"));
            } else {
                String videoId = VideoInformation.getVideoId();
                String playlistId = VideoInformation.getPlaylistId();

                // Dismiss the player.
                playerInterface.patch_dismissPlayer();

                // Reopens the video after 500ms.
                // If the video was opened from a playlist, the playlist ID is also used.
                Utils.runOnMainThreadDelayed(() -> openVideo(playlistId, videoId), 500);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to reload video", ex);
        }
    }

    @SuppressWarnings("ExtractMethodRecommender")
    private static void openVideo(String playlistId, String videoId) {
        try {
            String parameterSeparator = "?";
            StringBuilder builder = new StringBuilder("https://youtu.be/");
            builder.append(videoId);
            if (!playlistId.isEmpty()) {
                builder.append(parameterSeparator);
                parameterSeparator = "&";
                builder.append("list=");
                builder.append(playlistId);
            }
            long currentVideoTimeInSeconds = VideoInformation.getVideoTime() / 1000;
            if (currentVideoTimeInSeconds > 0) {
                builder.append(parameterSeparator);
                builder.append("t=");
                builder.append(currentVideoTimeInSeconds);
            }
            Uri content = Uri.parse(builder.toString());

            // If possible, use the main activity as the context.
            // Otherwise, fall back on using the application context.
            Context context = activityRef.get();
            boolean isActivityContext = true;
            if (context == null) {
                // Utils context is the application context, and not an activity context.
                //
                // Edit: This check may no longer be needed since YT can now
                // only be launched from the main Activity (embedded usage in other apps no longer works).
                context = Utils.getContext();
                isActivityContext = false;
            }

            Intent intent = new Intent("android.intent.action.VIEW", content);
            intent.setPackage(context.getPackageName());
            if (!isActivityContext) {
                Logger.printDebug(() -> "Using new task intent");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            Logger.printException(() -> "Failed to open video", e);
        }
    }

}
