/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import static app.morphe.extension.shared.StringRef.str;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;

/**
 * Button at the top of the player that opens the DeArrow titles and thumbnails of the video,
 * to vote on them or submit new ones.
 */
@SuppressWarnings("unused")
public final class DeArrowVoteButton {

    private static final String BUTTON_ID = "morphe_dearrow_vote_button";

    static {
        if (Settings.DEARROW_VOTE_BUTTON.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    private DeArrowVoteButton() {
    }

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            new LegacyPlayerControlButton(
                    controlsView,
                    BUTTON_ID,
                    null,
                    "morphe_yt_dearrow",
                    Settings.DEARROW_VOTE_BUTTON,
                    DeArrowVoteButton::showDialog,
                    null
            );
            Utils.getChildViewByResourceName(controlsView, BUTTON_ID)
                    .setContentDescription(str("morphe_dearrow_vote_title"));
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    private static void showDialog(View view) {
        try {
            DeArrowVoteDialog.show(view.getContext());
        } catch (Exception ex) {
            Logger.printException(() -> "showDialog failure", ex);
        }
    }
}
