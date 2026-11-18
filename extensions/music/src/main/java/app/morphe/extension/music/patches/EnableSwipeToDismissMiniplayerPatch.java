/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class EnableSwipeToDismissMiniplayerPatch {

    private static final String PLAYER_STATE_DISMISSED = "DISMISSED";
    private static final String PLAYER_STATE_MINIMIZED = "MINIMIZED";

    /**
     * Current state of the watch while layout. Null until it changes state for the first time.
     */
    @Nullable
    private static volatile Enum<?> currentPlayerState;

    @Nullable
    private static volatile Class<?> playerStateClass;

    private static volatile WeakReference<View> watchWhileLayoutRef = new WeakReference<>(null);

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
        if (Settings.MINIPLAYER_DISMISSED.get() != dismissed) {
            Settings.MINIPLAYER_DISMISSED.save(dismissed);
        }
    }

    /**
     * Injection point.
     * Called each time the watch while layout changes the player state.
     */
    public static void onPlayerStateChanged(@NonNull View watchWhileLayout, @NonNull Enum<?> state) {
        watchWhileLayoutRef = new WeakReference<>(watchWhileLayout);
        currentPlayerState = state;
        playerStateClass = state.getDeclaringClass();

        if (!PLAYER_STATE_DISMISSED.equals(state.name()) && isMiniplayerDismissed()) {
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
    public static boolean ignorePlayerStateRequest(@NonNull View watchWhileLayout, @NonNull Enum<?> requestedState) {
        // The layout may not have changed state yet after an app start.
        watchWhileLayoutRef = new WeakReference<>(watchWhileLayout);
        playerStateClass = requestedState.getDeclaringClass();

        if (PLAYER_STATE_MINIMIZED.equals(requestedState.name()) && isMiniplayerDismissed()) {
            Logger.printDebug(() -> "Ignoring miniplayer request while dismissed");
            return true;
        }

        return false;
    }

    /**
     * Injection point.
     * A song or video was loaded. If the miniplayer request that came with it
     * was ignored, show the miniplayer now.
     */
    public static void newVideoLoaded(@NonNull String videoId) {
        if (!isMiniplayerDismissed()) return;
        setMiniplayerDismissed(false);

        Utils.runOnMainThread(() -> {
            try {
                View layout = watchWhileLayoutRef.get();
                Enum<?> state = currentPlayerState;
                // The layout starts dismissed. A null state means it has not changed state since the app start.
                if (layout == null || (state != null && !PLAYER_STATE_DISMISSED.equals(state.name()))) {
                    return;
                }

                Logger.printDebug(() -> "Showing miniplayer for new video: " + videoId);
                setPlayerState(layout, getMinimizedPlayerState());
            } catch (Exception ex) {
                Logger.printException(() -> "newVideoLoaded failure", ex);
            }
        });
    }

    /**
     * The player state class is saved together with the watch while layout, so it is known here.
     */
    private static Enum<?> getMinimizedPlayerState() {
        Class<?> enumClass = Objects.requireNonNull(playerStateClass);
        for (Object state : Objects.requireNonNull(enumClass.getEnumConstants())) {
            Enum<?> playerState = (Enum<?>) state;
            if (PLAYER_STATE_MINIMIZED.equals(playerState.name())) {
                return playerState;
            }
        }

        throw new IllegalStateException("Minimized player state not found");
    }

    /**
     * Requests a new player state on the watch while layout.
     * The method body is replaced during patching.
     */
    public static void setPlayerState(View watchWhileLayout, Enum<?> state) {
    }
}
