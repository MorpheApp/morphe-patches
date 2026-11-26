/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OffsetModeStateTest {

    @Test
    public void offsetGestureOnlyFinishesOnce() {
        OffsetModeState state = new OffsetModeState();
        state.beginOffsetGesture(120f, -500);
        assertTrue(state.isOffsetAdjusting());
        assertEquals(120f, state.offsetSwipeStartX(), 0f);
        assertEquals(-500, state.offsetSwipeStartMs());

        assertTrue(state.finishOffsetAdjustment());
        assertFalse(state.isOffsetAdjusting());
        assertFalse(state.finishOffsetAdjustment());
    }

    @Test
    public void buttonRowHideIsIdempotent() {
        OffsetModeState state = new OffsetModeState();
        assertTrue(state.hideButtonRowForOffset());
        assertFalse(state.hideButtonRowForOffset());
        assertTrue(state.isButtonRowHiddenForOffset());
        assertTrue(state.restoreButtonRowAfterOffset());
        assertFalse(state.restoreButtonRowAfterOffset());
        assertFalse(state.isButtonRowHiddenForOffset());
    }

    @Test
    public void recordingSuppressesRulerAutoHideUntilStopped() {
        OffsetModeState state = new OffsetModeState();
        assertTrue(state.shouldAutoHideRuler(false));
        assertFalse(state.shouldAutoHideRuler(true));

        state.startRecording("video-id", 12_345L);
        assertTrue(state.isRecording());
        assertFalse(state.shouldAutoHideRuler(false));

        OffsetModeState.Recording recording = state.stopRecording();
        assertEquals("video-id", recording.videoId());
        assertEquals(12_345L, recording.startMs());
        assertFalse(state.isRecording());
        assertNull(state.stopRecording());
        assertTrue(state.shouldAutoHideRuler(false));
    }
}
