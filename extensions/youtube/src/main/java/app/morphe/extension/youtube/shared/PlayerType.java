/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.shared;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;

/**
 * Regular player type.
 */
public enum PlayerType {
    /**
     * Either no video, or a Short is playing.
     */
    NONE,

    /**
     * A Short is playing. Occurs if a regular video is first opened
     * and then a Short is opened (without first closing the regular video).
     */
    HIDDEN,

    /**
     * A regular video is minimized.
     */
    WATCH_WHILE_MINIMIZED,
    WATCH_WHILE_MAXIMIZED,
    WATCH_WHILE_FULLSCREEN,
    WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN,
    WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED,

    /**
     * Player is either sliding to {@link #HIDDEN} state because a Short was opened while a regular video is on screen.
     * OR
     * The user has swiped a minimized player away to be closed (and no Short is being opened).
     */
    WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED,
    WATCH_WHILE_SLIDING_FULLSCREEN_DISMISSED,

    /**
     * Home feed video playback.
     */
    INLINE_MINIMAL,
    VIRTUAL_REALITY_FULLSCREEN,
    WATCH_WHILE_PICTURE_IN_PICTURE;

    private static final Map<String, PlayerType> nameToPlayerType = new HashMap<>();

    static {
        for (PlayerType type : values()) {
            nameToPlayerType.put(type.name(), type);
        }
    }

    /**
     * player type change listener
     */
    private static final Event<PlayerType> onChange = new Event<>();

    private static volatile PlayerType currentPlayerType = NONE; // Read/write from different threads.

    public static Event<PlayerType> getOnChange() {
        return onChange;
    }

    public static void setFromString(String enumName) {
        PlayerType newType = nameToPlayerType.get(enumName);
        if (newType == null) {
            Logger.printException(() -> "Unknown PlayerType encountered: " + enumName);
        } else {
            setCurrent(newType);
        }
    }

    /**
     * The current player type.
     */
    public static PlayerType getCurrent() {
        return currentPlayerType;
    }

    private static void setCurrent(PlayerType type) {
        if (currentPlayerType != type) {
            Logger.printDebug(() -> "Changed to: " + type);

            currentPlayerType = type;
            onChange.invoke(type);
        }
    }

    /**
     * Check if the current player type is {@link #NONE} or {@link #HIDDEN}.
     * Useful to check if a short is currently playing.
     * <p>
     * Does not include the first moment after a short is opened when a regular video is minimized on screen,
     * or while watching a short with a regular video present on a spoofed 16.x version of YouTube.
     * To include those situations instead use {@link #isNoneHiddenOrMinimized()}.
     */
    public boolean isNoneOrHidden() {
        return this == NONE || this == HIDDEN;
    }

    /**
     * Check if the current player type is
     * {@link #NONE}, {@link #HIDDEN}, {@link #WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED}.
     * <p>
     * Useful to check if a Short is being played or opened.
     * <p>
     * Usually covers all use cases with no false positives, except if called from some hooks
     * when spoofing to an old version this will return false even
     * though a Short is being opened or is on screen (see {@link #isNoneHiddenOrMinimized()}).
     * <p>
     * Instead of this method, consider using {@link ShortsPlayerState}
     * which may work better for some situations.
     *
     * @return If nothing, a Short, or a regular video is sliding off-screen to a dismissed or hidden state.
     * @see ShortsPlayerState
     */
    public boolean isNoneHiddenOrSlidingMinimized() {
        return isNoneOrHidden() || this == WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED;
    }

    /**
     * Check if the current player type is
     * {@link #NONE}, {@link #HIDDEN}, {@link #WATCH_WHILE_MINIMIZED}, {@link #WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED}.
     * <p>
     * Useful to check if a Short is being played,
     * although it will return false positive if a regular video is
     * opened and minimized (and a Short is not playing or being opened).
     * <p>
     * Typically used to detect if a Short is playing when the player cannot be in a minimized state,
     * such as the user interacting with a button or element of the player.
     * <p>
     * Instead of this method, consider using {@link ShortsPlayerState}
     * which may work better for some situations.
     *
     * @return If nothing, a Short, a regular video is sliding off-screen to a dismissed or hidden state,
     *         a regular video is minimized (and a new video is not being opened).
     * @see ShortsPlayerState
     */
    public boolean isNoneHiddenOrMinimized() {
        return isNoneHiddenOrSlidingMinimized() || this == WATCH_WHILE_MINIMIZED;
    }

    public boolean isMaximizedOrFullscreen() {
        return this == WATCH_WHILE_MAXIMIZED || this == WATCH_WHILE_FULLSCREEN
                || this == WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN;
    }
}
