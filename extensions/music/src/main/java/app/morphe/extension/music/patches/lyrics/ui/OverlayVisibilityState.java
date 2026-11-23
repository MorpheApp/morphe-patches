/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

/**
 * Desired/observed visibility of the lyrics overlay. Pure holder: the panel reads the environment
 * and applies the resulting visibility to its views; this class only holds the short-lived cache
 * and the effective-visibility decision.
 */
final class OverlayVisibilityState {

    private static final long OVERLAY_CACHE_TTL_MS = 300L;

    private boolean desiredVisible;
    private boolean lyricsPanelOpen;
    private boolean otherPanelForeground;
    private long cacheUptimeMs;

    void setDesiredVisible(boolean visible) {
        desiredVisible = visible;
    }

    void invalidateCache() {
        cacheUptimeMs = 0L;
    }

    boolean shouldRefresh(long nowUptimeMs) {
        return nowUptimeMs - cacheUptimeMs > OVERLAY_CACHE_TTL_MS;
    }

    void refresh(boolean lyricsPanelOpen, boolean otherPanelForeground, long nowUptimeMs) {
        this.lyricsPanelOpen = lyricsPanelOpen;
        this.otherPanelForeground = otherPanelForeground;
        this.cacheUptimeMs = nowUptimeMs;
    }

    boolean isOtherPanelForeground() {
        return otherPanelForeground;
    }

    boolean isEffectivelyVisible() {
        return desiredVisible && lyricsPanelOpen;
    }
}
