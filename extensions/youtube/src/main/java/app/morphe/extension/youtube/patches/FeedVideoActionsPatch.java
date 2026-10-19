/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3758
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Dialog;
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
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Pair;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.patches.utils.FeedVideoCards;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Feed video actions.
 * <p>
 * Feed videos are drawn by Litho, so a video has no view of its own. The root
 * {@link ComponentHost} of a full width video is recognized by its shape:
 * a 16:9 thumbnail at the top, and the menu button below it on the right.
 * <ul>
 *   <li>Tap and hold anywhere on the video: clicks the menu button of the video.</li>
 *   <li>Watch later button: drawn on the overlay of the root host. Tapping it opens the menu
 *       of the video without showing it, and clicks its own Watch later item, so the app
 *       saves the video and shows its own message. If the item is not found, the video is
 *       saved with the same request as the player button.</li>
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

    /** A video must be almost as wide as the screen. Shelves and grids are not changed. */
    private static final float MIN_WIDTH_OF_SCREEN = 0.7f;
    private static final float THUMBNAIL_RATIO = 9f / 16f;
    private static final int MIN_WIDTH_DP = 240;
    /** Minimum height of the video details below the thumbnail. */
    private static final int MIN_DETAILS_HEIGHT_DP = 40;
    /** Allowed overlap of the video details and the thumbnail. */
    private static final int THUMBNAIL_OVERLAP_DP = 8;
    private static final int MENU_BUTTON_MIN_DP = 20;
    private static final int MENU_BUTTON_MAX_DP = 72;
    /** The menu button is in the right part of the video. */
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

    /** Movement of the video on the screen that cancels a long press. */
    private static final int HOST_MOVE_TOLERANCE_DP = 2;

    /** Video texts shorter than this are not removed from the menu button description. */
    private static final int MIN_VIDEO_TEXT_LENGTH = 3;
    /** Length of the fixed start or end a description must share with the learned one. */
    private static final int MIN_SHARED_LENGTH = 6;

    /** Duration of a video, such as 4:05 or 1:02:33. */
    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\d{1,2}([:.]\\d{2}){1,2}$");
    /** A short badge in capital letters, such as the live badge. */
    private static final Pattern BADGE_PATTERN = Pattern.compile("^\\p{Lu}{2,12}$");
    private static final Pattern NOT_LETTERS_PATTERN = Pattern.compile("[^\\p{L}]+");

    /** Time the menu click has to open the menu, before a Watch later tap is forgotten. */
    private static final long PENDING_WATCH_LATER_TIMEOUT_MS = 2000;
    /** Time the hidden menu has to bind its items, before the request is used instead. */
    private static final long NATIVE_ITEM_TIMEOUT_MS = 700;
    /** The menu may be created before it is shown, so it is closed after each of these delays. */
    private static final long[] DISMISS_DELAYS_MS = {50, 300};

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

    private static WeakReference<View> pendingMenuButtonRef = new WeakReference<>(null);
    private static long pendingWatchLaterUptime;
    /** If the pending menu item is Save to playlist instead of Watch later. */
    private static boolean pendingSaveToPlaylist;
    private static String pendingVideoId = "";
    /** Menu that is hidden until its Watch later item is clicked. */
    private static WeakReference<Object> pendingFlyoutRef = new WeakReference<>(null);
    private static boolean pendingItemClicked;
    private static final Runnable pendingFallbackRunnable = FeedVideoActionsPatch::onPendingFallback;

    @Nullable
    private static Drawable clockIcon;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private static final class VideoCard {
        WeakReference<View> menuButtonRef = new WeakReference<>(null);
        final WatchLaterBadge badge = new WatchLaterBadge();
        boolean badgeAdded;

        @Nullable
        View getMenuButton() {
            View menuButton = menuButtonRef.get();
            return menuButton != null && menuButton.isAttachedToWindow() ? menuButton : null;
        }

        void placeBadge(ComponentHost host) {
            final int size = dp(host, BADGE_SIZE_DP);
            final int margin = dp(host, BADGE_MARGIN_DP);
            final int right = host.getWidth() - dp(host, BADGE_RIGHT_MARGIN_DP);
            badge.setBounds(right - size, margin, right, margin + size);
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
            if (!badgeAdded || FeedVideoCards.isCardPlayingInline(host)
                    || FeedVideoCards.isPlaylistCard(host)) {
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
            if (host != null && (FeedVideoCards.isCardPlayingInline(host)
                    || FeedVideoCards.isPlaylistCard(host))) {
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
     *         small, below the thumbnail and at the right side.
     */
    private static boolean isMenuButtonShaped(ComponentHost root, View view, Rect rect) {
        rect.set(0, 0, view.getWidth(), view.getHeight());
        root.offsetDescendantRectToMyCoords(view, rect);

        final int min = dp(root, MENU_BUTTON_MIN_DP);
        final int max = dp(root, MENU_BUTTON_MAX_DP);
        final int width = root.getWidth();
        return rect.width() >= min && rect.width() <= max
                && rect.height() >= min && rect.height() <= max
                && rect.centerX() >= width * MENU_BUTTON_MIN_CENTER_X
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
        List<CharSequence> texts = null;
        if (view instanceof ComponentHost host) {
            TextContent textContent = host.getTextContent();
            if (textContent != null) {
                texts = textContent.getTextItems();
            }
        } else if (view instanceof TextView textView) {
            texts = List.of(textView.getText());
        }

        if (texts != null && !texts.isEmpty()) {
            rect.set(0, 0, view.getWidth(), view.getHeight());
            if (view != root) {
                root.offsetDescendantRectToMyCoords(view, rect);
            }
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
     */
    private static boolean isSamePattern(String pattern, String learned) {
        if (pattern.isEmpty() || learned.isEmpty()) {
            return false;
        }
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
    private static View findMenuButton(ComponentHost root) {
        String learned = Settings.FEED_MENU_BUTTON_DESCRIPTION.get();
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
        View menuButton = isVideoShaped(host) ? findMenuButton(host) : null;
        // Until the menu button is learned, buttons are found only by their shape,
        // which posts with an image also have, so only videos with a badge are used.
        if (menuButton != null && Settings.FEED_MENU_BUTTON_DESCRIPTION.get().isEmpty()
                && !hasVideoBadge(host)) {
            menuButton = null;
        }

        if (menuButton == null) {
            if (card != null) {
                card.removeBadge(host);
                cards.remove(host);
            }
            return null;
        }

        if (card == null) {
            card = new VideoCard();
            cards.put(host, card);
        }
        card.menuButtonRef = new WeakReference<>(menuButton);
        if (WATCH_LATER_BUTTON) {
            card.placeBadge(host);
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
                            if (action == MotionEvent.ACTION_UP
                                    && card.isBadgeHit(host, event.getX(), event.getY())) {
                                saveToWatchLater(host, card);
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
            openMenuItem(card, true);
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

    private static void saveToWatchLater(ComponentHost host, VideoCard card) {
        if (card.getMenuButton() == null) {
            return;
        }
        vibrate(host, false);
        openMenuItem(card, false);
    }

    /**
     * Opens the menu of the video without showing it, and clicks its own item.
     *
     * @param saveToPlaylist If the item is Save to playlist, which opens the panel of the app.
     *                       Otherwise the item is Watch later.
     */
    private static void openMenuItem(VideoCard card, boolean saveToPlaylist) {
        View menuButton = card.getMenuButton();
        if (menuButton == null) {
            return;
        }

        // The menu of the video is opened without showing it, and its own Watch later item
        // is clicked, so the app saves the video and shows its own message.
        pendingMenuButtonRef = new WeakReference<>(menuButton);
        pendingVideoId = "";
        pendingWatchLaterUptime = SystemClock.uptimeMillis();
        pendingSaveToPlaylist = saveToPlaylist;
        pendingItemClicked = false;
        menuButton.performClick();
    }

    private static boolean isWatchLaterPending() {
        if (pendingWatchLaterUptime == 0) {
            return false;
        }
        if (SystemClock.uptimeMillis() - pendingWatchLaterUptime > PENDING_WATCH_LATER_TIMEOUT_MS) {
            clearPendingWatchLater();
            return false;
        }
        return true;
    }

    private static void clearPendingWatchLater() {
        pendingWatchLaterUptime = 0;
        pendingSaveToPlaylist = false;
        pendingVideoId = "";
        pendingMenuButtonRef = new WeakReference<>(null);
        pendingFlyoutRef = new WeakReference<>(null);
        pendingItemClicked = false;
        handler.removeCallbacks(pendingFallbackRunnable);
        handler.removeCallbacks(pendingItemCheckRunnable);
        pendingItemListRef = new WeakReference<>(null);
    }

    /**
     * Called by {@link FlyoutUtils} when a feed button is clicked, after the ids are found.
     * Not every such button opens a menu, such as the comment button of posts.
     */
    public static void onFlyoutSender(@Nullable View sender) {
        if (!ENABLED || sender == null) {
            return;
        }
        try {
            lastSenderRef = new WeakReference<>(sender);

            if (isWatchLaterPending()) {
                if (sender == pendingMenuButtonRef.get()) {
                    pendingVideoId = FlyoutUtils.getFlyoutVideoId();
                } else {
                    // Another menu was opened.
                    clearPendingWatchLater();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onFlyoutSender failure", ex);
        }
    }

    /**
     * Saves the localized description of the menu button, so the menu button of other videos
     * is found by it. Only a sender that opened a menu with a video id is the menu button.
     */
    private static void learnMenuButtonDescription() {
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
        if (!pattern.equals(Settings.FEED_MENU_BUTTON_DESCRIPTION.get())) {
            Logger.printDebug(() -> "Learned feed menu button pattern: " + pattern
                    + " (description: " + description + ")");
            Settings.FEED_MENU_BUTTON_DESCRIPTION.save(pattern);
        }
    }

    /**
     * Called by {@link FlyoutUtils} when a feed menu dialog is created.
     */
    public static void onBottomSheetFlyout(Dialog dialog) {
        if (!ENABLED) {
            return;
        }
        try {
            learnMenuButtonDescription();
            if (!isWatchLaterPending()) {
                return;
            }
            Window window = dialog.getWindow();
            if (window != null) {
                window.setWindowAnimations(0);
                window.setDimAmount(0f);
                WindowManager.LayoutParams params = window.getAttributes();
                params.alpha = 0f;
                window.setAttributes(params);
            }
            startPendingFlyout(dialog);
        } catch (Exception ex) {
            Logger.printException(() -> "onBottomSheetFlyout failure", ex);
        }
    }

    /**
     * Called by {@link FlyoutUtils} when a feed menu popup is about to be shown.
     */
    public static void onPopupWindowFlyout(PopupWindow popupWindow) {
        if (!ENABLED) {
            return;
        }
        try {
            learnMenuButtonDescription();
            if (!isWatchLaterPending()) {
                return;
            }
            popupWindow.setAnimationStyle(0);
            View content = popupWindow.getContentView();
            if (content != null) {
                content.setAlpha(0f);
            }
            startPendingFlyout(popupWindow);
        } catch (Exception ex) {
            Logger.printException(() -> "onPopupWindowFlyout failure", ex);
        }
    }

    private static void startPendingFlyout(Object flyout) {
        pendingFlyoutRef = new WeakReference<>(flyout);
        pendingItemListRef = new WeakReference<>(null);
        handler.removeCallbacks(pendingFallbackRunnable);
        handler.removeCallbacks(pendingItemCheckRunnable);
        handler.postDelayed(pendingFallbackRunnable, NATIVE_ITEM_TIMEOUT_MS);
        handler.postDelayed(pendingItemCheckRunnable, PENDING_ITEM_CHECK_MS);
    }

    @Nullable
    private static ViewGroup getFlyoutRootView(@Nullable Object flyout) {
        View root = null;
        if (flyout instanceof Dialog dialog && dialog.getWindow() != null) {
            root = dialog.getWindow().getDecorView();
        } else if (flyout instanceof PopupWindow popupWindow) {
            root = popupWindow.getContentView();
        }
        return root instanceof ViewGroup group ? group : null;
    }

    /** Items of the hidden menu, which can be bound before all their names are known. */
    private static WeakReference<ViewGroup> pendingItemListRef = new WeakReference<>(null);
    private static final long PENDING_ITEM_CHECK_MS = 50;

    /**
     * The items are checked again until the item is clicked or the fallback runs,
     * because the menu does not always bind its items again after the names are known.
     */
    private static final Runnable pendingItemCheckRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (pendingItemClicked || pendingFlyoutRef.get() == null || !isWatchLaterPending()) {
                    return;
                }
                ViewGroup itemList = pendingItemListRef.get();
                if (itemList == null) {
                    // The list was not reported, so the items are searched in the menu itself.
                    itemList = getFlyoutRootView(pendingFlyoutRef.get());
                }
                if (itemList != null) {
                    onFlyoutListBound(itemList);
                }
                if (!pendingItemClicked && pendingFlyoutRef.get() != null) {
                    handler.postDelayed(this, PENDING_ITEM_CHECK_MS);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "pendingItemCheck failure", ex);
            }
        }
    };

    /**
     * Called by {@link FlyoutUtils} each time the items of a menu are bound.
     */
    public static void onFlyoutListBound(ViewGroup itemList) {
        if (!WATCH_LATER_BUTTON || !ENABLED) {
            return;
        }
        try {
            if (pendingItemClicked || !isWatchLaterPending()) {
                return;
            }
            // Kept even before the menu is known, so the item check can use it later.
            pendingItemListRef = new WeakReference<>(itemList);
            if (pendingFlyoutRef.get() == null) {
                return;
            }

            final Pair<String, Integer> button = pendingSaveToPlaylist ? findSaveToPlaylistButton() : findWatchLaterButton();
            if (button == null) {
                return;
            }
            // The item is found by its text, as the index of the buttons does not always match the items
            // of the menu, such as when an item has no text or a button is not an item of the list.
            View item = findItemByText(itemList, FlyoutUtils.getVisibleFlyoutButtonText(button.first));
            final boolean foundByText = item != null;
            if (item == null) {
                final int itemIndex = button.second - 1;
                ViewGroup items = findItemGroup(itemList, itemIndex);
                if (items == null) {
                    // The items may not be bound yet. The fallback runs if they never are.
                    return;
                }
                item = items.getChildAt(itemIndex);
            }

            pendingItemClicked = true;
            final View clickedItem = item;
            Logger.printDebug(() -> "Clicking the " + (pendingSaveToPlaylist ? "Save to playlist" : "Watch later")
                    + " item of the hidden menu, found by " + (foundByText ? "text" : "index"));
            final boolean saveToPlaylist = pendingSaveToPlaylist;
            // Watch later: the app saves the video, shows its own message and closes the menu.
            // Save to playlist: the app closes the menu and opens its own panel.
            handler.post(() -> {
                try {
                    clickedItem.performClick();
                } catch (Exception ex) {
                    Logger.printException(() -> "Menu item click failure", ex);
                }
                Object flyout = pendingFlyoutRef.get();
                clearPendingWatchLater();
                if (saveToPlaylist) {
                    // If the panel is shown in the same sheet as the menu, the sheet must be visible again.
                    // A menu that the app closed is not shown again.
                    showHiddenMenu(flyout);
                } else {
                    dismissSoon(flyout);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onFlyoutListBound failure", ex);
        }
    }

    /**
     * The items are known by the names of the enum of the app, which do not depend on the language.
     */
    @Nullable
    private static Pair<String, Integer> findWatchLaterButton() {
        for (Pair<String, Integer> button : FlyoutUtils.getVisibleFlyoutButtons()) {
            String name = button.first;
            if (name.contains("WATCH_LATER") && !name.contains("REMOVE")) {
                return button;
            }
        }
        Logger.printDebug(() -> "No Watch later item in: " + FlyoutUtils.getVisibleFlyoutButtons());
        return null;
    }

    /**
     * @return The clickable item of the menu that shows the text, or null if it's not found.
     */
    @Nullable
    private static View findItemByText(ViewGroup itemList, @Nullable String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        TextView textView = Utils.getChildView(itemList, true, view ->
                view instanceof TextView candidate && text.contentEquals(candidate.getText()));
        if (textView == null) {
            return null;
        }

        // The item is the clickable view that contains the text.
        View item = textView;
        while (!item.isClickable()) {
            if (item == itemList || !(item.getParent() instanceof View parent)) {
                return null;
            }
            item = parent;
        }
        return item == itemList ? null : item;
    }

    /**
     * @return The view group of the menu items: the list itself, or in some layouts
     *         the view group inside it with the most children.
     */
    @Nullable
    private static ViewGroup findItemGroup(ViewGroup itemList, int itemIndex) {
        if (itemList.getChildCount() > itemIndex) {
            return itemList;
        }
        ViewGroup best = null;
        List<ViewGroup> groups = new ArrayList<>();
        groups.add(itemList);
        for (int g = 0; g < groups.size(); g++) {
            ViewGroup group = groups.get(g);
            if (group.getChildCount() > itemIndex
                    && (best == null || group.getChildCount() > best.getChildCount())) {
                best = group;
            }
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                if (group.getChildAt(i) instanceof ViewGroup child) {
                    groups.add(child);
                }
            }
        }
        if (best != null) {
            ViewGroup found = best;
            Logger.printDebug(() -> "Menu items found in a nested group: " + found.getClass().getName()
                    + " (" + found.getChildCount() + " items)");
        } else {
            Logger.printDebug(() -> "Menu items not bound yet: " + itemList.getChildCount() + " children");
        }
        return best;
    }

    /**
     * The icon names of the Save to playlist item, by preference.
     * The item uses the icon of a playlist or of a bookmark, depending on the app version.
     */
    private static final String[] SAVE_TO_PLAYLIST_ICON_NAMES = {"PLAYLIST_ADD", "ADD_TO_PLAYLIST", "BOOKMARK", "SAVE"};

    private static final Pattern NOT_SAVE_ITEM_PATTERN =
            Pattern.compile("DOWNLOAD|OFFLINE|SHARE|QUEUE|NOT_INTERESTED|DISMISS|FLAG|REPORT|CAST");

    @Nullable
    private static Pair<String, Integer> findSaveToPlaylistButton() {
        List<Pair<String, Integer>> buttons = FlyoutUtils.getVisibleFlyoutButtons();
        for (String iconName : SAVE_TO_PLAYLIST_ICON_NAMES) {
            for (Pair<String, Integer> button : buttons) {
                String name = button.first;
                if (name.contains(iconName) && !name.contains("WATCH_LATER") && !name.contains("REMOVE")) {
                    return button;
                }
            }
        }
        // The item is right after the Watch later item, if its icon has another name.
        for (int i = 0, size = buttons.size(); i < size - 1; i++) {
            String name = buttons.get(i).first;
            if (name.contains("WATCH_LATER") && !name.contains("REMOVE")) {
                Pair<String, Integer> next = buttons.get(i + 1);
                if (!NOT_SAVE_ITEM_PATTERN.matcher(next.first).find()) {
                    Logger.printDebug(() -> "Using the item after Watch later as Save to playlist: " + next.first);
                    return next;
                }
                break;
            }
        }
        Logger.printDebug(() -> "No Save to playlist item in: " + buttons);
        return null;
    }

    /**
     * The Save to playlist item was not found, so the hidden menu is shown,
     * and the item can be chosen from it.
     */
    private static void showHiddenMenu(@Nullable Object flyout) {
        if (flyout instanceof Dialog dialog && dialog.isShowing()) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setDimAmount(0.5f);
                WindowManager.LayoutParams params = window.getAttributes();
                params.alpha = 1f;
                window.setAttributes(params);
            }
        } else if (flyout instanceof PopupWindow popupWindow && popupWindow.isShowing()) {
            View content = popupWindow.getContentView();
            if (content != null) {
                content.setAlpha(1f);
            }
        }
    }

    /**
     * The Watch later item was not found, such as when the video is already saved,
     * so the video is saved with the same request as the player button.
     */
    private static void onPendingFallback() {
        try {
            if (pendingItemClicked) {
                return;
            }
            Object flyout = pendingFlyoutRef.get();
            String videoId = pendingVideoId;
            final boolean saveToPlaylist = pendingSaveToPlaylist;
            clearPendingWatchLater();
            if (saveToPlaylist) {
                showHiddenMenu(flyout);
                return;
            }
            dismissSoon(flyout);

            // Shows a toast if the id is not found.
            SaveToWatchLaterPatch.saveVideo(videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "onPendingFallback failure", ex);
        }
    }

    /**
     * The menu may be created before it is shown, so it is closed again after it is shown.
     */
    private static void dismissSoon(@Nullable Object flyout) {
        if (flyout == null) {
            return;
        }
        Runnable dismissIfShowing = () -> {
            try {
                if (flyout instanceof Dialog dialog && dialog.isShowing()) {
                    dialog.dismiss();
                } else if (flyout instanceof PopupWindow popupWindow && popupWindow.isShowing()) {
                    popupWindow.dismiss();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "dismiss failure", ex);
            }
        };
        for (long delay : DISMISS_DELAYS_MS) {
            handler.postDelayed(dismissIfShowing, delay);
        }
    }
}
