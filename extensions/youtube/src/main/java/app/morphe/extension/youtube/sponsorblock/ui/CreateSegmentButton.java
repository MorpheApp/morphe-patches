package app.morphe.extension.youtube.sponsorblock.ui;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.sponsorblock.SegmentPlaybackController;
import app.morphe.extension.youtube.videoplayer.PlayerControlButton;

@SuppressWarnings("unused")
public class CreateSegmentButton {

    private static final int DRAWABLE_SB_LOGO = Utils.getResourceIdentifierOrThrow(
            ResourceType.DRAWABLE, Utils.appIsUsingBoldIcons()
                    ? "revanced_sb_logo_bold"
                    : "revanced_sb_logo"
    );

    @Nullable
    private static PlayerControlButton instance;

    public static void hideControls() {
        if (instance != null) instance.hide();
    }

    /**
     * injection point.
     */
    public static void initialize(View controlsView) {
        try {
            instance = new PlayerControlButton(
                    controlsView,
                    "revanced_sb_create_segment_button",
                    null,
                    CreateSegmentButton::isButtonEnabled,
                    v -> SponsorBlockViewController.toggleNewSegmentLayoutVisibility(),
                    null
            );

            // FIXME: Bold YT player icons are currently forced off.
            //        Enable this logic when the new player icons are not forced off.
            ImageView icon = Utils.getChildViewByResourceName(controlsView,
                    "revanced_sb_create_segment_button");
            if (false) {
                icon.setImageResource(DRAWABLE_SB_LOGO);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * injection point.
     */
    public static void setVisibilityNegatedImmediate() {
        if (instance != null) instance.setVisibilityNegatedImmediate();
    }

    /**
     * injection point.
     */
    public static void setVisibilityImmediate(boolean visible) {
        if (instance != null) instance.setVisibilityImmediate(visible);
    }

    /**
     * injection point.
     */
    public static void setVisibility(boolean visible, boolean animated) {
        if (instance != null) instance.setVisibility(visible, animated);
    }

    private static boolean isButtonEnabled() {
        return Settings.SB_ENABLED.get() && Settings.SB_CREATE_NEW_SEGMENT.get()
                && !SegmentPlaybackController.isAdProgressTextVisible();
    }
}
