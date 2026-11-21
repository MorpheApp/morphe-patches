/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3510
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.shared.ui.IconSpan;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Shows the DeArrow icon before the titles submitted to DeArrow,
 * so they can be told apart from the titles of the uploader. The icon is also shown before the title
 * of a video whose thumbnail is replaced with its DeArrow thumbnail, where titles are replaced.
 * <p>
 * The icon is the DeArrow icon of the settings, tinted with the color of the title
 * so it matches the theme.
 * <p>
 * Tapping the icon shows the original title and thumbnail of the video instead, with a faded icon,
 * and tapping it again shows the DeArrow title and thumbnail. If only the thumbnail is from DeArrow,
 * then only the thumbnail is changed.
 */
public final class DeArrowTitleIcon {

    private static final boolean SHOW_ICON = DeArrowPatch.DeArrowTitlesAvailability.usingDeArrowTitlesAnywhere()
            && Settings.DEARROW_TITLES_ICON.get();

    private static final boolean SHOW_THUMBNAIL_ICON =
            DeArrowPatch.DeArrowThumbnailsAvailability.usingDeArrowThumbnailsAnywhere()
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
     * Each icon span draws its own copy, so the icons of different titles can have different opacities.
     */
    private static final Drawable ICON = ResourceUtils.getDrawable("morphe_settings_screen_02_dearrow_bold");

    /**
     * Size of the icon relative to the text size.
     */
    private static final float ICON_SIZE = 0.85f;

    /**
     * Width and height of the area that taps the icon, relative to the size of the icon,
     * so the icon is easier to tap without drawing it larger.
     */
    private static final float ICON_TOUCH_SIZE = 1.5f;

    /**
     * Opacity of the icon relative to the title, so the icon is visible but less prominent
     * than the title. The transparent icon is blended with any background, such as the
     * background of the app or of a video.
     */
    private static final float ICON_OPACITY = 0.5f;

    /**
     * Opacity of the icon shown before the title of a video whose DeArrow icon was tapped,
     * so the icon looks turned off and can be tapped again to show the DeArrow title and thumbnail.
     */
    private static final float ORIGINAL_ICON_OPACITY = 0.2f;

