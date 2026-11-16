/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Paint;
import android.icu.text.BreakIterator;
import android.text.Layout;

import java.util.List;

/**
 * Where each word of a laid out line sits, and where each of the line's own lines runs. The word
 * positions are snapped to the character clusters the line breaks at, so a word is never split
 * across a break by the sweep that lights it up.
 */
final class WordGeometry {

    private WordGeometry() {
    }

    /** The measured geometry of every word and every line of the laid out text. */
    record WordMetrics(float[] wordLeadX, float[] wordTrailX, int[] wordLine,
                       int[] wordWrappedLine, int[] lineTop, int[] lineBottom,
                       float[] lineLeft, float[] lineRight, int lineCount) {
    }

    static WordMetrics computeWordGeometry(Layout layout, CharSequence text, Paint paint,
                                           List<WordTiming> timings, int origStart) {
        final int wordCount = timings.size();
        float[] wordLeadX = new float[wordCount];
        float[] wordTrailX = new float[wordCount];
        int[] wordLine = new int[wordCount];
        int[] wordWrappedLine = new int[wordCount];
        int[] clusters = clusterBoundaries(text);
        for (int i = 0; i < wordCount; i++) {
            WordTiming timing = timings.get(i);
            final int s = timing.start() + origStart;
            final int e = Math.min(timing.end() + origStart, text.length());
            if (s < 0 || s >= text.length() || s >= e) {
                wordWrappedLine[i] = -1;
                continue;
            }
            final int snappedStart = snapToClusterStart(clusters, s);
            final int snappedEnd = snapToClusterEnd(clusters, e);
            final int line = lineDrawing(layout, s);
            // e lands on the break when the word ends its line; its last character never does.
            final int endLine = lineDrawing(layout, e - 1);
            // Both ends of a word sit on one of its lines, whatever an offset that lands on a
            // break reports for itself.
            final float lineLeft = Math.min(layout.getLineLeft(line),
                    layout.getLineRight(line));
            final float lineRight = Math.max(layout.getLineLeft(line),
                    layout.getLineRight(line));
            final float lead = Math.min(
                    Math.max(layout.getPrimaryHorizontal(snappedStart), lineLeft), lineRight);
            final boolean isRtl = isRightToLeftLine(layout, line) || layout.isRtlCharAt(s);
            final float trail;
            if (endLine != line) {
                // measureText reads the raw text, so a smaller size span is worth nothing to
                // it and the word measures wider than it is drawn.
                final float width = paint.measureText(text, s, e);
                final float measured = isRtl ? lead - width : lead + width;
                trail = Math.min(Math.max(measured, lineLeft), lineRight);
            } else {
                final float end = layout.getPrimaryHorizontal(snappedEnd);
                if (isRtl) {
                    // In RTL, text runs right-to-left, so end < lead.
                    // If end >= lead, the offset wrapped to the start of the next line,
                    // so the word ends at the line's left boundary.
                    trail = end >= lead ? lineLeft : Math.min(Math.max(end, lineLeft), lineRight);
                } else {
                    // That offset reports where the next line starts; a word ending its line ends at its right.
                    trail = end <= lead ? lineRight : Math.min(Math.max(end, lineLeft), lineRight);
                }
            }
            wordLeadX[i] = lead;
            wordTrailX[i] = trail;
            wordLine[i] = line;
            wordWrappedLine[i] = endLine != line ? endLine : -1;
        }
        final int lineCount = layout.getLineCount();
        int[] lineTop = new int[lineCount];
        int[] lineBottom = new int[lineCount];
        float[] lineLeft = new float[lineCount];
        float[] lineRight = new float[lineCount];
        for (int ln = 0; ln < lineCount; ln++) {
            lineTop[ln] = layout.getLineTop(ln);
            lineBottom[ln] = layout.getLineBottom(ln);
            lineLeft[ln] = Math.min(layout.getLineLeft(ln), layout.getLineRight(ln));
            lineRight[ln] = Math.max(layout.getLineLeft(ln), layout.getLineRight(ln));
        }
        return new WordMetrics(wordLeadX, wordTrailX, wordLine, wordWrappedLine,
                lineTop, lineBottom, lineLeft, lineRight, lineCount);
    }

    private static int[] clusterBoundaries(CharSequence text) {
        final int length = text.length();
        int[] boundaries = new int[length + 1];
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text.toString());
        int clusterStart = iterator.first();
        while (clusterStart != BreakIterator.DONE && clusterStart <= length) {
            final int next = iterator.next();
            final int clusterEnd = next == BreakIterator.DONE ? length : next;
            for (int offset = clusterStart; offset <= clusterEnd && offset <= length; offset++) {
                boundaries[offset] = clusterStart;
            }
            clusterStart = next;
        }
        return boundaries;
    }

    private static int snapToClusterStart(int[] clusters, int offset) {
        return clusters[Math.min(Math.max(offset, 0), clusters.length - 1)];
    }

    private static int snapToClusterEnd(int[] clusters, int offset) {
        final int index = Math.min(Math.max(offset, 0), clusters.length - 1);
        if (clusters[index] == index) {
            return index;
        }
        final int clusterStart = clusters[index];
        int probe = index;
        while (probe < clusters.length && clusters[probe] == clusterStart) {
            probe++;
        }
        return Math.min(probe, clusters.length - 1);
    }

    private static int lineDrawing(Layout layout, int offset) {
        int low = 0;
        int high = layout.getLineCount() - 1;
        while (low < high) {
            final int mid = (low + high) >>> 1;
            if (offset < layout.getLineStart(mid + 1)) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    static boolean isRightToLeftLine(Layout layout, int line) {
        if (layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
            return true;
        }
        if (line >= 0 && line < layout.getLineCount()) {
            final int start = layout.getLineStart(line);
            final int end = layout.getLineEnd(line);
            CharSequence text = layout.getText();
            for (int i = start; i < end && i < text.length(); i++) {
                byte dir = Character.getDirectionality(text.charAt(i));
                if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                    return true;
                } else if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                    return false;
                }
            }
        }
        return false;
    }
}
