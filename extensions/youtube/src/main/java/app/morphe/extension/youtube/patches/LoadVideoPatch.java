/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class LoadVideoPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface PlayerInterface {
        // Method is added during patching.
        void patch_dismissPlayer();
        Parcelable patch_getIntentParcelable(Intent intent);
    }

    private static WeakReference<Activity> mainActivityRef = new WeakReference<>(null);
    private static WeakReference<PlayerInterface> playerInterfaceRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void setMainActivity(Activity mainActivity) {
        mainActivityRef = new WeakReference<>(mainActivity);
    }

    /**
     * Injection point.
     */
    public static void initialize(@NonNull PlayerInterface playerInterfaceInit) {
        playerInterfaceRef = new WeakReference<>(Objects.requireNonNull(playerInterfaceInit));
    }

    /**
     * If the player is not active, the layout may break.
     * Use it only when it is guaranteed to be used in situations where the player is active.
     */
    @SuppressWarnings("ExtractMethodRecommender")
    public static void reloadVideo() {
        try {
            PlayerInterface playerInterface = playerInterfaceRef.get();
            if (playerInterface == null) {
                Utils.showToastShort(str("morphe_dismiss_player_not_available_toast"));
                return;
            }

            // Must use player response video id, otherwise reloading a video that isn't opening
            // can use the wrong video id. Response video id may be a feed Short that was scrolled
            // past but not opened.
            String videoId = VideoInformation.lastPlayerResponseIsShort()
                    ? VideoInformation.getVideoId() // Player response may be a Short in the feed.
                    : VideoInformation.getPlayerResponseVideoId();
            String playlistId = VideoInformation.getPlaylistId();
            final long videoTime = VideoInformation.getVideoTime();
            String parameterSeparator = "?";
            StringBuilder builder = new StringBuilder(videoId);
            if (!playlistId.isEmpty()) {
                builder.append(parameterSeparator);
                builder.append("list=");
                builder.append(playlistId);
                parameterSeparator = "&";
            }
            if (videoTime > 0) {
                builder.append(parameterSeparator);
                builder.append("t=");
                builder.append(videoTime / 1000);
                builder.append('s');
            }
            String builderString = builder.toString();
            Logger.printDebug(() -> "Opening: https://www.youtube.com/watch?v=" + builderString);

            // Dismiss the player.
            playerInterface.patch_dismissPlayer();

            // Reopens the video after 500ms.
            Utils.runOnMainThreadDelayed(() -> openVideo(builderString), 500);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to reload video", ex);
        }
    }

    // This method opens a video based on hardcoded parameters found in an obfuscated class.
    public static void openVideo(String videoIDWithParams) {
        PlayerInterface playerInterface = playerInterfaceRef.get();
        if (playerInterface == null) {
            Utils.showToastShort(str("morphe_dismiss_player_not_available_toast"));
            return;
        }

        Context context = mainActivityRef.get();
        // No videoID is needed to put inside the Intent initialization.
        Intent reloadVideoIntent = new Intent();
        reloadVideoIntent.setComponent(new ComponentName(
                context,
                "com.google.android.apps.youtube.app.watchwhile.InternalMainActivity"
        ));
        // NEW_TASK intent is not needed by this code.
        reloadVideoIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Always put 'inventory_identifier' putExtra before 'watch'
        // putExtra, to ensure the patch works correctly.
        reloadVideoIntent.putExtra(
                "android.intent.extra.inventory_identifier", new String[]{"vnd.youtube://" + videoIDWithParams}
        );
        // Get the needed Parcelable object from a static method, which will
        // read inventory_identifier inside the currently built Intent.
        reloadVideoIntent.putExtra("watch", playerInterface.patch_getIntentParcelable(reloadVideoIntent));

        context.startActivity(reloadVideoIntent);
    }
}
