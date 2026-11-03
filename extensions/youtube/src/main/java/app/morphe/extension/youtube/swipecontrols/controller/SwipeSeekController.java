/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller;

import android.view.MotionEvent;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.swipecontrols.misc.SwipeControlsOverlay;

/** Adapts player touches and playback state to a horizontal seek session. */
public final class SwipeSeekController {
    private final SwipeControlsOverlay overlay;
    private final SwipeSeekSession session = new SwipeSeekSession();

    public SwipeSeekController(SwipeControlsOverlay overlay) {
        this.overlay = overlay;
    }

    public static boolean isVideoSeekable() {
        return !VideoInformation.isLiveStream()
                && !VideoInformation.getVideoId().isEmpty()
                && VideoInformation.getVideoLength() > 0
                && VideoInformation.getVideoTime() >= 0;
    }

    public boolean isSeeking() {
        return session.isActive();
    }

    public boolean isCurrentVideo() {
        return isVideoSeekable() && session.isCurrentVideo(VideoInformation.getVideoId());
    }

    public void onScroll(MotionEvent to, float density, int sensitivityPercent) {
        if (!isVideoSeekable()) {
            onUp(true);
            return;
        }
        if (!session.isActive()) {
            session.begin(VideoInformation.getVideoId(), VideoInformation.getVideoTime(),
                    VideoInformation.getVideoLength(), to.getX());
        }
        session.update(to.getX(), density, sensitivityPercent);
        if (session.isActive()) {
            overlay.onSeekChanged(session.getTarget(), session.getOffset());
        }
    }

    public void onUp(boolean isCancelled) {
        if (!session.isActive()) return;
        try {
            long target = session.finish(isCancelled || !isVideoSeekable(), VideoInformation.getVideoId());
            if (target >= 0) VideoInformation.seekTo(target);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to commit swipe seek", ex);
        } finally {
            reset();
            overlay.onSeekEnded();
        }
    }

    public void reset() {
        session.reset();
    }
}
