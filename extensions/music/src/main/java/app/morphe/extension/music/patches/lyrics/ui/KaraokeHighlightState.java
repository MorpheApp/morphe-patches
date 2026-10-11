/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import java.util.List;

/**
 * The handoff state of karaoke (word sync) highlighting: which line still wears sung
 * word colors after the highlight moved on ({@code last}), which one is waiting to
 * retire on a later tick ({@code pending}), and whether word sync is on.
 *
 * <p>The machine is pure: {@link #advance} reads the inputs it is given, updates the
 * three fields, and appends the actions the view has to perform, in the exact order
 * the inline logic it replaces executed them. It never touches a view, an animation
 * or an Android type, so the whole handoff rule lives in this one file and the view
 * only executes what comes out of it.
 */
final class KaraokeHighlightState {

    /**
     * The word timings of the built lines, read without holding the views they belong
     * to. {@link #size()} is the line count both backing lists agree on; every line
     * index passed to the other methods is smaller than it.
     */
    interface Lines {
        int size();

        boolean wordsEmpty(int line);

        long firstStart(int line);

        long lastEnd(int line);
    }

    enum Kind {
        /** Paints the word colors at a position, propagating to background lines. */
        PAINT,
        /** Drops the word overlay of a line, background lines included. */
        CLEAR,
        CLEAR_FADE,
        /** Fades a line to the inactive alpha, word overlay untouched. */
        FADE,
        /** Restores a line to its non-karaoke colors while word sync is being turned off. */
        RESET_ACTIVE,
        RESET_INACTIVE
    }

    /** One queued effect on one line; the view applies the list synchronously. */
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
     * Runs one tick. {@code active} is the highlighted line, {@code enabled} the word
     * sync setting. Actions are appended to {@code out} in the order the view has to
     * run them; the caller owns the list and clears it after execution.
     */
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
     * Retires the line the highlight left: staged on the single pending slot while
     * its words are still sung, cleared at once otherwise. Nothing else ever clears
     * a line that reached the slot, so staging evicts the occupant first.
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

    /** Drops the pending slot; the line it held stays cleared by its own path. */
    void resetPending() {
        pendingLine = -1;
    }

    /** Drops both slots, for the content under them being rebuilt or switched. */
    void resetContent() {
        lastLine = -1;
        pendingLine = -1;
    }

    /**
     * Realigns the toggle memory with the setting, for the code path that resets
     * karaoke state without a tick to notice the change on.
     */
    void syncEnabled(boolean enabled) {
        wasEnabled = enabled;
    }
}
