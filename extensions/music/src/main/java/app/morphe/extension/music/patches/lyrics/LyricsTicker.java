/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.os.Handler;
import android.os.Looper;

final class LyricsTicker {

    /** How often to check whether the current lyric line changed. */
    private static final long TICK_INTERVAL_MS = 300;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable task;

    LyricsTicker(Runnable task) {
        this.task = task;
    }

    /** (Re)starts the ticker. Safe to call repeatedly. */
    void schedule() {
        handler.removeCallbacks(task);
        handler.postDelayed(task, TICK_INTERVAL_MS);
    }

    /** Stops the ticker. Does not touch any destination state. */
    void stop() {
        handler.removeCallbacks(task);
    }
}
