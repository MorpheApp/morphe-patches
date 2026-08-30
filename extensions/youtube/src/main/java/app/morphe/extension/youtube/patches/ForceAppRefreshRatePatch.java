/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;

import android.content.res.Configuration;

import app.morphe.extension.shared.patches.BaseForceAppRefreshRatePatch;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;
import kotlin.Unit;

@SuppressWarnings("unused")
public final class ForceAppRefreshRatePatch {

    public static void initialize(Activity activity) {
        VideoState.getOnChange().addObserver((VideoState state) -> {
            BaseForceAppRefreshRatePatch.videoPlayerIsActive(
                    state == VideoState.PLAYING
                            && PlayerType.getCurrent().isMaximizedOrFullscreen(),
                    isPortrait(activity)
            );
            return Unit.INSTANCE;
        });

        PlayerType.getOnChange().addObserver((PlayerType type) -> {
            BaseForceAppRefreshRatePatch.videoPlayerIsActive(
                    VideoState.getCurrent() == VideoState.PLAYING
                            && type.isMaximizedOrFullscreen(),
                    isPortrait(activity)
            );
            return Unit.INSTANCE;
        });
    }

    private static boolean isPortrait(Activity activity) {
        return activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
    }
}
