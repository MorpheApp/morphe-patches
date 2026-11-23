/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.Nullable;

/**
 * Offset-ruler and SponsorBlock-recording modes of the lyrics panel. Pure holder: ruler/handler
 * side effects stay in {@link LyricsPanelView}; only mode flags and their accumulated values move here.
 */
final class OffsetModeState {

    private boolean offsetAdjusting;
    private boolean buttonRowHiddenForOffset;
    private float offsetSwipeStartX;
    private int offsetSwipeStartMs;

    private boolean sbRecording;
    private long sbRecordStartMs;
    @Nullable
    private String sbRecordVideoId;

    void beginOffsetGesture(float startX, int startOffsetMs) {
        offsetAdjusting = true;
        offsetSwipeStartX = startX;
        offsetSwipeStartMs = startOffsetMs;
    }

    boolean isOffsetAdjusting() {
        return offsetAdjusting;
    }

    float offsetSwipeStartX() {
        return offsetSwipeStartX;
    }

    int offsetSwipeStartMs() {
        return offsetSwipeStartMs;
    }

    boolean finishOffsetAdjustment() {
        if (!offsetAdjusting) {
            return false;
        }
        offsetAdjusting = false;
        return true;
    }

    void cancelOffsetAdjustment() {
        offsetAdjusting = false;
    }

    /** Returns true when this call actually hid the button row. */
    boolean hideButtonRowForOffset() {
        if (buttonRowHiddenForOffset) {
            return false;
        }
        buttonRowHiddenForOffset = true;
        return true;
    }

    /** Returns true when this call actually restored the button row. */
    boolean restoreButtonRowAfterOffset() {
        if (!buttonRowHiddenForOffset) {
            return false;
        }
        buttonRowHiddenForOffset = false;
        return true;
    }

    boolean isButtonRowHiddenForOffset() {
        return buttonRowHiddenForOffset;
    }

    boolean shouldAutoHideRuler(boolean hasTemporaryOffset) {
        return !sbRecording && !hasTemporaryOffset;
    }

    void startRecording(@Nullable String videoId, long startMs) {
        sbRecording = true;
        sbRecordVideoId = videoId;
        sbRecordStartMs = startMs;
    }

    boolean isRecording() {
        return sbRecording;
    }

    long recordingStartMs() {
        return sbRecordStartMs;
    }

    /** Ends a recording and returns its video id and start time before clearing them. */
    @Nullable
    Recording stopRecording() {
        if (!sbRecording) {
            return null;
        }
        final Recording result = new Recording(sbRecordVideoId, sbRecordStartMs);
        sbRecording = false;
        sbRecordVideoId = null;
        return result;
    }

    record Recording(@Nullable String videoId, long startMs) {
    }
}
