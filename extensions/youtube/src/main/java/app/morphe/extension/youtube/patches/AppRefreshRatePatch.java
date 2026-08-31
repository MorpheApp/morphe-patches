/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;

import app.morphe.extension.shared.patches.BaseAppRefreshRatePatch;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;
import kotlin.Unit;

@SuppressWarnings("unused")
public final class AppRefreshRatePatch {

    /**
     * Injection point.
     */
    public static void initialize(Activity activity) {
        if (!BaseAppRefreshRatePatch.isPatchEnabled()) {
            return;
        }

        VideoState.getOnChange().addObserver((VideoState state) -> {
            updatePlayerIsActive(PlayerType.getCurrent(), state);
            return Unit.INSTANCE;
        });

        PlayerType.getOnChange().addObserver((PlayerType type) -> {
            updatePlayerIsActive(type, VideoState.getCurrent());
            return Unit.INSTANCE;
        });
    }

    private static void updatePlayerIsActive(PlayerType type, VideoState state) {
        final boolean isPlaying = state == VideoState.PLAYING;
        BaseAppRefreshRatePatch.setVideoPlayerIsActive(
                isPlaying && type == PlayerType.WATCH_WHILE_MAXIMIZED,
                isPlaying && type == PlayerType.WATCH_WHILE_FULLSCREEN
        );
    }
}
