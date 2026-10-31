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

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VideoInformation;

/**
 * VideoState playback state.
 */
public enum VideoState {
    NEW,
    PLAYING,
    PAUSED,
    RECOVERABLE_ERROR,
    UNRECOVERABLE_ERROR,
    /**
     * @see VideoInformation#isAtEndOfVideo()
     */
    ENDED;

    private static final Map<String, VideoState> nameToVideoState;

    static {
        VideoState[] values = values();
        nameToVideoState = new HashMap<>(2 * values.length);
        for (VideoState state : values) {
            nameToVideoState.put(state.name(), state);
        }
    }

    private static final Event<VideoState> onChange = new Event<>();

    @Nullable
    private static volatile VideoState currentVideoState; // Read/write from different threads.

    public static Event<VideoState> getOnChange() {
        return onChange;
    }

    public static void setFromString(String enumName) {
        VideoState state = nameToVideoState.get(enumName);
        if (state == null) {
            Logger.printException(() -> "Unknown VideoState encountered: " + enumName);
        } else if (currentVideoState != state) {
            Logger.printDebug(() -> "VideoState changed to: " + state);
            currentVideoState = state;
            onChange.invoke(state);
        }
    }

    /**
     * Depending on which hook this is called from,
     * this value may not be up to date with the actual playback state.
     */
    @Nullable
    public static VideoState getCurrent() {
        return currentVideoState;
    }
}
