/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.PlayerRoutes;

@SuppressWarnings("unused")
public final class SaveToWatchLaterPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface PlayerInterface {
        // Method is added during patching.
        void patch_dismissPlayer();
    }

    @SuppressWarnings("FieldCanBeLocal")
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    @SuppressWarnings("FieldCanBeLocal")
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
    public static void saveVideo() {
        try {
            SpoofVideoStreamsPatch.fetchDetails(
                PlayerRoutes.SEND_SAVE_VIDEO_TO_PLAYLIST,

                VideoInformation.getVideoId()
            );

            String saveToWatchLaterResponse = SpoofVideoStreamsPatch.getDetailsData(VideoInformation.getVideoId());

            if (saveToWatchLaterResponse != null && !saveToWatchLaterResponse.isEmpty()) {
                Logger.printInfo(() -> saveToWatchLaterResponse);

                if (saveToWatchLaterResponse.contains("STATUS_SUCCEEDED")) {
                    if (saveToWatchLaterResponse.contains("\"playlistEditResults\"")) {
                        Utils.showToastShort(str("morphe_save_to_watch_later_success_toast"));
                    } else {
                        Utils.showToastShort(str("morphe_save_to_watch_later_already_exists_toast"));
                    }
                }

                Logger.printInfo(() -> saveToWatchLaterResponse);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch video details", ex);
            Utils.showToastShort(str("morphe_save_to_watch_later_error_toast"));
        }
    }
}
