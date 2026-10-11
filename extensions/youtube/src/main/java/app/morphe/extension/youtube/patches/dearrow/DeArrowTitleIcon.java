/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3510
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.Spanned;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ui.IconSpan;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Shows the DeArrow icon before the titles submitted to DeArrow,
 * so they can be told apart from the titles of the uploader.
 * <p>
 * The icon is the DeArrow icon of the settings, tinted with the color of the title
 * so it matches the theme.
 */
public final class DeArrowTitleIcon {

    private static final boolean SHOW_ICON = DeArrowPatch.DeArrowTitlesAvailability.usingDeArrowTitlesAnywhere()
            && Settings.DEARROW_TITLES_ICON.get();

    /**
     * Bullseye character that the icon is drawn over. The text of the title is not used to find the icon,
     * and the bullseye looks similar to the icon if the icon span is removed, such as when the title is copied.
     */
    private static final String ICON_PLACEHOLDER = "◎";

    /**
     * Separates the icon from the title.
     */
    private static final String ICON_SEPARATOR = " ";

    /**
     * Icon of the DeArrow settings, or null if not loaded.
     * The icon is only drawn on the main thread, so it can be shared by all titles.
     */
    private static final Drawable ICON = ResourceUtils.getDrawable("morphe_settings_screen_02_dearrow_bold");

    /**
     * Size of the icon relative to the text size.
     */
    private static final float ICON_SIZE = 0.85f;

    /**
     * Opacity of the icon relative to the title, so the icon is visible but less prominent
     * than the title. The transparent icon is blended with any background, such as the
     * background of the app or of a video.
     */
    private static final float ICON_OPACITY = 0.5f;

    private DeArrowTitleIcon() {
    }

    /**
     * @return If the icon is shown before the title.
     */
    public static boolean isShown(@Nullable String title) {
        return SHOW_ICON && title != null && DeArrowBrandingRequest.isDeArrowTitle(title.trim());
    }

    /**
     * @return If the text starts with the icon added by this class.
     */
    public static boolean hasIcon(@Nullable CharSequence text) {
        //noinspection SizeReplaceableByIsEmpty
        return text instanceof Spanned spanned && spanned.length() > 0
                && spanned.getSpans(0, 1, IconSpan.class).length > 0;
    }

    /**
     * The icon is shown only after {@link #setIconSpan(Spannable)} is called with the text,
     * so spans of the entire title can be applied to the icon first.
     *
     * @return The title with the character that is replaced by the icon.
     */
    public static String addIcon(String title) {
        return ICON_PLACEHOLDER + ICON_SEPARATOR + title;
    }

    /**
     * Shows the icon over the placeholder character.
     *
     * @param text Text returned by {@link #addIcon(String)}, with the spans of the title.
     */
    public static void setIconSpan(Spannable text) {
        // Without the icon, the placeholder character is shown instead.
        if (ICON != null) {
            text.setSpan(new IconSpan(ICON, ICON_SIZE, ICON_OPACITY), 0, ICON_PLACEHOLDER.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
