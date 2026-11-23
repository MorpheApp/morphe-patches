/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

/**
 * Follow/browsing state of the lyrics panel. Pure holder: follow-resume timers and view scrolling
 * stay in {@link LyricsPanelView}, only the mutually exclusive flags and their timestamps live here.
 */
final class FollowState {

    private boolean browsing;
    private boolean draggingScroll;
    private boolean programmaticScrolling;
    private long userScrollUntilUptimeMs;
    private int lastScrollTarget = -1;

    boolean isBrowsing() {
        return browsing;
    }

    void setBrowsing(boolean browsing) {
        this.browsing = browsing;
    }

    void enterBrowsing() {
        browsing = true;
        lastScrollTarget = -1;
    }

    void exitBrowsing() {
        browsing = false;
        userScrollUntilUptimeMs = 0L;
        draggingScroll = false;
        lastScrollTarget = -1;
    }

    boolean isDraggingScroll() {
        return draggingScroll;
    }

    void setDraggingScroll(boolean draggingScroll) {
        this.draggingScroll = draggingScroll;
    }

    boolean isProgrammaticScrolling() {
        return programmaticScrolling;
    }

    void setProgrammaticScrolling(boolean programmaticScrolling) {
        this.programmaticScrolling = programmaticScrolling;
    }

    /** Called when the user drags or flings the scroll view; keeps following suppressed briefly. */
    void noteUserScroll(long untilUptimeMs) {
        userScrollUntilUptimeMs = untilUptimeMs;
        lastScrollTarget = -1;
        if (!browsing) {
            browsing = true;
        }
    }

    long userScrollUntilUptimeMs() {
        return userScrollUntilUptimeMs;
    }

    void setUserScrollUntilUptimeMs(long userScrollUntilUptimeMs) {
        this.userScrollUntilUptimeMs = userScrollUntilUptimeMs;
    }

    boolean hasUserScrollGrace(long nowUptimeMs) {
        return nowUptimeMs < userScrollUntilUptimeMs;
    }

    boolean isUserScrolling(long nowUptimeMs) {
        return browsing || draggingScroll || hasUserScrollGrace(nowUptimeMs);
    }

    int lastScrollTarget() {
        return lastScrollTarget;
    }

    void setLastScrollTarget(int lastScrollTarget) {
        this.lastScrollTarget = lastScrollTarget;
    }
}
