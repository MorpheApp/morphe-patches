/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3758
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Feed video actions.
 * <p>
 * Feed videos are drawn by Litho, so a video has no view of its own. The root
 * {@link ComponentHost} of a full width video is recognized by its shape:
 * a 16:9 thumbnail at the top, and the menu button below it at the end side
 * (on the right, or on the left in right to left languages).
 * <ul>
 *   <li>Tap and hold anywhere on the video: clicks the menu button of the video.</li>
 *   <li>Watch later button: drawn on the overlay of the root host. Tapping it saves the video to
 *       Watch later, and tapping and holding it opens the Save to playlist panel. Both run the
 *       command of the app directly, so they do not depend on the menu of the video.</li>
 * </ul>
 * The content description of the menu button is localized and contains the title of its
 * video. The texts of the video are removed from it, and the remaining pattern is learned
 * the first time the menu of a video is opened. Until then, only videos with a duration or
 * a live badge are used, because posts with an image have the same shape.
 */
@SuppressWarnings("unused")
public final class FeedVideoActionsPatch {

    private static final boolean LONG_PRESS_MENU = Settings.FEED_LONG_PRESS_MENU.get();
    private static final boolean WATCH_LATER_BUTTON = Settings.FEED_WATCH_LATER_BUTTON.get();
    private static final boolean ENABLED = isPatchIncluded() && (LONG_PRESS_MENU || WATCH_LATER_BUTTON);

    private static final boolean USE_EXPERIMENTAL_ICONS = VersionCheckPatch.IS_20_31_OR_GREATER
            && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");

    /**
     * A video must be as wide as a video of a grid of up to three columns, such as the feed in landscape.
     * Shelves are not changed.
     */
    private static final float MIN_WIDTH_OF_SCREEN = 0.3f;
    private static final float THUMBNAIL_RATIO = 9f / 16f;
    private static final int MIN_WIDTH_DP = 240;
    /** Minimum height of the video details below the thumbnail. */
    private static final int MIN_DETAILS_HEIGHT_DP = 40;
    /** Allowed overlap of the video details and the thumbnail. */
    private static final int THUMBNAIL_OVERLAP_DP = 8;
    private static final int MENU_BUTTON_MIN_DP = 20;
    private static final int MENU_BUTTON_MAX_DP = 72;
    /**
     * The menu button is in the end part of the video: the right part, or the left part
     * in right to left languages, where the channel avatar is in the right part.
     */
    private static final float MENU_BUTTON_MIN_CENTER_X = 0.8f;

    private static final int BADGE_SIZE_DP = 34;
    private static final int BADGE_ICON_DP = 20;
    private static final int BADGE_MARGIN_DP = 10;
    /** Same as the duration badge of the thumbnail, so their right edges line up. */
    public static final int BADGE_RIGHT_MARGIN_DP = 8;
    private static final int BADGE_TOUCH_SLOP_DP = 8;
    private static final int BADGE_COLOR = 0xA6000000;
    private static final int BADGE_ALPHA = 0xB3;
    private static final int BADGE_PRESSED_ALPHA = 0xFF;
    private static final int BADGE_ICON_COLOR = 0xFFFFFFFF;

    /** Space between the duration badge and the bottom of the thumbnail. */
    private static final int DURATION_BOTTOM_MARGIN_DP = 8;
    /** The menu button of a video is at most this far below the thumbnail. */
    private static final int MAX_MENU_BUTTON_BELOW_THUMBNAIL_DP = 64;

    /** Movement of the video on the screen that cancels a long press. */
    private static final int HOST_MOVE_TOLERANCE_DP = 2;

    /** Video texts shorter than this are not removed from the menu button description. */
    private static final int MIN_VIDEO_TEXT_LENGTH = 3;
    /** Separates the language from the learned pattern, which has only letters and spaces. */
    private static final char LANGUAGE_SEPARATOR = '|';
    /** Length of the fixed start or end a description must share with the learned one. */
    private static final int MIN_SHARED_LENGTH = 6;

    /** Duration of a video, such as 4:05 or 1:02:33. */
    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\d{1,2}([:.]\\d{2}){1,2}$");
    /** A short badge in capital letters, such as the live badge. */
    private static final Pattern BADGE_PATTERN = Pattern.compile("^\\p{Lu}{2,12}$");
    private static final Pattern NOT_LETTERS_PATTERN = Pattern.compile("[^\\p{L}]+");
    private static final Pattern LETTERS_PATTERN = Pattern.compile("\\p{L}{2,}");
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[\\w-]{11}$");

