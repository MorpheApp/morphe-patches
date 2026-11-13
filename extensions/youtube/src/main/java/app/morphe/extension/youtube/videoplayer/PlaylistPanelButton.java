/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4662
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;

import java.util.HashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.queue.fullscreen.QueueFullscreenRenderer;
import app.morphe.extension.youtube.patches.CommandRouterPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.queue.QueueFullscreenActions;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton.ButtonVisibility;

/**
 * Opens the playlist panel with the same command as the playlist quick action under the seekbar.
 */
@SuppressWarnings("unused")
public class PlaylistPanelButton {

    private static final byte[] SHOW_PLAYLIST_PANEL_COMMAND = QueueFullscreenRenderer.Endpoint.newBuilder()
            .setShowEngagementPanel(QueueFullscreenRenderer.ShowPanel.newBuilder()
                    .setPanelId(QueueFullscreenActions.PLAYLIST_PANEL_ID))
            .build()
            .toByteArray();

    static {
        if (Settings.PLAYLIST_PANEL_BUTTON.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_playlist_panel_button",
                    null,
                    null,
                    () -> Settings.PLAYLIST_PANEL_BUTTON.get() && !VideoInformation.getPlaylistId().isEmpty()
                            ? ButtonVisibility.ENABLED
                            : ButtonVisibility.DISABLED,
                    view -> openPlaylistPanel(),
                    null
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    private static void openPlaylistPanel() {
        try {
            if (!CommandRouterPatch.executeCommand(SHOW_PLAYLIST_PANEL_COMMAND, new HashMap<>())) {
                Logger.printDebug(() -> "No command router of a shown activity");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "openPlaylistPanel failure", ex);
        }
    }
}
