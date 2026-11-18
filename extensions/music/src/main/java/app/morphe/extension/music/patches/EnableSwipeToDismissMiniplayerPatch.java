/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class EnableSwipeToDismissMiniplayerPatch {

    /**
     * Implemented by the watch while layout during patching.
     */
    public interface WatchWhileLayoutAccess {
        /**
         * Requests a new player state, such as showing the miniplayer.
         */
        void patch_requestPlayerState(Enum<?> state);
    }

    /**
     * The last miniplayer request ignored while the miniplayer is dismissed, and the layout it was for.
     * It is requested again when a song is loaded.
     */
    @Nullable
    private static volatile Enum<?> ignoredPlayerStateRequest;

    private static volatile WeakReference<WatchWhileLayoutAccess> ignoredRequestLayoutRef = new WeakReference<>(null);

    /**
     * Injection point
     */
    public static boolean enableSwipeToDismissMiniplayer() {
        return Settings.ENABLE_SWIPE_TO_DISMISS_MINIPLAYER.get();
    }

    /**
     * Injection point
     */
    public static Object enableSwipeToDismissMiniplayer(Object object) {
        return Settings.ENABLE_SWIPE_TO_DISMISS_MINIPLAYER.get() ? null : object;
    }

    /**
     * Injection point.
     * The miniplayer was swiped away and the queue was dismissed.
     */
    public static void onMiniplayerDismissed() {
        Logger.printDebug(() -> "Miniplayer dismissed");
        setMiniplayerDismissed(true);
    }

    /**
     * Set when the miniplayer is swiped away, cleared when the player is shown again.
     * While set, the app is not allowed to bring back the empty miniplayer
     * (for example on a tab change or on the next app start).
     * Saved so it is kept after the app is closed.
     */
    private static boolean isMiniplayerDismissed() {
        return Settings.ENABLE_SWIPE_TO_DISMISS_MINIPLAYER.get() && Settings.MINIPLAYER_DISMISSED.get();
    }

    private static void setMiniplayerDismissed(boolean dismissed) {
        if (!dismissed) ignoredPlayerStateRequest = null;

        if (Settings.MINIPLAYER_DISMISSED.get() != dismissed) {
            Settings.MINIPLAYER_DISMISSED.save(dismissed);
        }
    }

    /**
     * Injection point.
     * Called each time the watch while layout changes the player state.
     */
    public static void onPlayerStateChanged(@NonNull Enum<?> state) {
        if (!"DISMISSED".equals(state.name()) && isMiniplayerDismissed()) {
            // The player was opened again, such as by playing a song from a list.
            Logger.printDebug(() -> "Player shown again: " + state);
            setMiniplayerDismissed(false);
        }
    }

    /**
     * Injection point.
     * Called before the watch while layout moves to a new player state.
     *
     * @return If the state request must be ignored.
     */
    public static boolean ignorePlayerStateRequest(@NonNull WatchWhileLayoutAccess watchWhileLayout,
                                                   @NonNull Enum<?> requestedState) {
        if (!"MINIMIZED".equals(requestedState.name()) || !isMiniplayerDismissed()) {
            return false;
        }

        Logger.printDebug(() -> "Ignoring miniplayer request while dismissed");
        ignoredPlayerStateRequest = requestedState;
        ignoredRequestLayoutRef = new WeakReference<>(watchWhileLayout);
        return true;
    }

    /**
     * Injection point.
     * A song or video was loaded. If a miniplayer request was ignored, request it again now.
     */
    public static void newVideoLoaded(@NonNull String videoId) {
        if (!isMiniplayerDismissed()) return;
        final Enum<?> request = ignoredPlayerStateRequest;
        setMiniplayerDismissed(false);
        if (request == null) return;

        Utils.runOnMainThread(() -> {
            WatchWhileLayoutAccess layout = ignoredRequestLayoutRef.get();
            if (layout == null) return;

            Logger.printDebug(() -> "Showing miniplayer for new video: " + videoId);
            layout.patch_requestPlayerState(request);
        });
    }
}