    /** {@code ACTION_ADD_VIDEO} of a playlist edit. */
    private static final int PLAYLIST_ACTION_ADD_VIDEO = 1;

    private static final StringRef VIDEO_ID_NOT_FOUND = StringRef.sfc("morphe_queue_manager_check_failed_video_id");

    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_LONG_PRESS_PENDING = 1;
    private static final int GESTURE_BADGE = 2;
    private static final int GESTURE_CONSUMED = 3;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** Root hosts that currently show a video. Used only on the main thread. */
    private static final Map<ComponentHost, VideoCard> cards = new WeakHashMap<>();

    /**
     * Root hosts with a layout change, which are checked once the layout pass is done.
     * The root host is a LithoView, which may not call the layout method of its super class,
     * so the layout of any host schedules its root host.
     */
    private static final Map<ComponentHost, Boolean> rootsToUpdate = new WeakHashMap<>();
    private static boolean rootsUpdateScheduled;
    private static final Runnable updateRootsRunnable = FeedVideoActionsPatch::updateRoots;

    private static WeakReference<ComponentHost> gestureHostRef = new WeakReference<>(null);
    private static int gestureMode = GESTURE_NONE;
    private static float downX, downY;
    private static float downRawX, downRawY;
    /** Position of the video on the screen when touched, to notice that the feed has moved. */
    private static final int[] downLocation = new int[2];
    private static final int[] currentLocation = new int[2];

    /** Longer than a normal long press, so resting a finger before scrolling does not open it. */
    private static final long LONG_PRESS_TIMEOUT_MS = ViewConfiguration.getLongPressTimeout() * 5L / 4;
    private static boolean dispatchingCancel;

    private static final Runnable longPressRunnable = FeedVideoActionsPatch::onLongPressTimeout;
    /** Shorter than a normal long press, but longer than any tap. */
    private static final long BADGE_LONG_PRESS_TIMEOUT_MS = 300;
    private static final Runnable badgeLongPressRunnable = FeedVideoActionsPatch::onBadgeLongPressTimeout;

    /** Sender of the last menu, which is learned once the menu really opens for a video. */
    private static WeakReference<View> lastSenderRef = new WeakReference<>(null);

    @Nullable
    private static Drawable clockIcon;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private static final class VideoCard {
        WeakReference<View> menuButtonRef = new WeakReference<>(null);
        final WatchLaterBadge badge = new WatchLaterBadge();
        boolean badgeAdded;
        /** Top of the thumbnail in the host, or -1 if it is not known. */
        int thumbnailTop = -1;

        @Nullable
        View getMenuButton() {
            View menuButton = menuButtonRef.get();
            return menuButton != null && menuButton.isAttachedToWindow() ? menuButton : null;
        }

        void placeBadge(ComponentHost host) {
            final int size = dp(host, BADGE_SIZE_DP);
            final int top = thumbnailTop + dp(host, BADGE_MARGIN_DP);
            final int right = host.getWidth() - dp(host, BADGE_RIGHT_MARGIN_DP);
            badge.setBounds(right - size, top, right, top + size);
            badge.hostRef = new WeakReference<>(host);
            if (!badgeAdded) {
                host.getOverlay().add(badge);
                badgeAdded = true;
            }
        }

        void removeBadge(ComponentHost host) {
            if (badgeAdded) {
                host.getOverlay().remove(badge);
                badgeAdded = false;
            }
        }

        boolean isBadgeHit(ComponentHost host, float x, float y) {
            if (!badgeAdded || FeedVideoCardsPatch.isCardPlayingInline(host)
                    || FeedVideoCardsPatch.isPlaylistCard(host)) {
                return false;
            }
            Rect bounds = badge.getBounds();
            final int slop = dp(host, BADGE_TOUCH_SLOP_DP);
            return x >= bounds.left - slop && x <= bounds.right + slop
                    && y >= bounds.top - slop && y <= bounds.bottom + slop;
        }
    }

    /**
     * Round dark button with the clock icon of the app, like on the desktop site.
     */
    private static final class WatchLaterBadge extends Drawable {
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean pressed;
        WeakReference<ComponentHost> hostRef = new WeakReference<>(null);

        WatchLaterBadge() {
            backgroundPaint.setColor(BADGE_COLOR);
        }

