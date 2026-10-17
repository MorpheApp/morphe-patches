/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.session;

import android.os.Handler;
import android.os.Looper;

final class LyricsTicker {

    private static final long TICK_INTERVAL_MS = 300;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable task;

    LyricsTicker(Runnable task) {
        this.task = task;
    }

    /** (Re)starts the ticker; safe to call repeatedly. */
    void schedule() {
        handler.removeCallbacks(task);
        handler.postDelayed(task, TICK_INTERVAL_MS);
    }

    /** Stops the ticker, leaving destination state alone. */
    void stop() {
        handler.removeCallbacks(task);
    }
}
