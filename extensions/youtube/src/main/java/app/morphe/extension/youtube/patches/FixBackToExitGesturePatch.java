/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;

import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

@SuppressWarnings("unused")
public class FixBackToExitGesturePatch {
    /**
     * Time between two back button presses.
     */
    private static final long PRESSED_TIMEOUT_MILLISECONDS = 1500L;

    /**
     * Last time back button was pressed.
     */
    private static long lastTimeBackPressed = 0;

    /**
     * Whether {@link Activity#moveTaskToBack(boolean)} was called by the patch.
     */
    private static final AtomicBoolean isTaskToBack = new AtomicBoolean(false);

    /**
     * State whether the scroll position reaches the top.
     */
    private static final AtomicBoolean isTopView = new AtomicBoolean(false);

    /**
     * Injection point.
     * Disable PiP mode when {@link Activity#moveTaskToBack(boolean)} is triggered by the patch.
     */
    public static boolean getMoveTaskToBackState(boolean original) {
        return !isTaskToBack.get() && original;
    }

    /**
     * Handle the event after clicking the back button.
     *
     * @param activity The activity, the app is launched with to finish.
     */
    public static void onBackPressed(Activity activity) {
        if (!isTopView.get()) return;
        long now = System.currentTimeMillis();

        // If the time between two back button presses does not reach PRESSED_TIMEOUT_MILLISECONDS,
        // set lastTimeBackPressed to the current time.
        if (now - lastTimeBackPressed < PRESSED_TIMEOUT_MILLISECONDS) {
            // In the latest YouTube, there is an issue where the video pauses if 'onDestroy()' is called while the video is minimized and playing,
            // and then 'onCreate()' is called again (Unpatched YouTube issue).
            // See: https://github.com/MorpheApp/morphe-patches/issues/279
            // As a workaround for this issue, use 'moveTaskToBack()' instead of 'finish()'
            // when the video is minimized and playing to avoid the call to 'onDestroy()'.
            if (PlayerType.getCurrent() == PlayerType.WATCH_WHILE_MINIMIZED
                    && VideoState.getCurrent() == VideoState.PLAYING
                    && activity.moveTaskToBack(true)) {
                Logger.printDebug(() -> "Moving task to back");
                isTaskToBack.set(true);
                // Change to false after PiP mode availability is checked.
                Utils.runOnMainThreadDelayed(() -> isTaskToBack.compareAndSet(true, false), 500);
            } else {
                Logger.printDebug(() -> "Activity is closed");
                activity.finish();
            }
        } else {
            lastTimeBackPressed = now;
            Utils.runOnMainThreadDelayed(() -> {
                // After the timeout, the user should double-click the back button again.
                isTopView.compareAndSet(true, false);
            }, PRESSED_TIMEOUT_MILLISECONDS);
        }
    }

    /**
     * Handle the event when the homepage list of views is being scrolled.
     */
    public static void onScrollingViews() {
        Logger.printDebug(() -> "Views are scrolling");
        isTopView.set(false);
    }

    /**
     * Handle the event when the homepage list of views reached the top.
     */
    public static void onTopView() {
        Logger.printDebug(() -> "Scrolling reached the top");
        isTopView.set(true);
    }
}
