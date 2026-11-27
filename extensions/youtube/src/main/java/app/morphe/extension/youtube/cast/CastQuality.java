/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

/**
 * Highest video quality sent to the Cast device. The device can play less if its network is slow.
 */
public enum CastQuality {
    P2160(2160),
    P1440(1440),
    P1080(1080),
    P720(720),
    P480(480);

    final int maxHeight;

    CastQuality(int maxHeight) {
        this.maxHeight = maxHeight;
    }
}
