/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import android.graphics.drawable.StateListDrawable;
import android.util.StateSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.VideoState;

/** Direct targets of Morphe's playback and navigation hooks. */
@SuppressWarnings("unused")
public final class SeriesTrackerPatch {
    /**
     * Changing the setting requires an app restart. When disabled, every hook returns
     * immediately and the app behaves as if the patch was not included.
     */
    static final boolean ENABLED = Settings.SERIES_TRACKER.get();

    /**
     * Off while the navigation bar is hidden.
     */
    static final boolean NAVIGATION_BUTTON = ENABLED
            && Settings.SERIES_TRACKER_NAVIGATION_BUTTON.get()
            && Settings.SERIES_TRACKER_NAVIGATION_BUTTON.isAvailable();

    static {
        if (ENABLED) {
            VideoState.getOnChange().addObserver(state -> {
                videoStateChanged(state);
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    /**
     * Injection point.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController controller) {
        if (!ENABLED) return;
        try {
            PlaybackBridge.attach(
                    controller instanceof PlaybackBridge.Source
                            ? (PlaybackBridge.Source) controller
                            : null);
            TrackerRuntime.initialize();
            TrackerRuntime.newVideo();
        } catch (Exception ex) {
            Logger.printException(() -> "newVideoStarted failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void videoTimeChanged(long time) {
        if (!ENABLED) return;
        try {
            TrackerRuntime.sample();
        } catch (Exception ex) {
            Logger.printException(() -> "videoTimeChanged failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @return If the app navigation to another video must be skipped.
     */
    public static boolean navigationIntent(Enum<?> intent) {
        if (!ENABLED) return false;
        try {
            return SeriesPlayback.navigate(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "navigationIntent failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     */
    public static void navigationTabCreated(NavigationBar.NavigationButton button, View tabView) {
        if (!NAVIGATION_BUTTON || button != NavigationBar.NavigationButton.SERIES) return;
        try {
            // Series player button icon, filled when selected like the other buttons.
            final String suffix = Utils.appIsUsingBoldIcons() ? "_bold" : "";
            StateListDrawable drawable = new StateListDrawable();
            drawable.addState(new int[]{android.R.attr.state_selected},
                    ResourceUtils.getDrawable("morphe_series_tracker_button_fill" + suffix));
            drawable.addState(StateSet.WILD_CARD, ResourceUtils.getDrawable("morphe_series_tracker_button" + suffix));
            ImageView icon = Utils.getChildViewByResourceName(tabView, "image");
            TextView label = Utils.getChildViewByResourceName(tabView, "text");
            icon.setImageDrawable(drawable);
            icon.setImageTintList(label.getTextColors());
        } catch (Exception ex) {
            Logger.printException(() -> "navigationTabCreated failure", ex);
        }
    }

    private static void videoStateChanged(Enum<?> state) {
        try {
            TrackerRuntime.state(state.name());
        } catch (Exception ex) {
            Logger.printException(() -> "videoStateChanged failure", ex);
        }
    }

    private SeriesTrackerPatch() {}
}
