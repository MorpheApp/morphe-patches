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
 * All players share one media session and one background playback. The player that starts the session
 * is its owner, and a session stop or a background playback stop from another player is ignored
 * (YouTube 21.42+ does it for the session stop with an A/B flag).
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
     * Player that is calling the background playback stop, or null if the stop is not made by a player.
     */
    @Nullable
    private static Object backgroundStoppingPlayer;

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
     */
    public static void setBackgroundPlaybackStoppingPlayer(Object player) {
        backgroundStoppingPlayer = player;
    }

    /**
     * @return False if the stop is made by a player that is not the owner of the session.
     */
    private static boolean isOwnerOrUnknown(@Nullable Object player, String stop) {
        if (player == null) {
            return true;
        }

        Object owner = ownerRef.get();
        if (owner != null && owner != player) {
            Logger.printDebug(() -> "Ignoring " + stop + " of player: " + player
                    + " that is not the owner: " + owner);
            return false;
        }
        return true;
    }

    /**
     * Injection point.
     *
     * @return If the media session can be stopped.
     */
    public static boolean isMediaSessionStopAllowed(boolean playbackDestroyed) {
        Object player = stoppingPlayer;
        stoppingPlayer = null;
        if (!isOwnerOrUnknown(player, "media session stop")) {
            return false;
        }

        if (player != null && playbackDestroyed) {
            ownerRef = new WeakReference<>(null);
        }
        return true;
    }

    /**
     * Injection point.
     *
     * @return If the background playback can be stopped (media notification and background player service).
     */
    public static boolean isBackgroundPlaybackStopAllowed() {
        Object player = backgroundStoppingPlayer;
        backgroundStoppingPlayer = null;
        return isOwnerOrUnknown(player, "background playback stop");
    }
}
