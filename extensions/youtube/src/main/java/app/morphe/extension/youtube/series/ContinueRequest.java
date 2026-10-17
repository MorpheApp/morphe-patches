/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

/**
 * The network callback and the timeout of one Continue tap. Only one of them continues.
 */
final class ContinueRequest {
    private int generation;
    private boolean waiting;

    int begin() {
        waiting = true;
        return ++generation;
    }

    boolean waiting() {
        return waiting;
    }

    void cancel() {
        waiting = false;
        generation++;
    }

    boolean stale(int token) {
        return generation != token;
    }

    boolean claim(int token) {
        if (!waiting || stale(token)) return false;
        waiting = false;
        return true;
    }
}