        void setPressed(boolean pressed) {
            if (this.pressed != pressed) {
                this.pressed = pressed;
                invalidateSelf();
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            ComponentHost host = hostRef.get();
            if (host != null && (FeedVideoCardsPatch.isCardPlayingInline(host)
                    || FeedVideoCardsPatch.isPlaylistCard(host))) {
                // The mute button of the inline player is here, or the card is a playlist or a mix.
                return;
            }
            Rect bounds = getBounds();
            backgroundPaint.setAlpha(pressed ? BADGE_PRESSED_ALPHA : BADGE_ALPHA);
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(),
                    bounds.width() / 2f, backgroundPaint);

            Drawable icon = getClockIcon();
            if (icon != null) {
                final int iconSize = bounds.width() * BADGE_ICON_DP / BADGE_SIZE_DP;
                final int left = bounds.centerX() - iconSize / 2;
                final int top = bounds.centerY() - iconSize / 2;
                icon.setBounds(left, top, left + iconSize, top + iconSize);
                icon.draw(canvas);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    @Nullable
    private static Drawable getClockIcon() {
        if (clockIcon == null) {
            Drawable icon = ResourceUtils.getDrawable(USE_EXPERIMENTAL_ICONS
                    ? "yt_outline_experimental_clock_vd_theme_24"
                    : "yt_outline_clock_black_24");
            if (icon != null) {
                icon = icon.mutate();
                icon.setColorFilter(new PorterDuffColorFilter(BADGE_ICON_COLOR, PorterDuff.Mode.SRC_IN));
            }
            clockIcon = icon;
        }
        return clockIcon;
    }

    private static int dp(View view, int dp) {
        return Math.round(dp * view.getResources().getDisplayMetrics().density);
    }

    private static boolean isRootHost(ComponentHost host) {
        return !(host.getParent() instanceof ComponentHost);
    }

    @Nullable
    private static ComponentHost findRootHost(View view) {
        ComponentHost root = null;
        ViewParent parent = view instanceof ComponentHost ? (ViewParent) view : view.getParent();
        while (parent instanceof ComponentHost host) {
            root = host;
            parent = host.getParent();
        }
        return root;
    }

    private static boolean isVideoShaped(ComponentHost host) {
        final int width = host.getWidth();
        final int screenWidth = host.getResources().getDisplayMetrics().widthPixels;
        return width >= dp(host, MIN_WIDTH_DP)
                && width >= screenWidth * MIN_WIDTH_OF_SCREEN
                && host.getHeight() >= width * THUMBNAIL_RATIO + dp(host, MIN_DETAILS_HEIGHT_DP);
    }

    /**
     * @return If the view is where the menu button of a video is:
     *         small, below the thumbnail and at the end side.
     */
    private static boolean isMenuButtonShaped(ComponentHost root, View view, Rect rect) {
        rect.set(0, 0, view.getWidth(), view.getHeight());
        root.offsetDescendantRectToMyCoords(view, rect);

        final int min = dp(root, MENU_BUTTON_MIN_DP);
        final int max = dp(root, MENU_BUTTON_MAX_DP);
        final int width = root.getWidth();
        // The channel avatar has the same size, at the start side.
        final boolean atEndSide = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? rect.centerX() <= width * (1 - MENU_BUTTON_MIN_CENTER_X)
                : rect.centerX() >= width * MENU_BUTTON_MIN_CENTER_X;
        return rect.width() >= min && rect.width() <= max
                && rect.height() >= min && rect.height() <= max
                && atEndSide
                && rect.top >= width * THUMBNAIL_RATIO - dp(root, THUMBNAIL_OVERLAP_DP);
    }

    /**
     * Posts are shaped like videos when they have an image, and their buttons below the image
     * are not the menu. Only videos show a duration (or a live badge) on the thumbnail.
     */
    private static boolean hasVideoBadge(ComponentHost root) {
        final int thumbnailBottom = Math.round(root.getWidth() * THUMBNAIL_RATIO) + dp(root, THUMBNAIL_OVERLAP_DP);
        return hasVideoBadge(root, root, thumbnailBottom, new Rect());
    }

    private static boolean hasVideoBadge(ComponentHost root, View view, int thumbnailBottom, Rect rect) {
        List<CharSequence> texts = getTexts(view);
        if (texts != null && !texts.isEmpty()) {
            setRectInRoot(root, view, rect);
            final boolean inThumbnail = rect.bottom <= thumbnailBottom;

            for (CharSequence text : texts) {
                if (text == null) {
                    continue;
                }
                String item = text.toString().trim();
                if (DURATION_PATTERN.matcher(item).matches()
                        || (inThumbnail && BADGE_PATTERN.matcher(item).matches())) {
                    return true;
                }
            }
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE
                        && hasVideoBadge(root, child, thumbnailBottom, rect)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return The texts drawn by the view itself, or null if it draws none.
     */
    @Nullable
    private static List<CharSequence> getTexts(View view) {
        if (view instanceof ComponentHost host) {
            TextContent textContent = host.getTextContent();
            return textContent == null ? null : textContent.getTextItems();
        }
        if (view instanceof TextView textView) {
            return List.of(textView.getText());
        }
        return null;
    }

    private static void setRectInRoot(ComponentHost root, View view, Rect rect) {
        rect.set(0, 0, view.getWidth(), view.getHeight());
        if (view != root) {
            root.offsetDescendantRectToMyCoords(view, rect);
        }
    }

    /**
     * @return The bottom of the lowest duration badge in the host, or -1 if none is shown.
     */
    private static int findDurationBottom(ComponentHost root, View view, Rect rect) {
        int bottom = -1;
        List<CharSequence> texts = getTexts(view);
        if (texts != null) {
            for (CharSequence text : texts) {
                if (text != null && DURATION_PATTERN.matcher(text.toString().trim()).matches()) {
                    setRectInRoot(root, view, rect);
                    bottom = rect.bottom;
                    break;
                }
            }
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE) {
                    bottom = Math.max(bottom, findDurationBottom(root, child, rect));
                }
            }
        }
        return bottom;
    }

    /**
     * @return If the host shows a text above the position, such as the header
     *         and the text of a post above its video.
     */
    private static boolean hasTextAbove(ComponentHost root, View view, int y, Rect rect) {
        List<CharSequence> texts = getTexts(view);
        if (texts != null) {
            for (CharSequence text : texts) {
                if (text != null && LETTERS_PATTERN.matcher(text).find()) {
                    setRectInRoot(root, view, rect);
                    if (rect.bottom <= y) {
                        return true;
                    }
                    break;
                }
            }
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && hasTextAbove(root, child, y, rect)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The thumbnail of a video card is at the top, but the video of a post is below the
     * header and the text of the post. The duration badge is at the bottom of the thumbnail.
     * Thumbnails can be square, such as of music, so a thumbnail is at the top of the card
     * unless a text is shown above it.
     *
     * @return The top of the thumbnail in the host, or -1 if it is not known.
     */
    private static int findThumbnailTop(ComponentHost host, View menuButton) {
        final int width = host.getWidth();
        final int thumbnailHeight = Math.round(width * THUMBNAIL_RATIO);
        Rect rect = new Rect();
        final int durationBottom = findDurationBottom(host, host, rect);
        if (durationBottom >= 0) {
            final int top = durationBottom + dp(host, DURATION_BOTTOM_MARGIN_DP) - thumbnailHeight;
            return top > 0 && hasTextAbove(host, host, top, rect) ? top : 0;
        }

        // Live videos have no duration. The thumbnail is above the menu button,
        // and at most as tall as it is wide.
        setRectInRoot(host, menuButton, rect);
        final int highestTop = rect.top - width - dp(host, MAX_MENU_BUTTON_BELOW_THUMBNAIL_DP);
        return highestTop > 0 && hasTextAbove(host, host, highestTop, rect) ? -1 : 0;
    }

    /**
     * @return The language of the app, which the description of the menu button is in.
     */
    private static String getLanguage(View view) {
        LocaleList locales = view.getResources().getConfiguration().getLocales();
        return (locales.isEmpty() ? Locale.getDefault() : locales.get(0)).toLanguageTag();
    }

    /**
     * The pattern is learned in the language of the app, so a pattern of another language,
     * such as after the language of the app is changed, is not used until it is learned again.
     *
     * @return The learned pattern of the menu button description, or an empty string if it is
     *         not learned in the language of the app.
     */
    private static String getLearnedPattern(View view) {
        String learned = Settings.FEED_MENU_BUTTON_DESCRIPTION.get();
        final int separator = learned.indexOf(LANGUAGE_SEPARATOR);
        if (separator < 0 || !learned.substring(0, separator).equals(getLanguage(view))) {
            return "";
        }
        return learned.substring(separator + 1);
    }

    /**
     * The description of the menu button contains the title of its video, such as
     * "Action menu: [title]", so it differs on every video. The texts of the video are
     * removed from it, and what remains is the same on every video.
     */
    private static String getDescriptionPattern(CharSequence description, List<String> videoTexts) {
        String pattern = description.toString();
        for (String text : videoTexts) {
            if (pattern.contains(text)) {
                pattern = pattern.replace(text, " ");
            }
        }
        return NOT_LETTERS_PATTERN.matcher(pattern).replaceAll(" ").trim();
    }

    /**
     * If a text of the video is not drawn by Litho, it is still in the description.
     * The fixed part of the description is then at its start or at its end.
     * The case is ignored, as the layouts can use another case for the same words,
     * such as "Action menu" and "action menu for [title]", and the pattern can be
     * learned on another layout.
     */
    private static boolean isSamePattern(String pattern, String learned) {
        if (pattern.isEmpty() || learned.isEmpty()) {
            return false;
        }
        pattern = pattern.toLowerCase(Locale.ROOT);
        learned = learned.toLowerCase(Locale.ROOT);
        if (pattern.equals(learned)) {
            return true;
        }
        final int length = Math.min(pattern.length(), learned.length());
        if (length < MIN_SHARED_LENGTH) {
            return false;
        }
        int prefix = 0;
        while (prefix < length && pattern.charAt(prefix) == learned.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < length && pattern.charAt(pattern.length() - 1 - suffix)
                == learned.charAt(learned.length() - 1 - suffix)) {
            suffix++;
        }
        return prefix >= MIN_SHARED_LENGTH || suffix >= MIN_SHARED_LENGTH;
    }

    /**
     * @return The texts drawn on the video, longest first so a title is removed before its words.
     */
    private static List<String> collectVideoTexts(ComponentHost root) {
        List<String> texts = new ArrayList<>();
        collectVideoTexts(root, texts);
        texts.sort((a, b) -> b.length() - a.length());
        return texts;
    }

    private static void collectVideoTexts(ComponentHost host, List<String> texts) {
        TextContent textContent = host.getTextContent();
        if (textContent != null) {
            for (CharSequence text : textContent.getTextItems()) {
                if (text != null) {
                    String item = text.toString().trim();
                    if (item.length() >= MIN_VIDEO_TEXT_LENGTH && !texts.contains(item)) {
                        texts.add(item);
                    }
                }
            }
        }
        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            if (host.getChildAt(i) instanceof ComponentHost child) {
                collectVideoTexts(child, texts);
            }
        }
    }

    /**
     * Once the pattern of the menu button description is learned, only a button with that
     * pattern is used, so other buttons of the right shape, such as the comment button
     * of posts, never are.
     */
    @Nullable
    private static View findMenuButton(ComponentHost root, String learned) {
        List<String> videoTexts = learned.isEmpty() ? List.of() : collectVideoTexts(root);
        View[] bestMatch = new View[1];
        if (findMenuButton(root, root, learned, videoTexts, new Rect(), bestMatch)) {
            return bestMatch[0];
        }
        return learned.isEmpty() ? bestMatch[0] : null;
    }

    /**
     * @return If a button with the learned pattern was found,
     *         otherwise the topmost button of the right shape is kept.
     */
    private static boolean findMenuButton(ComponentHost root, ViewGroup group, String learned,
                                          List<String> videoTexts, Rect rect, View[] bestMatch) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (child.isClickable() && isMenuButtonShaped(root, child, rect)) {
                if (!learned.isEmpty()) {
                    CharSequence description = child.getContentDescription();
                    if (!TextUtils.isEmpty(description)
                            && isSamePattern(getDescriptionPattern(description, videoTexts), learned)) {
                        bestMatch[0] = child;
                        return true;
                    }
                } else if (bestMatch[0] == null) {
                    bestMatch[0] = child;
                }
            }
            if (child instanceof ViewGroup childGroup
                    && findMenuButton(root, childGroup, learned, videoTexts, rect, bestMatch)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static VideoCard updateCard(ComponentHost host) {
        VideoCard card = cards.get(host);
        View menuButton = null;
        if (isVideoShaped(host)) {
            String learned = getLearnedPattern(host);
            menuButton = findMenuButton(host, learned);
            // Until the menu button is learned, buttons are found only by their shape,
            // which posts with an image also have, so only videos with a badge are used.
            if (menuButton != null && learned.isEmpty() && !hasVideoBadge(host)) {
                menuButton = null;
            }
        }

        if (menuButton == null) {
            if (card != null) {
                // Litho mounts only the part of a card that is shown, so the menu button below
                // the thumbnail, such as of the classic layout, is not mounted while only the
                // thumbnail is shown, as when the card leaves the screen. The button is kept
                // until the card is fully shown and still has no menu button.
                if (card.badgeAdded && isVideoShaped(host) && FeedVideoCardsPatch.scheduleScrollCheck(host)) {
                    return card;
                }
                card.removeBadge(host);
                cards.remove(host);
            }
            return null;
        }

        if (card == null) {
            card = new VideoCard();
            cards.put(host, card);
        }
        final boolean sameMenuButton = card.menuButtonRef.get() == menuButton;
        card.menuButtonRef = new WeakReference<>(menuButton);
        if (WATCH_LATER_BUTTON) {
            final int thumbnailTop = findThumbnailTop(host, menuButton);
            // Litho mounts only the part of a card that is shown, so the duration badge
            // may not be mounted. The thumbnail of the same video does not move.
            if (thumbnailTop >= 0 || !sameMenuButton) {
                card.thumbnailTop = thumbnailTop;
            }
            if (card.thumbnailTop >= 0) {
                card.placeBadge(host);
            } else {
                card.removeBadge(host);
            }
        }
        return card;
    }

    private static void updateRoots() {
        rootsUpdateScheduled = false;
        try {
            ComponentHost[] roots = rootsToUpdate.keySet().toArray(new ComponentHost[0]);
            rootsToUpdate.clear();
            for (ComponentHost root : roots) {
                if (root != null && root.isAttachedToWindow() && isRootHost(root)) {
                    updateCard(root);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateRoots failure", ex);
        }
    }

    /**
     * Called when {@link FeedVideoCardsPatch} checks a card, which it also does again while
     * the list scrolls and the card is partly shown. Litho mounts only the part of a card that
     * is shown, so the menu button of a card that enters from the top of the list may not be
     * found when it is laid out, and the card may not be laid out again once it is shown.
     *
     * @param videoId The video of the card, or null if the card is not a known video.
     */
    public static void onCardLaidOut(ComponentHost host, @Nullable String videoId) {
        if (!ENABLED) {
            return;
        }
        try {
            if (updateCard(host) == null && videoId != null) {
                FeedVideoCardsPatch.scheduleScrollCheck(host);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onCardLaidOut failure", ex);
        }
    }

    /**
     * Injection point.
     * Called after a host has laid out its children.
     */
    public static void onComponentHostLayout(ComponentHost host) {
        if (!ENABLED) {
            return;
        }
        try {
            ComponentHost root = findRootHost(host);
            if (root == null) {
                return;
            }
            rootsToUpdate.put(root, Boolean.TRUE);
            if (!rootsUpdateScheduled) {
                rootsUpdateScheduled = true;
                handler.post(updateRootsRunnable);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostLayout failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @return If the touch was handled and must not be given to the children.
     */
    public static boolean onComponentHostTouch(ComponentHost host, MotionEvent event) {
        if (!ENABLED || dispatchingCancel || !isRootHost(host)) {
            return false;
        }
        try {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                return onTouchDown(host, event);
            }
            if (gestureHostRef.get() != host || gestureMode == GESTURE_NONE) {
                return false;
            }

            final boolean gestureEnded = action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL;

            // A second finger is a zoom or a scroll, never a long press.
            if (action == MotionEvent.ACTION_POINTER_DOWN && gestureMode == GESTURE_LONG_PRESS_PENDING) {
                resetGesture();
                return false;
            }
            switch (gestureMode) {
                case GESTURE_BADGE -> {
                    VideoCard card = cards.get(host);
                    if (action == MotionEvent.ACTION_MOVE && card != null
                            && !card.isBadgeHit(host, event.getX(), event.getY())) {
                        // Leaving the button cancels the tap and the long press.
                        card.badge.setPressed(false);
                        gestureMode = GESTURE_CONSUMED;
                        handler.removeCallbacks(badgeLongPressRunnable);
                        return true;
                    }
                    if (gestureEnded) {
                        if (card != null) {
                            card.badge.setPressed(false);
                            // Saves the video to Watch later.
                            if (action == MotionEvent.ACTION_UP
                                    && card.isBadgeHit(host, event.getX(), event.getY())) {
                                vibrate(host, false);
                                runVideoCommand(host, false);
                            }
                        }
                        resetGesture();
                    }
                    return true;
                }
                case GESTURE_LONG_PRESS_PENDING -> {
                    if (gestureEnded) {
                        resetGesture();
                    } else if (action == MotionEvent.ACTION_MOVE && hasMoved(host, event)) {
                        resetGesture();
                    }
                    return false;
                }
                case GESTURE_CONSUMED -> {
                    if (gestureEnded) {
                        resetGesture();
                    }
                    return true;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostTouch failure", ex);
            resetGesture();
        }
        return false;
    }

    private static boolean onTouchDown(ComponentHost host, MotionEvent event) {
        resetGesture();

        VideoCard card = cards.get(host);
        if (card == null || card.getMenuButton() == null) {
            // The host may show another video since its last layout.
            card = updateCard(host);
            if (card == null) {
                return false;
            }
        }

        gestureHostRef = new WeakReference<>(host);
        downX = event.getX();
        downY = event.getY();
        downRawX = event.getRawX();
        downRawY = event.getRawY();
        host.getLocationOnScreen(downLocation);

        if (WATCH_LATER_BUTTON && card.isBadgeHit(host, downX, downY)) {
            gestureMode = GESTURE_BADGE;
            card.badge.setPressed(true);
            // Tapping and holding the button opens the Save to playlist panel.
            handler.postDelayed(badgeLongPressRunnable, BADGE_LONG_PRESS_TIMEOUT_MS);
            return true;
        }

        if (LONG_PRESS_MENU) {
            gestureMode = GESTURE_LONG_PRESS_PENDING;
            handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS);
        }
        return false;
    }

    /**
     * Movement much smaller than a scroll already cancels the long press,
     * both of the finger and of the feed below the finger.
     */
    private static boolean hasMoved(ComponentHost host, MotionEvent event) {
        final float tolerance = ViewConfiguration.get(host.getContext()).getScaledTouchSlop() / 2f;
        return Math.abs(event.getX() - downX) > tolerance
                || Math.abs(event.getY() - downY) > tolerance
                || Math.abs(event.getRawX() - downRawX) > tolerance
                || Math.abs(event.getRawY() - downRawY) > tolerance
                || hasHostMoved(host);
    }

    private static boolean hasHostMoved(ComponentHost host) {
        host.getLocationOnScreen(currentLocation);
        final int tolerance = dp(host, HOST_MOVE_TOLERANCE_DP);
        return Math.abs(currentLocation[0] - downLocation[0]) > tolerance
                || Math.abs(currentLocation[1] - downLocation[1]) > tolerance;
    }

    private static void resetGesture() {
        handler.removeCallbacks(longPressRunnable);
        handler.removeCallbacks(badgeLongPressRunnable);
        gestureMode = GESTURE_NONE;
        gestureHostRef = new WeakReference<>(null);
    }

    private static void onLongPressTimeout() {
        try {
            ComponentHost host = gestureHostRef.get();
            if (host == null || gestureMode != GESTURE_LONG_PRESS_PENDING) {
                return;
            }
            VideoCard card = cards.get(host);
            View menuButton = card == null ? null : card.getMenuButton();
            // The feed can still move without new touch events, such as after a fling.
            if (menuButton == null || hasHostMoved(host)) {
                resetGesture();
                return;
            }

            // The rest of the gesture is not given to the children,
            // so lifting the finger does not open the video.
            gestureMode = GESTURE_CONSUMED;
            cancelChildrenTouch(host);

            host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            menuButton.performClick();
        } catch (Exception ex) {
            Logger.printException(() -> "onLongPressTimeout failure", ex);
            resetGesture();
        }
    }

    /**
     * Removes the pressed state of the children, and their own pending clicks.
     */
    private static void cancelChildrenTouch(ComponentHost host) {
        final long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0);
        dispatchingCancel = true;
        try {
            host.dispatchTouchEvent(cancel);
        } finally {
            dispatchingCancel = false;
            cancel.recycle();
        }
    }

    private static void onBadgeLongPressTimeout() {
        try {
            ComponentHost host = gestureHostRef.get();
            if (host == null || gestureMode != GESTURE_BADGE) {
                return;
            }
            VideoCard card = cards.get(host);
            // The rest of the gesture is consumed, so lifting the finger does not also save to Watch later.
            gestureMode = GESTURE_CONSUMED;
            if (card == null) {
                return;
            }
            card.badge.setPressed(false);
            vibrate(host, true);
            runVideoCommand(host, true);
        } catch (Exception ex) {
            Logger.printException(() -> "onBadgeLongPressTimeout failure", ex);
            resetGesture();
        }
    }

    /**
     * The Litho host can have haptic feedback turned off, so the vibrator is used when possible.
     */
    private static void vibrate(View view, boolean longPress) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Vibrator vibrator = view.getContext().getSystemService(Vibrator.class);
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createPredefined(longPress
                            ? VibrationEffect.EFFECT_HEAVY_CLICK
                            : VibrationEffect.EFFECT_CLICK));
                    return;
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Vibrator not available: " + ex);
        }
        view.performHapticFeedback(longPress
                        ? HapticFeedbackConstants.LONG_PRESS
                        : HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
    }

    /**
     * Runs the command of the app that its own menu items run, with the video of the card.
     *
     * @param saveToPlaylist If the Save to playlist panel is opened.
     *                       Otherwise the video is saved to Watch later.
     */
    private static void runVideoCommand(ComponentHost host, boolean saveToPlaylist) {
        String cardVideoId = FeedVideoCardsPatch.findVideoId(host);
        // The texts of a card that is not fully shown may not be drawn.
        final String videoId = cardVideoId != null && VIDEO_ID_PATTERN.matcher(cardVideoId).matches()
                ? cardVideoId
                : FeedVideoCardsPatch.findVideoIdByDescription(host);
        if (videoId == null) {
            Logger.printDebug(() -> "Video of the card not found: " + cardVideoId);
            Utils.showSnackbarShort(VIDEO_ID_NOT_FOUND.toString());
            return;
        }

        Logger.printDebug(() -> (saveToPlaylist ? "Opening Save to playlist: " : "Saving to Watch later: ") + videoId);
        var command = PlaylistQueueRenderer.NavigationEndpoint.newBuilder();
        if (saveToPlaylist) {
            command.setAddToPlaylistEndpoint(PlaylistQueueRenderer.AddToPlaylistEndpoint.newBuilder()
                    .setVideoId(videoId));
        } else {
            command.setPlaylistEditEndpoint(PlaylistQueueRenderer.PlaylistEditEndpoint.newBuilder()
                    .setPlaylistId(SaveToWatchLaterPatch.WATCH_LATER_PLAYLIST_ID)
                    .addActions(PlaylistQueueRenderer.PlaylistEditAction.newBuilder()
                            .setAction(PLAYLIST_ACTION_ADD_VIDEO)
                            .setAddedVideoId(videoId)));
        }
        if (!CommandRouterPatch.executeCommand(command.build().toByteArray(), new HashMap<>())) {
            Logger.printDebug(() -> "No command router of a shown activity");
        }
    }

    /**
     * Called by {@link FlyoutUtils} when a feed button is clicked, after the ids are found.
     * Not every such button opens a menu, such as the comment button of posts.
     */
    public static void onFlyoutSender(@Nullable View sender) {
        if (ENABLED && sender != null) {
            lastSenderRef = new WeakReference<>(sender);
        }
    }

    /**
     * Called by {@link FlyoutUtils} when a feed menu is created.
     * Saves the localized description of the menu button, so the menu button of other videos
     * is found by it. Only a sender that opened a menu with a video id is the menu button.
     */
    public static void onFlyoutCreated() {
        if (!ENABLED) {
            return;
        }
        try {
            View sender = lastSenderRef.get();
            lastSenderRef = new WeakReference<>(null);
            if (sender == null || FlyoutUtils.getFlyoutVideoId().isEmpty()) {
                return;
            }

            CharSequence description = sender.getContentDescription();
            if (TextUtils.isEmpty(description)) {
                return;
            }
            ComponentHost root = findRootHost(sender);
            if (root == null || !isVideoShaped(root) || !isMenuButtonShaped(root, sender, new Rect())) {
                return;
            }

            String pattern = getDescriptionPattern(description, collectVideoTexts(root));
            if (pattern.isEmpty()) {
                return;
            }
            String learned = getLanguage(root) + LANGUAGE_SEPARATOR + pattern;
            if (!learned.equals(Settings.FEED_MENU_BUTTON_DESCRIPTION.get())) {
                Logger.printDebug(() -> "Learned feed menu button pattern: " + learned
                        + " (description: " + description + ")");
                Settings.FEED_MENU_BUTTON_DESCRIPTION.save(learned);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onFlyoutCreated failure", ex);
        }
    }
}
