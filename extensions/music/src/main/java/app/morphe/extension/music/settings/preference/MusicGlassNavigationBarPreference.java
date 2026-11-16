/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.music.patches.MusicGlassNavigationBar;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.preference.GlassNavigationBarSizePreference;

/**
 * Sets the height, the width, the button spacing, the opacity and the rim brightness of the glass navigation bar,
 * with a preview of the bar that follows the sliders.
 */
@SuppressWarnings("unused")
public class MusicGlassNavigationBarPreference extends GlassNavigationBarSizePreference {

    private static final IntegerSetting[] SETTINGS = {
            Settings.NAVIGATION_BAR_GLASS_HEIGHT,
            Settings.NAVIGATION_BAR_GLASS_WIDTH,
            Settings.NAVIGATION_BAR_GLASS_BUTTON_SPACING,
            Settings.NAVIGATION_BAR_GLASS_OPACITY,
            Settings.NAVIGATION_BAR_GLASS_RIM
    };

    private static final String[] TITLES = {
            "morphe_music_navigation_bar_glass_size_height",
            "morphe_music_navigation_bar_glass_size_width",
            "morphe_music_navigation_bar_glass_size_button_spacing",
            "morphe_music_navigation_bar_glass_opacity_title",
            "morphe_music_navigation_bar_glass_rim_title"
    };

    private static final int INDEX_OPACITY = 3;
    private static final int INDEX_RIM = 4;

    public MusicGlassNavigationBarPreference(Context context, AttributeSet attrs) {
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
        return "morphe_music_navigation_bar_glass_size_buttons_shown";
    }

    @Override
    protected int getHorizontalMargin() {
        return MusicGlassNavigationBar.MARGIN_HORIZONTAL;
    }

    @Override
    protected float getOpacityScale(int[] values) {
        return values[INDEX_OPACITY] / 50f;
    }

    @Override
    protected float getRimScale(int[] values) {
        return values[INDEX_RIM] / 50f;
    }

    @Override
    protected boolean isLightTint() {
        return Settings.NAVIGATION_BAR_GLASS_LIGHT.get();
    }

    @Override
    protected int getTintColor() {
        return Settings.NAVIGATION_BAR_GLASS_ALBUM_TINT.get() ? MusicGlassNavigationBar.getAlbumColor() : 0;
    }

    @Override
    protected boolean isIndicatorShown() {
        return !Settings.NAVIGATION_BAR_GLASS_HIDE_INDICATOR.get();
    }

    @Override
    protected PreviewButtons getButtons() {
        // The icons, the labels and the selected button of the last glass navigation bar.
        PreviewButtons buttons = PreviewButtons.fromLastBar(
                MusicGlassNavigationBar.BUTTON_ICON, MusicGlassNavigationBar.BUTTON_LABEL);
        if (!buttons.isEmpty()) return buttons;

        // The usual buttons, if no navigation bar with the glass was shown yet.
        buttons.add(str("morphe_music_navigation_bar_glass_preview_home"));
        buttons.add(str("morphe_music_navigation_bar_glass_preview_samples"));
        buttons.add(str("morphe_music_navigation_bar_glass_preview_explore"));
        buttons.add(str("morphe_music_navigation_bar_glass_preview_library"));
        buttons.setShowLabels(!Settings.HIDE_NAVIGATION_BAR_LABEL.get());
        return buttons;
    }
}
