/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.music.patches.lyrics.model;

import java.util.List;
import java.util.Objects;

/** Shared by both surfaces, so polling either one never advances a frame twice. */
public final class ArtistLineCycle {
    private List<String> frames = List.of();
    private Object token;
    private int index;
    private long shownAt;
    private boolean pending;

    public String update(List<String> nextFrames, Object nextToken, long now, int minimum, int maximum) {
        if (!frames.equals(nextFrames)) {
            frames = List.copyOf(nextFrames);
            index = 0;
            shownAt = now;
            pending = false;
        } else if (!Objects.equals(token, nextToken)) {
            pending = true;
        }
        token = nextToken;
        long elapsed = Math.max(0, now - shownAt);
        int min = Math.max(0, minimum);
        int max = Math.max(Math.max(1, min), maximum);
        if (frames.size() > 1 && elapsed >= min && (pending || elapsed >= max)) {
            index = (index + 1) % frames.size();
            shownAt = now;
            pending = false;
        }
        return current(nextFrames);
    }

    public String current(List<String> fallback) {
        return frames.equals(fallback) && !frames.isEmpty() ? frames.get(index) : fallback.get(0);
    }

    public void reset() {
        frames = List.of();
        token = null;
        pending = false;
    }
}
