/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

/**
 * Tracks 1–5 selected lyric line indices during Share Lyrics mode.
 */
final class LyricsShareSelection {

    static final int LYRICS_SHARE_LINE_LIMIT = 5;

    private final TreeSet<Integer> selectedIndices = new TreeSet<>();
    private boolean limitReached;

    /** {@return true if the line contains non-empty sung text rather than a blank or instrumental marker} */
    static boolean isSelectableLine(@Nullable LyricsLine line) {
        if (line == null) {
            return false;
        }
        String text = LyricsTextUtils.displayLineText(line);
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return !trimmed.isEmpty() && !"♪".equals(trimmed) && !"♫".equals(trimmed);
    }

    /**
     * Pre-selects currently active selectable lines in ascending song order (up to
     * {@link #LYRICS_SHARE_LINE_LIMIT}), falling back to {@code primaryActiveIndex} or
     * {@code highlightedIndex} if the active set has no selectable lines.
     */
    void preselectActiveLines(
            @Nullable List<LyricsLine> lines,
            @Nullable Set<Integer> activeIndices,
            int primaryActiveIndex,
            int highlightedIndex) {
        clear();
        if (lines == null || lines.isEmpty()) {
            return;
        }
        int lineCount = lines.size();
        if (activeIndices != null && !activeIndices.isEmpty()) {
            TreeSet<Integer> sortedActive = new TreeSet<>(activeIndices);
            for (int idx : sortedActive) {
                if (idx >= 0 && idx < lineCount && !lines.get(idx).isBG() && isSelectableLine(lines.get(idx))) {
                    selectedIndices.add(idx);
                    if (selectedIndices.size() >= LYRICS_SHARE_LINE_LIMIT) {
                        break;
                    }
                }
            }
            if (selectedIndices.isEmpty()) {
                for (int idx : sortedActive) {
                    if (idx >= 0 && idx < lineCount && isSelectableLine(lines.get(idx))) {
                        selectedIndices.add(idx);
                        if (selectedIndices.size() >= LYRICS_SHARE_LINE_LIMIT) {
                            break;
                        }
                    }
                }
            }
        }
        if (selectedIndices.isEmpty()
                && primaryActiveIndex >= 0
                && primaryActiveIndex < lineCount
                && isSelectableLine(lines.get(primaryActiveIndex))) {
            selectedIndices.add(primaryActiveIndex);
        }
        if (selectedIndices.isEmpty()
                && highlightedIndex >= 0
                && highlightedIndex < lineCount
                && isSelectableLine(lines.get(highlightedIndex))) {
            selectedIndices.add(highlightedIndex);
        }
    }

    /**
     * Toggles selection for {@code index}.
     *
     * @return {@code true} if the selection changed, or {@code false} if the 5-line limit blocked adding.
     */
    boolean toggle(int index) {
        if (index < 0) {
            return false;
        }
        if (selectedIndices.contains(index)) {
            selectedIndices.remove(index);
            limitReached = false;
            return true;
        }
        if (selectedIndices.size() >= LYRICS_SHARE_LINE_LIMIT) {
            limitReached = true;
            return false;
        }
        selectedIndices.add(index);
        limitReached = false;
        return true;
    }

    void clear() {
        selectedIndices.clear();
        limitReached = false;
    }

    boolean isSelected(int index) {
        return selectedIndices.contains(index);
    }

    int count() {
        return selectedIndices.size();
    }

    boolean hasSelection() {
        return !selectedIndices.isEmpty();
    }

    boolean isLimitReached() {
        return limitReached || selectedIndices.size() >= LYRICS_SHARE_LINE_LIMIT;
    }
    Set<Integer> selectedIndices() {
        return Collections.unmodifiableSet(selectedIndices);
    }
    List<Integer> selectedIndicesInOrder() {
        return new ArrayList<>(selectedIndices);
    }

    /** Extracts the formatted non-blank text of selected lines in ascending song order. */
    List<String> selectedLinesInOrder(
            @Nullable List<LyricsLine> lines,
            IntFunction<String> textResolver) {
        if (lines == null || lines.isEmpty() || selectedIndices.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(selectedIndices.size());
        for (int idx : selectedIndices) {
            if (idx >= 0 && idx < lines.size()) {
                String resolved = textResolver.apply(idx);
                if (resolved != null) {
                    String trimmed = resolved.trim();
                    if (!trimmed.isEmpty() && !"♪".equals(trimmed)) {
                        out.add(trimmed);
                    }
                }
            }
        }
        return out;
    }
}
