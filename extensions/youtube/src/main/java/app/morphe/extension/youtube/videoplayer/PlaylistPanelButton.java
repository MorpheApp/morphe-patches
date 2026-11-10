/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4662
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;

import com.google.protobuf.CodedOutputStream;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.CommandRouterPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton.ButtonVisibility;

/**
 * Opens the playlist panel from a button at the top of the player, with the same command as the
 * playlist quick action under the seekbar, which pushes the seekbar up.
 */
@SuppressWarnings("unused")
public class PlaylistPanelButton {

    /**
     * Protobuf field numbers of showEngagementPanelEndpoint and of its panel id.
     */
    private static final int SHOW_ENGAGEMENT_PANEL_ENDPOINT_FIELD = 138681778;
    private static final int SHOW_ENGAGEMENT_PANEL_ENDPOINT_PANEL_ID_FIELD = 1;
    private static final String PLAYLIST_PANEL = "engagement-panel-playlist";

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
            ByteArrayOutputStream endpoint = new ByteArrayOutputStream();
            CodedOutputStream endpointOutput = CodedOutputStream.newInstance(endpoint);
            endpointOutput.writeString(SHOW_ENGAGEMENT_PANEL_ENDPOINT_PANEL_ID_FIELD, PLAYLIST_PANEL);
            endpointOutput.flush();

            ByteArrayOutputStream command = new ByteArrayOutputStream();
            CodedOutputStream commandOutput = CodedOutputStream.newInstance(command);
            commandOutput.writeByteArray(SHOW_ENGAGEMENT_PANEL_ENDPOINT_FIELD, endpoint.toByteArray());
            commandOutput.flush();

            if (!CommandRouterPatch.executeCommand(command.toByteArray(), new HashMap<>())) {
                Logger.printDebug(() -> "Playlist button: no shown activity to open the panel in");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "openPlaylistPanel failure", ex);
        }
    }
}
