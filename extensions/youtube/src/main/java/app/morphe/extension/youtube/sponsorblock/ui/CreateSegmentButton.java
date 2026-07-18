package app.morphe.extension.youtube.sponsorblock.ui;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.shared.sponsorblock.SegmentPlaybackController;
import app.morphe.extension.youtube.sponsorblock.SponsorBlockUtils;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;

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
                        SponsorBlockUtils.showChannelWhitelistDialog(v.getContext());
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
