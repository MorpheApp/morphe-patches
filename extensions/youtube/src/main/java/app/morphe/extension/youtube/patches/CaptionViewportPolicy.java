/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Encoded content geometry belongs to its video, never to the last prefetched response. */
final class CaptionViewportPolicy {
    static float scaleFontSize(float size, int percentage) {
        return size * Math.max(30, Math.min(150, percentage)) / 100f;
    }

    private final LinkedHashMap<String, Double> aspects = new LinkedHashMap<>(32, 0.75f, true);
    private String selected;

    synchronized void select(String videoId) {
        selected = videoId != null && videoId.matches("[A-Za-z0-9_-]{11}") ? videoId : null;
    }

    synchronized void record(String videoId, int width, int height) {
        if (videoId == null
                || !videoId.matches("[A-Za-z0-9_-]{11}")
                || width <= 0
                || height <= 0
                || width > 16384
                || height > 16384) return;
        double aspect = width / (double) height;
        if (aspect < 0.1 || aspect > 10) return;
        aspects.put(videoId, aspect);
        if (aspects.size() > 32) {
            Iterator<Map.Entry<String, Double>> iterator = aspects.entrySet().iterator();
            while (iterator.hasNext()) {
                if (!iterator.next().getKey().equals(selected)) {
                    iterator.remove();
                    break;
                }
            }
        }
    }

    synchronized int height(
            int width, int height, boolean fullscreenFit, boolean landscapeTransition) {
        if (!fullscreenFit || width <= 0 || height <= 0 || selected == null) return height;
        // Animated bounds cross the stable portrait/near-square guards. The target
        // configuration, rather than one intermediate frame, identifies landscape.
        if (!landscapeTransition && (width < height || 4L * height < 3L * width)) return height;
        Double aspect = aspects.get(selected);
        if (aspect == null) return height;
        return (int) Math.min(height, Math.max(1L, Math.round(width / aspect)));
    }

}