    /**
     * Videos whose DeArrow icon was tapped, which show the original title and thumbnail
     * instead of the DeArrow title and the replaced thumbnail.
     */
    private static final Set<String> originalBrandingVideoIds = Collections.newSetFromMap(
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000)));

    private DeArrowTitleIcon() {
    }

    /**
     * @return If the icon is shown before the title because it's a DeArrow title.
     */
    public static boolean isShown(@Nullable String title) {
        return findVideoId(title) != null;
    }

    /**
     * @return The video id of the DeArrow title, or null if the icon is not shown before the title.
     */
    @Nullable
    public static String findVideoId(@Nullable String title) {
        return SHOW_ICON && title != null ? DeArrowBrandingRequest.getVideoIdOfTitle(title.trim()) : null;
    }

    /**
     * Does not start fetching the DeArrow thumbnail. Can be called on any thread.
     *
     * @return If the icon is shown before the title of the video because its thumbnail
     *         is replaced with its DeArrow thumbnail for the current navigation.
     */
    public static boolean isShownForThumbnail(String videoId) {
        return SHOW_THUMBNAIL_ICON && DeArrowPatch.showsDeArrowThumbnailForCurrentNavigation(videoId);
    }

    /**
     * Waits until the DeArrow thumbnail of the video is fetched, if the icon can be shown for thumbnails,
     * so it's known if the icon is shown when the title is shown. Must be called off the main thread.
     */
    public static void fetchThumbnailIfNeeded(String videoId) {
        if (SHOW_THUMBNAIL_ICON) {
            DeArrowBrandingRequest.fetchThumbnailTime(videoId, DeArrowPatch.DEARROW_BRANDING_TIMEOUT_MILLISECONDS);
        }
    }

    /**
     * Can be called on any thread.
     *
     * @return If the DeArrow icon of the video was tapped to show the original title and thumbnail.
     */
    public static boolean isOriginalBrandingSelected(String videoId) {
        return originalBrandingVideoIds.contains(videoId);
    }

    /**
     * Can be called on any thread.
     *
     * @return If the DeArrow icon of any video was tapped to show the original title and thumbnail.
     */
    public static boolean isAnyOriginalBrandingSelected() {
        return !originalBrandingVideoIds.isEmpty();
    }

    /**
     * Shows the original or the DeArrow title and thumbnail of the video. The titles are laid out again,
     * and the thumbnails are loaded again if the thumbnail of the video is replaced. Can be called on any thread.
     */
    public static void setOriginalBrandingSelected(String videoId, boolean selected) {
        if (selected) {
            originalBrandingVideoIds.add(videoId);
        } else {
            originalBrandingVideoIds.remove(videoId);
        }

        if (DeArrowPatch.replacesThumbnailForCurrentNavigation(videoId)) {
            DeArrowPatch.remountThumbnailViews(videoId);
        }
        LithoRelayoutPatch.relayoutOutdatedTexts();
    }

    /**
     * Injection point. Called for each touch event of a Litho text, before the text checks
     * if the event is inside its bounds and finds the tapped span. The text handles only events
     * inside its bounds, so a touch just above or before the icon would otherwise tap the video.
     *
     * @param textDrawable Litho text drawable that the event is given to.
     * @param layout       Layout of the text, or null if the text is not laid out.
     * @return The event moved to the center of the icon if the event is in the touch area
     *         around the icon, or the same event.
     */
    public static MotionEvent iconTouchEvent(Drawable textDrawable, MotionEvent event, @Nullable Layout layout) {
        if (!SHOW_ICON && !SHOW_THUMBNAIL_ICON) {
            return event;
        }

        try {
            if (layout == null || !hasIcon(layout.getText())) {
                return event;
            }

            // The text finds the tapped span relative to the bounds of the drawable.
            Rect bounds = textDrawable.getBounds();
            final float iconStart = layout.getPrimaryHorizontal(0);
            final float iconEnd = layout.getPrimaryHorizontal(ICON_PLACEHOLDER.length());
            Paint.FontMetrics metrics = layout.getPaint().getFontMetrics();
            // Same center as the icon is drawn with.
            final float centerX = bounds.left + (iconStart + iconEnd) / 2;
            final float centerY = bounds.top + layout.getLineBaseline(0) + (metrics.ascent + metrics.descent) / 2;
            final float touchRadius = Math.abs(iconEnd - iconStart) * ICON_TOUCH_SIZE / 2;

            if (Math.abs(event.getX() - centerX) > touchRadius || Math.abs(event.getY() - centerY) > touchRadius) {
                return event;
            }

            // The same event is also given to the other contents of the Litho view, so a copy is moved.
            MotionEvent iconEvent = MotionEvent.obtain(event);
            iconEvent.setLocation(centerX, centerY);
            return iconEvent;
        } catch (Exception ex) {
            Logger.printException(() -> "iconTouchEvent failure", ex);
            return event;
        }
    }

    /**
     * @return If the text starts with the icon added by this class.
     */
    public static boolean hasIcon(@Nullable CharSequence text) {
        return text instanceof Spanned spanned && Utils.isNotEmpty(spanned)
                && spanned.getSpans(0, 1, IconSpan.class).length > 0;
    }

    /**
     * The icon is shown only after {@link #setIconSpans(Spannable, boolean, ClickableSpan)}
     * is called with the text, so spans of the entire title can be applied to the icon first.
     *
     * @return The title with the character that is replaced by the icon.
     */
    public static String addIcon(String title) {
        return ICON_PLACEHOLDER + ICON_SEPARATOR + title;
    }

    /**
     * Shows the icon over the placeholder character.
     *
     * @param text          Text returned by {@link #addIcon(String)}, with the spans of the title.
     * @param showsOriginal If the DeArrow icon of the video was tapped to show the original title and thumbnail.
     * @param tapSpan       Span that handles taps on the icon and the separator after it.
     */
    public static void setIconSpans(Spannable text, boolean showsOriginal, ClickableSpan tapSpan) {
        // Without the icon, the placeholder character is shown instead.
        if (ICON != null) {
            final float opacity = showsOriginal ? ORIGINAL_ICON_OPACITY : ICON_OPACITY;
            text.setSpan(new IconSpan(ICON, ICON_SIZE, opacity), 0, ICON_PLACEHOLDER.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.setSpan(tapSpan, 0, ICON_PLACEHOLDER.length() + ICON_SEPARATOR.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
