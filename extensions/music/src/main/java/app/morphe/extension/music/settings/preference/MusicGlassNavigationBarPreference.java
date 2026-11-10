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
 * Configures either the glass navigation bar (height, width, button spacing, opacity, border glow)
 * or the glass miniplayer (corner radius, artwork padding, border glow, opacity),
 * each with its own independent live preview dialog.
 */
@SuppressWarnings({"unused", "deprecation"})
public class MusicGlassNavigationBarPreference extends GlassNavigationBarSizePreference {

    private static final String MINIPLAYER_KEY = "morphe_music_miniplayer_glass_corner_radius";

    private static final IntegerSetting[] NAV_BAR_SETTINGS = {
            Settings.NAVIGATION_BAR_GLASS_HEIGHT,
            Settings.NAVIGATION_BAR_GLASS_WIDTH,
            Settings.NAVIGATION_BAR_GLASS_BUTTON_SPACING,
            Settings.NAVIGATION_BAR_GLASS_OPACITY,
            Settings.NAVIGATION_BAR_GLASS_RIM
    };

    private static final String[] NAV_BAR_TITLES = {
            "morphe_music_navigation_bar_glass_size_height",
            "morphe_music_navigation_bar_glass_size_width",
            "morphe_music_navigation_bar_glass_size_button_spacing",
            "morphe_music_navigation_bar_glass_opacity_title",
            "morphe_music_navigation_bar_glass_rim_title"
    };

    private static final IntegerSetting[] MINIPLAYER_SETTINGS = {
            Settings.MINIPLAYER_GLASS_CORNER_RADIUS,
            Settings.MINIPLAYER_GLASS_ARTWORK_PADDING,
            Settings.MINIPLAYER_GLASS_RIM,
            Settings.MINIPLAYER_GLASS_OPACITY
    };

    private static final String[] MINIPLAYER_TITLES = {
            "morphe_music_miniplayer_glass_corner_radius_label",
            "morphe_music_miniplayer_glass_artwork_padding_label",
            "morphe_music_miniplayer_glass_rim_title",
            "morphe_music_miniplayer_glass_opacity_title"
    };

    public MusicGlassNavigationBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected boolean isMiniplayerMode() {
        return MINIPLAYER_KEY.equals(getKey());
    }

    @Override
    protected IntegerSetting[] getSizeSettings() {
        return isMiniplayerMode() ? MINIPLAYER_SETTINGS : NAV_BAR_SETTINGS;
    }

    @Override
    protected String[] getSizeTitleKeys() {
        return isMiniplayerMode() ? MINIPLAYER_TITLES : NAV_BAR_TITLES;
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
    protected int getPreviewTintColor() {
        final boolean useAlbumTint = isMiniplayerMode()
                ? Settings.MINIPLAYER_GLASS_ALBUM_TINT.get()
                : Settings.NAVIGATION_BAR_GLASS_ALBUM_TINT.get();
        if (useAlbumTint) {
            int resolved = MusicGlassNavigationBar.getResolvedAlbumColor();
            return resolved != 0 ? resolved : 0xFF7B3B5E;
        }
        return 0;
    }

    @Override
    protected boolean isLightGlass() {
        return isMiniplayerMode()
                ? Settings.MINIPLAYER_GLASS_LIGHT.get()
                : Settings.NAVIGATION_BAR_GLASS_LIGHT.get();
    }

    @Override
    protected boolean isSecondaryElementHidden() {
        return isMiniplayerMode()
                ? Settings.MINIPLAYER_GLASS_HIDE_PROGRESS_BAR.get()
                : Settings.NAVIGATION_BAR_GLASS_HIDE_INDICATOR.get();
    }

    @Override
    protected boolean isCircularArtwork() {
        return isMiniplayerMode() && Settings.MINIPLAYER_GLASS_SPINNING_ARTWORK.get();
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
