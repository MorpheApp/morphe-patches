/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4664
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;

@SuppressWarnings("unused")
public final class FullscreenPlaylistPanelPatch {

    private static final String PLAYLIST_PANEL = "engagement-panel-playlist";

    /**
     * Injection point: start of YouTube's rule whether a panel exits fullscreen.
     *
     * @return True to keep fullscreen for the panel that opens now.
     */
    public static boolean keepFullscreen() {
        if (!Settings.FULLSCREEN_PLAYLIST_PANEL.get()) {
            return false;
        }

        final boolean keep = PLAYLIST_PANEL.equals(EngagementPanel.getLastOpenedPanelId());
        if (keep) {
            Logger.printDebug(() -> "Playlist panel opened in fullscreen: keeping fullscreen");
        }
        return keep;
    }

    /**
     * Injection point: YouTube's server config checks for the panel overlay over the fullscreen
     * player. YouTube still requires fullscreen and no ad playing for the overlay.
     */
    public static boolean useFullscreenOverlay(boolean original) {
        return original || Settings.FULLSCREEN_PLAYLIST_PANEL.get();
    }
}
