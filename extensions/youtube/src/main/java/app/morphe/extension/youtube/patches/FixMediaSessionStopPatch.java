/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;

/**
 * All players share one media session. The player that starts the session is its owner,
 * and a session stop from another player is ignored (what YouTube 21.42+ does with an A/B flag).
 */
@SuppressWarnings("unused")
public final class FixMediaSessionStopPatch {

    private static WeakReference<Object> ownerRef = new WeakReference<>(null);

    /**
     * Player that is calling the session stop, or null if the stop is not made by a player.
     */
    @Nullable
    private static Object stoppingPlayer;

    /**
     * Injection point.
     */
    public static void setMediaSessionOwner(Object player) {
        if (ownerRef.get() != player) {
            Logger.printDebug(() -> "Media session owner changed to: " + player);
            ownerRef = new WeakReference<>(player);
        }
    }

    /**
     * Injection point.
     */
    public static void setMediaSessionStoppingPlayer(Object player) {
        stoppingPlayer = player;
    }

    /**
     * Injection point.
     *
     * @return If the media session can be stopped.
     */
    public static boolean isMediaSessionStopAllowed(boolean playbackDestroyed) {
        Object player = stoppingPlayer;
        stoppingPlayer = null;
        if (player == null) {
            return true;
        }

        Object owner = ownerRef.get();
        if (owner != null && owner != player) {
            Logger.printDebug(() -> "Ignoring media session stop of player: " + player
                    + " that is not the owner: " + owner);
            return false;
        }

        if (playbackDestroyed) {
            ownerRef = new WeakReference<>(null);
        }
        return true;
    }
}
