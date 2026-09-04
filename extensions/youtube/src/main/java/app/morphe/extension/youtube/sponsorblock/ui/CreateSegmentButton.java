/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.sponsorblock.ui;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.sponsorblock.SegmentPlaybackController;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;
import app.morphe.extension.youtube.whitelist.ChannelWhitelistDialog;
import app.morphe.extension.youtube.whitelist.WhitelistType;

@SuppressWarnings("unused")
public class CreateSegmentButton {

    static {
        if (Settings.SB_ENABLED.get() && Settings.SB_CREATE_NEW_SEGMENT.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    /**
     * injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_sb_create_segment_button",
                    null,
                    "morphe_sb_logo",
                    () -> CreateSegmentButton.isButtonEnabled()
                            ? LegacyPlayerControlButton.ButtonVisibility.ENABLED
                            : LegacyPlayerControlButton.ButtonVisibility.DISABLED,
                    v -> SponsorBlockViewController.toggleNewSegmentLayoutVisibility(),
                    v -> {
                        ChannelWhitelistDialog.show(v.getContext(), WhitelistType.SPONSOR_BLOCK);
                        return true;
                    }
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    private static boolean isButtonEnabled() {
        return Settings.SB_ENABLED.get() && Settings.SB_CREATE_NEW_SEGMENT.get()
                && !SegmentPlaybackController.isAdProgressTextVisible();
    }
}
