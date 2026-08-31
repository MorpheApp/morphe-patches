/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

/**
 * Lyrics of a single track, either synced (each line carries a timestamp) or plain.
 *
 * @param providerName Provider name shown to the user, for example {@code LRCLIB}.
 */
public record Lyrics(List<LyricsLine> lines, String providerName, boolean synced) {

    /** Marker for a track that was looked up successfully but has no lyrics anywhere. */
    public static final Lyrics NOT_FOUND = new Lyrics(Collections.emptyList(), "", false);

    public static final String CAPTIONS_PROVIDER = "Captions";

    public Lyrics {
        lines = Collections.unmodifiableList(lines);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public boolean isSubtitles() {
        return CAPTIONS_PROVIDER.equals(providerName);
    }

    /**
     * Index of the line that should be highlighted at the given playback position,
     * or -1 if playback has not reached the first line yet.
     *
     * <p>No binary search: between two calls the position advances by one line at most,
     * so the hint answers almost every call in a few comparisons.
     *
     * @param positionMs Current playback position.
     * @param hintIndex  Index returned by the previous call, or -1.
     */
    public int indexForPosition(long positionMs, int hintIndex) {
        if (!synced || lines.isEmpty()) {
            return -1;
        }

        final int size = lines.size();

        // Fast path: still inside the hinted line, or moved into the next one.
        if (hintIndex >= 0 && hintIndex < size) {
            if (positionMs >= lines.get(hintIndex).startTimeMs()) {
                final int next = hintIndex + 1;
                if (next >= size || positionMs < lines.get(next).startTimeMs()) {
                    return hintIndex;
                }
                if (next + 1 >= size || positionMs < lines.get(next + 1).startTimeMs()) {
                    return next;
                }
            }
        }

        int result = -1;
        for (int i = 0; i < size; i++) {
            if (lines.get(i).startTimeMs() > positionMs) {
                break;
            }
            result = i;
        }
        return result;
    }

    @NonNull
    @Override
    public String toString() {
        return "Lyrics{" + providerName + ", synced=" + synced + ", lines=" + lines.size() + "}";
    }
}
