/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public final class SectionDotsMotion {

    public static final int DOT_COUNT = 3;

    /** Below this a silence is breathing room, not a break. */
    public static final long MIN_GAP_MS = 3000L;

    private static final long INFERRED_FLOOR_MS = 1200L;
    private static final long INFERRED_MS_PER_CHAR = 90L;
    /** Caps the guess so a long line cannot reach into the next one. */
    private static final int INFERRED_MAX_CHARS = 40;
    private static final long INFERRED_CEILING_MS = 5000L;

    /**
     * A silence long enough to play the dots, anchored on the line that follows it.
     *
     * @param tagLineIndex The line the motion plays on, which is where its label would sit.
     * @param gapStartMs   Where the previous line stops, or 0 for a silence before the first line.
     */
    public record Gap(int tagLineIndex, long gapStartMs, long gapEndMs) {
    }

    /**
     * @param pillAlpha  Opacity of the label: 1 outside the gap, 0 while the dots are up.
     * @param groupAlpha Opacity of the dot row.
     * @param dotAlpha   Per dot, lighting up and going out in turn.
     * @param expand     How far a line without a label opens up for the dots, 0 to 1.
     */
    public record State(float pillAlpha, boolean dotsVisible, float groupAlpha, float[] dotAlpha,
                        float expand) {
    }

    public static final State IDLE = new State(1f, false, 0f, new float[DOT_COUNT], 0f);

    /** Reference cycle head at full size, scaled down for shorter gaps. */
    private static final float ENTER_MS = 3000f;
    private static final float DIP_MS = 3000f;
    private static final float STILL_MS = 200f;
    private static final float DOT_UNLIT = 0.4f;
    private static final float PILL_FADE_MS = 250f;
    private static final float VANISH_DOT_MS = 200f;
    /** Stagger, so the dots go one after another. */
    private static final float VANISH_STAGGER_MS = 150f;
    private static final float VANISH_TOTAL_MS = VANISH_DOT_MS + VANISH_STAGGER_MS * (DOT_COUNT - 1);
    /** Lets the label fade back in before the next line starts. */
    private static final float PRE_HIDE_MS = 500f;

    private SectionDotsMotion() {
    }

    /**
     * Finds every silence long enough to play the dots, in line order. A gap belongs to the line
     * after it, which is the row the dots stand on; the silence before the first line counts too.
     *
     * @return The gaps to animate, empty when there are none.
     */
    @NonNull
    public static List<Gap> computeGaps(@Nullable List<LyricsLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<Gap> gaps = null;
        // Only the first line: a later one starting late is an ordinary silence, measured
        // from the line before it in the loop below.
        int firstIndex = 0;
        while (firstIndex < lines.size() && lines.get(firstIndex) == null) {
            firstIndex++;
        }
        if (firstIndex < lines.size()) {
            LyricsLine first = lines.get(firstIndex);
            if (first.startTimeMs() >= MIN_GAP_MS) {
                gaps = new ArrayList<>(4);
                gaps.add(new Gap(firstIndex, 0L, first.startTimeMs()));
            }
        }
        int previousLead = -1;
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            if (line == null || line.isBG()) {
                continue;
            }
            if (previousLead >= 0) {
                final long gapStart = effectiveEndMs(lines.get(previousLead));
                final long gapEnd = line.startTimeMs();
                if (gapStart > 0 && gapEnd > gapStart && gapEnd - gapStart >= MIN_GAP_MS) {
                    if (gaps == null) {
                        gaps = new ArrayList<>(4);
                    }
                    gaps.add(new Gap(i, gapStart, gapEnd));
                }
            }
            previousLead = i;
        }
        return gaps == null ? List.of() : Collections.unmodifiableList(gaps);
    }

    /**
     * The motion at {@code positionMs} within {@code [gapStartMs, gapEndMs)}. Positions outside
     * the gap, or gaps too short to animate, give {@link #IDLE}.
     */
    @NonNull
    public static State stateAt(long gapStartMs, long gapEndMs, long positionMs) {
        final long gapMs = gapEndMs - gapStartMs;
        if (gapMs < MIN_GAP_MS || positionMs < gapStartMs || positionMs >= gapEndMs) {
            return IDLE;
        }
        final float t = positionMs - gapStartMs;

        // Too short for the tail phases to fit.
        final float vanishStart = gapMs - PRE_HIDE_MS - VANISH_TOTAL_MS;
        if (vanishStart <= 0f) {
            return IDLE;
        }

        // Laid out backwards from the vanish so they tile a short silence instead of overlapping.
        final float factor = Math.min(vanishStart / (ENTER_MS + DIP_MS + STILL_MS), 1f);
        final float enterEnd = ENTER_MS * factor;

        final float pillFade = t < PILL_FADE_MS
                ? 1f - smooth(t / PILL_FADE_MS)
                : smooth((t - vanishStart - VANISH_TOTAL_MS) / PILL_FADE_MS);
        final float pillAlpha = clamp(pillFade);
        final boolean dotsVisible = t < vanishStart + VANISH_TOTAL_MS;
        final float groupAlpha = t < enterEnd ? smooth(t / enterEnd) : 1f;

        // Staggered so the last dot finishes exactly when the row hides.
        final float dotSpan = Math.max(1f, (vanishStart - enterEnd) / DOT_COUNT);
        float[] dotAlpha = new float[DOT_COUNT];
        for (int i = 0; i < DOT_COUNT; i++) {
            final float lit = DOT_UNLIT + (1f - DOT_UNLIT)
                    * clamp((t - enterEnd - dotSpan * i) / dotSpan);
            final float gone = 1f - smooth(clamp(
                    (t - (vanishStart + VANISH_STAGGER_MS * i)) / VANISH_DOT_MS));
            dotAlpha[i] = Math.min(lit, gone);
        }

        // Opens over the fade-in and closes after the dots go, so nothing moves under a visible dot.
        final float dotsGone = vanishStart + VANISH_TOTAL_MS;
        final float expand = t < enterEnd ? smooth(t / enterEnd)
                : 1f - smooth(clamp((t - dotsGone) / PRE_HIDE_MS));

        return new State(pillAlpha, dotsVisible, groupAlpha, dotAlpha, clamp(expand));
    }

    /** The end of a line: its own, else its last word's, else a guess from the text. */
    private static long effectiveEndMs(@Nullable LyricsLine line) {
        if (line == null) {
            return LyricsLine.NO_TIME;
        }
        final long end = line.endTimeMs();
        if (end > line.startTimeMs()) {
            return end;
        }
        List<Word> words = line.words();
        for (int i = words.size() - 1; i >= 0; i--) {
            final long wordEnd = words.get(i).endMs();
            if (wordEnd > line.startTimeMs()) {
                return wordEnd;
            }
        }
        final long inferred = inferredEndMs(line);
        return inferred > line.startTimeMs() ? inferred : LyricsLine.NO_TIME;
    }

    /**
     * Guesses where a start-only line stops being sung, scaled by its text. Without it an LRC gap
     * could never be measured. Never written back, so the original timing still drives everything.
     */
    private static long inferredEndMs(LyricsLine line) {
        final int chars = Math.min(line.text().length(), INFERRED_MAX_CHARS);
        if (chars <= 0) {
            return LyricsLine.NO_TIME;
        }
        return line.startTimeMs() + Math.min(
                INFERRED_FLOOR_MS + (long) chars * INFERRED_MS_PER_CHAR,
                INFERRED_CEILING_MS);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Smoothstep, the reference easing. */
    private static float smooth(float v) {
        final float x = clamp(v);
        return x * x * (3f - 2f * x);
    }
}