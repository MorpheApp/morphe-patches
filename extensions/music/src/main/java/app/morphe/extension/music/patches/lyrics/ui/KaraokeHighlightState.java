/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import java.util.List;

/**
 * Karaoke handoff: which line still wears sung word colors ({@code last}), which is
 * waiting to retire on a later tick ({@code pending}), and whether word sync is on.
 *
 * <p>Pure, so the handoff rule can be tested without a view.
 */
final class KaraokeHighlightState {

    /** Word timings of the built lines, read without holding their views. */
    interface Lines {
        int size();

        boolean wordsEmpty(int line);

        long firstStart(int line);

        long lastEnd(int line);
    }

    enum Kind {
        /** Paint the word colors at a position, propagating to background lines. */
        PAINT,
        /** Drop the word overlay, background lines included. */
        CLEAR,
        CLEAR_FADE,
        /** Fade to the inactive alpha, word overlay untouched. */
        FADE,
        /** Restore non-karaoke colors while word sync is being turned off. */
        RESET_ACTIVE,
        RESET_INACTIVE
    }

    /** One queued effect on one line. */
    record Action(Kind kind, int line, long posMs, boolean allSung) {}

    static boolean stillLit(Lines lines, int line, long pos) {
        if (line < 0 || line >= lines.size() || lines.wordsEmpty(line)) {
            return false;
        }
        return pos < lines.lastEnd(line) && pos >= lines.firstStart(line);
    }

    private int lastLine = -1;
    private int pendingLine = -1;
    private boolean wasEnabled = true;

    /**
     * Runs one tick, appending actions in the order the view has to run them. The caller
     * owns {@code out} and clears it after execution.
     */
    @SuppressWarnings("ConstantValue")
    void advance(long pos, int active, boolean enabled, Lines lines, List<Action> out) {
        if (enabled != wasEnabled) {
            if (!enabled) {
                final int count = lines.size();
                for (int i = 0; i < count; i++) {
                    out.add(new Action(i == active
                            ? Kind.RESET_ACTIVE : Kind.RESET_INACTIVE, i, 0, false));
                }
                lastLine = -1;
                pendingLine = -1;
                wasEnabled = enabled;
                return;
            }
            wasEnabled = enabled;
        }
        if (!enabled) {
            return;
        }

        final int count = lines.size();
        if (active < 0 || active >= count) {
            if (lastLine >= 0) {
                out.add(new Action(Kind.CLEAR, lastLine, 0, false));
            }
            if (pendingLine >= 0 && pendingLine < count) {
                out.add(new Action(Kind.CLEAR_FADE, pendingLine, 0, false));
            }
            lastLine = -1;
            pendingLine = -1;
            return;
        }

        if (pendingLine == active) {
            pendingLine = -1;
        }
        if (pendingLine >= 0 && pendingLine < count) {
            if (lines.wordsEmpty(pendingLine)) {
                out.add(new Action(Kind.FADE, pendingLine, 0, false));
                pendingLine = -1;
            } else if (pos >= lines.lastEnd(pendingLine)
                    || pos < lines.firstStart(pendingLine)) {
                out.add(new Action(Kind.CLEAR_FADE, pendingLine, 0, false));
                pendingLine = -1;
            } else {
                out.add(new Action(Kind.PAINT, pendingLine, pos, false));
            }
        }

        if (lines.wordsEmpty(active)) {
            if (lastLine >= 0 && lastLine != active) {
                handoff(lastLine, pos, lines, out);
            }
            lastLine = active;
            out.add(new Action(Kind.PAINT, active, 0, true));
            return;
        }

        if (active != lastLine) {
            if (lastLine >= 0) {
                handoff(lastLine, pos, lines, out);
            }
            lastLine = active;
        }
        out.add(new Action(Kind.PAINT, active, pos, false));
    }

    /**
     * Retires the line the highlight left: staged on the pending slot while its words
     * are still sung, cleared at once otherwise. Staging evicts the occupant first,
     * because nothing else ever clears a line that reached the slot.
     */
    private void handoff(int old, long pos, Lines lines, List<Action> out) {
        if (stillLit(lines, old, pos)) {
            if (pendingLine >= 0 && pendingLine != old && pendingLine < lines.size()) {
                out.add(new Action(Kind.CLEAR_FADE, pendingLine, 0, false));
            }
            pendingLine = old;
        } else {
            out.add(new Action(Kind.CLEAR, old, 0, false));
        }
    }

    /** Drops the pending slot; its line stays cleared by its own path. */
    void resetPending() {
        pendingLine = -1;
    }

    /** Drops both slots, for the content being rebuilt or switched. */
    void resetContent() {
        lastLine = -1;
        pendingLine = -1;
    }

    /** Realigns the toggle memory, for the reset path with no tick to notice on. */
    void syncEnabled(boolean enabled) {
        wasEnabled = enabled;
    }
}
