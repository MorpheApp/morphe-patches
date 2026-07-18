package app.morphe.extension.youtube.sponsorblock.ui;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.sponsorblock.SegmentPlaybackController;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.sponsorblock.SponsorBlockUtils;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;

@SuppressWarnings("unused")
public class VotingButton {

    static {
        if (Settings.SB_ENABLED.get() && Settings.SB_VOTING_BUTTON.get()) {
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
                    "morphe_sb_voting_button",
                    null,
                    "morphe_sb_voting",
                    () -> VotingButton.isButtonEnabled()
                            ? LegacyPlayerControlButton.ButtonVisibility.ENABLED
                            : LegacyPlayerControlButton.ButtonVisibility.DISABLED,
                    v -> SponsorBlockUtils.onVotingClicked(v.getContext()),
                    null
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    private static boolean isButtonEnabled() {
        return Settings.SB_ENABLED.get() && Settings.SB_VOTING_BUTTON.get()
                && SegmentPlaybackController.videoHasSegments()
                && !SegmentPlaybackController.isAdProgressTextVisible();
    }
}
