/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5134
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

/**
 * Values and colors between two others, for what the overlays draw while they move.
 */
final class Blend {
    private Blend() {
    }

    static float clamp01(float value) {
        return Math.max(0, Math.min(1, value));
    }

    static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /**
     * @param alpha Share of the alpha that the color has.
     */
    static int withAlpha(int color, float alpha) {
        final int scaled = Math.round((color >>> 24) * clamp01(alpha));
        return (scaled << 24) | (color & 0x00FFFFFF);
    }

    static int lerpColor(int from, int to, float t) {
        final int a = Math.round(lerp(from >>> 24, to >>> 24, t));
        final int r = Math.round(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, t));
        final int g = Math.round(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, t));
        final int b = Math.round(lerp(from & 0xFF, to & 0xFF, t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
