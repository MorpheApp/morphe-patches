/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4424
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.preference.GlassNavigationBarSizePreference;
import app.morphe.extension.youtube.patches.NavigationBarPatch;
import app.morphe.extension.youtube.patches.YouTubeGlassNavigationBar;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Sets the height, the width and the button spacing of the glass navigation bar,
 * with a preview of the bar that follows the sliders.
 */
@SuppressWarnings("unused")
public class YouTubeGlassNavigationBarPreference extends GlassNavigationBarSizePreference {

    private static final IntegerSetting[] SETTINGS = {
            Settings.NAVIGATION_BAR_GLASS_HEIGHT,
            Settings.NAVIGATION_BAR_GLASS_WIDTH,
            Settings.NAVIGATION_BAR_GLASS_BUTTON_SPACING
    };

    private static final String[] TITLES = {
            "morphe_navigation_bar_glass_size_height",
            "morphe_navigation_bar_glass_size_width",
            "morphe_navigation_bar_glass_size_button_spacing"
    };

    public YouTubeGlassNavigationBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected IntegerSetting[] getSettings() {
        return SETTINGS;
    }

    @Override
    protected String[] getTitleKeys() {
        return TITLES;
    }

    @Override
    protected String getButtonsShownKey() {
        return "morphe_navigation_bar_glass_size_buttons_shown";
    }

    @Override
    protected int getHorizontalMargin() {
        return YouTubeGlassNavigationBar.MARGIN_HORIZONTAL;
    }

    @Override
    protected PreviewButtons getButtons() {
        // The icons, the labels and the selected button of the last glass navigation bar,
        // as the bar shows them, such as without labels.
        PreviewButtons buttons = PreviewButtons.fromLastBar(YouTubeGlassNavigationBar.BUTTON_ICON, YouTubeGlassNavigationBar.BUTTON_LABEL);
        if (!buttons.isEmpty()) return buttons;

        // The buttons of the last navigation bar, or all the buttons seen if none was shown yet.
        Map<String, String> discoveredButtons;
        synchronized (NavigationBarPatch.discoveredNavigationButtons) {
            discoveredButtons = new LinkedHashMap<>(NavigationBarPatch.discoveredNavigationButtons);
        }
        for (String id : NavigationBarPatch.navigationButtons.isEmpty()
                ? discoveredButtons.keySet() : NavigationBarPatch.navigationButtons) {
            String label = discoveredButtons.get(id);
            if (label != null) buttons.add(label);
        }
        buttons.setShowLabels(!Settings.HIDE_NAVIGATION_BUTTON_LABELS.get());
        return buttons;
    }
}
