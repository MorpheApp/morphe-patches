/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The breathing-dots motion that plays over a silence between two lyric lines, taking the place of
 * the section label of the line that is about to start.
 *
 * <p>Any silence long enough to read as an instrumental break gets the treatment, whether or not the
 * lines around it carry labels; shorter ones are left alone. A line that does have a label swaps it
 * for the dots, and a line that has none opens up to the height a label would have taken so the
 * dots have somewhere to sit. Background vocal lines are skipped — they belong under their lead
 * line, so a lead line's own timing is what measures the gap.
 *
 * <p>The shape follows the Apple Music interlude dots as reimplemented in
 * {@code LyricsTimelineBuilder} / {@code AppleMusicMotion.Dots} of the Spicy Lyrics Android player:
 * the label fades out, the dots light up one after another and hold, and — unlike the reference,
 * where the whole group shrinks at once — they also vanish one after another, with the label
 * fading back in as they go. Nothing here scales or springs; the motion is opacity alone, with the
 * slot a label-less line opens up to match the fade it plays against.
 *
 * <p>Every phase is derived from the playback position rather than accumulated over frames, so a
 * seek into the middle of a gap lands on the right frame and a stall cannot desynchronise the motion.
 * That makes {@link #stateAt} a pure function and keeps the ticking view side effect free apart from
 * applying the returned {@link State}.
 */
public final class SectionDotsMotion {

    /** Dots shown over the gap. */
    public static final int DOT_COUNT = 3;

    /** Silences shorter than this are breathing room, not an instrumental break. */
    public static final long MIN_GAP_MS = 3000L;

    /** Milliseconds a line is assumed to be sung for before its own text length is added. */
    private static final long INFERRED_FLOOR_MS = 1200L;
    /** Milliseconds of singing per character, for a line that carries only a start time. */
    private static final long INFERRED_MS_PER_CHAR = 90L;
    /** The most characters that lengthen the guess, so a long line cannot reach the next line. */
    private static final int INFERRED_MAX_CHARS = 40;
    /** The longest a line is ever assumed to be sung for. */
    private static final long INFERRED_CEILING_MS = 5000L;

    /**
     * A silence long enough to play the dots, anchored on the line that follows it.
     *
     * @param tagLineIndex Index into the lyric lines of the line the motion plays on, which is also
     *                     the line index of its section label when it has one.
     * @param gapStartMs   Playback position where the previous line stops being sung, or the start
     *                     of playback for a silence before the first line.
     * @param gapEndMs     Playback position where the next line starts.
     */
    public record Gap(int tagLineIndex, long gapStartMs, long gapEndMs) {
    }

    /**
     * The motion at one playback position.
     *
     * @param pillAlpha    Opacity of the section label: 1 outside the gap, 0 while the dots are up.
     * @param dotsVisible  Whether the dot row should be shown at all.
     * @param groupAlpha   Opacity of the dot row as a whole.
     * @param dotAlpha     Opacity per dot, length {@link #DOT_COUNT}, lighting up and going out in turn.
     * @param expand       How far a line without a label opens up to make room for the dots, 0 to 1.
     *                    Lines that do have a label ignore this and swap it for the dots instead.
     */
    public record State(float pillAlpha, boolean dotsVisible, float groupAlpha, float[] dotAlpha,
                        float expand) {
    }

    /** The resting state: the label alone, dots gone, nothing opened up. */
    public static final State IDLE = new State(1f, false, 0f, new float[DOT_COUNT], 0f);

    /** Reference grow-in length at full size; scaled down for shorter gaps. */
    private static final float ENTER_MS = 3000f;
    /** Reference dwell length, part of the head of the cycle the fade schedule is packed into. */
    private static final float DIP_MS = 3000f;
    /** Reference hold length, part of the head of the cycle the fade schedule is packed into. */
    private static final float STILL_MS = 200f;
    /** A dim dot still waits its turn to light up. */
    private static final float DOT_UNLIT = 0.4f;

    /** How long the label takes to fade out as the dots come up. */
    private static final float PILL_FADE_MS = 250f;
    /** How long each dot takes to vanish. */
    private static final float VANISH_DOT_MS = 200f;
    /** Delay before each next dot starts vanishing, so they go one after another. */
    private static final float VANISH_STAGGER_MS = 150f;
    /** The whole staggered vanish, measured from the first dot leaving. */
    private static final float VANISH_TOTAL_MS = VANISH_DOT_MS + VANISH_STAGGER_MS * (DOT_COUNT - 1);
    /** The dots are gone this long before the next line starts, so the label can come back first. */
    private static final float PRE_HIDE_MS = 500f;

    private SectionDotsMotion() {
    }

    /**
     * Finds every silence long enough to play the dots, in line order.
     *
     * <p>A gap belongs to the line that follows it, because that is the row the dots stand on. The
     * silence before the very first line counts too, and plays against that line's own row.
     *
     * <p>The end of the previous line is its {@link LyricsLine#endTimeMs() end}, falling back to
     * that line's last word when the end is unknown (a text filter can drop it) and to nothing at
     * all when neither is known, in which case there is no gap to measure.
     *
     * @param lines The lyric lines, in playback order.
     * @return The gaps to animate, or an empty list when there are none.
     */
    @NonNull
    public static List<Gap> computeGaps(@Nullable List<LyricsLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<Gap> gaps = null;
        // The silence before the first line is a break like any other; it plays against that line's
        // own row, which is where its label would sit. Only the first line counts — a later line
        // starting late is an ordinary silence, measured from the line before it in the loop below.
        int firstIndex = 0;
        while (firstIndex < lines.size() && lines.get(firstIndex) == null) {
            firstIndex++;
        }
        if (firstIndex < lines.size()) {
            final LyricsLine first = lines.get(firstIndex);
            if (first.startTimeMs() >= MIN_GAP_MS) {
                gaps = new ArrayList<>(4);
                gaps.add(new Gap(firstIndex, 0L, first.startTimeMs()));
            }
        }
        int previousLead = -1;
        for (int i = 0; i < lines.size(); i++) {
            final LyricsLine line = lines.get(i);
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
     * The motion at {@code positionMs}, a pure function of the position inside
     * {@code [gapStartMs, gapEndMs)}.
     *
     * @param gapStartMs Where the previous line stops being sung.
     * @param gapEndMs   Where the next line starts.
     * @param positionMs Current playback position.
     * @return The label and dot opacities, and how far a line without a label opens up, at that
     *         position. Positions outside the gap, or gaps too short to animate, give {@link #IDLE}.
     */
    @NonNull
    public static State stateAt(long gapStartMs, long gapEndMs, long positionMs) {
        final long gapMs = gapEndMs - gapStartMs;
        if (gapMs < MIN_GAP_MS || positionMs < gapStartMs || positionMs >= gapEndMs) {
            return IDLE;
        }
        final float t = positionMs - gapStartMs;

        // A gap shorter than the tail work would collide with itself; the dots are not worth it.
        final float vanishStart = gapMs - PRE_HIDE_MS - VANISH_TOTAL_MS;
        if (vanishStart <= 0f) {
            return IDLE;
        }

        // Every phase is a fraction of the head of the reference cycle, so a short silence plays the
        // whole motion rather than just its opening. They are laid out backwards from the vanish, so
        // they tile the silence instead of overlapping when there is not room for all of them.
        final float factor = Math.min(vanishStart / (ENTER_MS + DIP_MS + STILL_MS), 1f);
        final float enterEnd = ENTER_MS * factor;

        final float pillFade = t < PILL_FADE_MS
                ? 1f - smooth(t / PILL_FADE_MS)
                : smooth((t - vanishStart - VANISH_TOTAL_MS) / PILL_FADE_MS);
        final float pillAlpha = clamp(pillFade);
        final boolean dotsVisible = t < vanishStart + VANISH_TOTAL_MS;
        final float groupAlpha = t < enterEnd ? smooth(t / enterEnd) : 1f;

        // Each dot starts leaving its own moment after the one before it, so the last one finishes
        // exactly when the row is hidden.
        final float dotSpan = Math.max(1f, (vanishStart - enterEnd) / DOT_COUNT);
        final float[] dotAlpha = new float[DOT_COUNT];
        for (int i = 0; i < DOT_COUNT; i++) {
            final float lit = DOT_UNLIT + (1f - DOT_UNLIT)
                    * clamp((t - enterEnd - dotSpan * i) / dotSpan);
            final float gone = 1f - smooth(clamp(
                    (t - (vanishStart + VANISH_STAGGER_MS * i)) / VANISH_DOT_MS));
            dotAlpha[i] = Math.min(lit, gone);
        }

        // A line with no label opens up over the same stretch the dots fade in on, and closes again
        // in the pause after they are gone, so nothing ever moves under a visible dot.
        final float dotsGone = vanishStart + VANISH_TOTAL_MS;
        final float expand = t < enterEnd ? smooth(t / enterEnd)
                : 1f - smooth(clamp((t - dotsGone) / PRE_HIDE_MS));

        return new State(pillAlpha, dotsVisible, groupAlpha, dotAlpha, clamp(expand));
    }

    /**
     * The end of a line, falling back to its last word when the end is unknown and to a guess
     * scaled by the text when the line carries neither.
     */
    private static long effectiveEndMs(@Nullable LyricsLine line) {
        if (line == null) {
            return LyricsLine.NO_TIME;
        }
        final long end = line.endTimeMs();
        if (end > line.startTimeMs()) {
            return end;
        }
        final List<Word> words = line.words();
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
     * Where a line that is only synced by its start is guessed to stop being sung.
     *
     * <p>Line-timed LRC gives every line a start but no end, so without this a gap between two
     * sections of an LRC file could never be measured and the dots would never play. A guess
     * scales with the line's text, because a longer line is sung for longer, and it only ever
     * decides whether a real silence was long enough to animate. It is never written back to
     * the line, so scrolling, highlighting and saving all keep reading the original timing.
     *
     * @return The guessed end, still clamped above {@link LyricsLine#startTimeMs()}.
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

    /** Smoothstep, the curve the reference easing uses. */
    private static float smooth(float v) {
        final float x = clamp(v);
        return x * x * (3f - 2f * x);
    }
}