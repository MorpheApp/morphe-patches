/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OverlayVisibilityStateTest {

    @Test
    public void effectiveVisibilityNeedsBothDesiredAndObservedPanelOpen() {
        OverlayVisibilityState state = new OverlayVisibilityState();
        assertFalse(state.isEffectivelyVisible());

        state.refresh(true, false, 1_000L);
        assertFalse(state.isEffectivelyVisible());
        state.setDesiredVisible(true);
        assertTrue(state.isEffectivelyVisible());

        state.refresh(false, false, 2_000L);
        assertFalse(state.isEffectivelyVisible());
    }

    @Test
    public void cacheExpiresAfterTtlAndCanBeInvalidated() {
        OverlayVisibilityState state = new OverlayVisibilityState();
        state.refresh(true, false, 1_000L);
        assertFalse(state.shouldRefresh(1_200L));
        assertTrue(state.shouldRefresh(1_301L));

        state.invalidateCache();
        assertTrue(state.shouldRefresh(1_301L));
    }

    @Test
    public void otherPanelForegroundIsCachedUntilRefreshed() {
        OverlayVisibilityState state = new OverlayVisibilityState();
        state.refresh(true, true, 1_000L);
        assertTrue(state.isOtherPanelForeground());
        state.refresh(true, false, 1_200L);
        assertFalse(state.isOtherPanelForeground());
    }
}
