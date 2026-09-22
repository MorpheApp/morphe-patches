/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3075
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.returnyoutubedislike;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;
import com.facebook.yoga.YogaNative;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Draws the like and dislike counts over the action bar buttons of YouTube and YouTube Music,
 * which lay out these buttons with the same Litho components.
 */
public final class ReturnYouTubeDislikeButtons {

    private static final boolean RYD_ENABLED = SharedYouTubeSettings.RYD_ENABLED.get();

    private static volatile Supplier<ReturnYouTubeDislike> videoDataSource = () -> null;

    private ReturnYouTubeDislikeButtons() {
    }

    /**
     * @param source The RYD data of the video or track on screen, kept by each app.
     */
    public static void setVideoDataSource(Supplier<ReturnYouTubeDislike> source) {
        videoDataSource = source;
    }

    /**
     * YouTube Music draws its segmented button larger, with a larger like count.
     *
     * @param iconDp        Width of the dislike icon.
     * @param textSp        Size of the like count.
     * @param startMarginDp Gap between the dislike icon and its count.
     */
    public static void setSegmentedButtonSizes(float iconDp, float textSp, float startMarginDp) {
        oldBarDislikeIconWidth = Dim.dp(iconDp);
        oldBarDislikeIconExactWidth = exactDp(iconDp);
        oldBarCountTextSizeSp = textSp;
        oldBarCountStartMargin = Dim.dp(startMarginDp);
        oldBarCountPaint = null;
    }

    @Nullable
    private static ReturnYouTubeDislike currentVideoData() {
        return videoDataSource.get();
    }

    //
    // Dislike button width of the old action bar.
    //

    private static volatile int oldBarDislikeIconWidth = Dim.dp16;
    /**
     * {@link Dim} truncates, while Litho rounds, so at a density such as 2.75 a 1dp width is
     * 2 there and 3 in the layout. The widths of the layout are matched against the exact size.
     */
    private static volatile float oldBarDislikeIconExactWidth = exactDp(16);
    private static final float OLD_BAR_SEPARATOR_EXACT_WIDTH = exactDp(1);
    private static final float OLD_BAR_BEFORE_SEPARATOR_MIN_WIDTH = exactDp(2);
    private static final float OLD_BAR_BEFORE_SEPARATOR_MAX_WIDTH = exactDp(100);
    private static volatile float oldBarCountTextSizeSp = 12;
    /**
     * The like icon has more empty space beside it, so the dislike count needs a wider gap to sit
     * as far from its icon. The end gap brings the padding after it up to what YouTube leaves
     * after text.
     */
    private static volatile int oldBarCountStartMargin = Dim.dp(9);
    private static final int OLD_BAR_COUNT_END_MARGIN = Dim.dp(5);

    /**
     * Nothing identifies the button, since the whole bar is a single Litho component, so it is
     * found by the separator that is always laid out immediately before it. The feed also has
     * 1dp dividers followed by 16dp nodes. There the node before the divider is another divider,
     * empty or as wide as a card, while in the bar it is a small part of the like button.
     * <p>
     * The last two widths of the thread, oldest first.
     */
    private static final ThreadLocal<float[]> previousWidths =
            ThreadLocal.withInitial(() -> new float[2]);

    private static Paint oldBarCountPaint;

    private static float exactDp(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, Dim.getMetrics());
    }

    private static boolean isDimension(float width, float dimension) {
        return Math.abs(width - dimension) < 1;
    }

    private static Paint oldBarCountPaint() {
        if (oldBarCountPaint == null) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(COUNT_TYPEFACE);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    oldBarCountTextSizeSp,
                    Utils.getContext().getResources().getDisplayMetrics()));
            oldBarCountPaint = paint;
        }
        return oldBarCountPaint;
    }

    private static int oldBarWidthOf(String count) {
        return oldBarCountStartMargin + OLD_BAR_COUNT_END_MARGIN
                + (int) Math.ceil(oldBarCountPaint().measureText(count));
    }

    /**
     * The count of the segmented button is drawn by the layout engine and not by the text view
     * beside it, so its color cannot be changed. The count drawn here takes the color of that
     * view instead, which is the one the engine draws with.
     */
    private static int oldBarCountColor(View host) {
        TextView text = barTextOf(barOf(host), 0);
        return text == null ? ThemeUtils.getAppForegroundColor() : text.getCurrentTextColor();
    }

    /**
     * @return The first text of the bar, which is the count YouTube shows for the likes.
     */
    @Nullable
    private static TextView barTextOf(View view, int depth) {
        if (view instanceof TextView text) {
            return text;
        }
        if (depth >= MAX_BAR_DEPTH || !(view instanceof ViewGroup group)) {
            return null;
        }
        for (int i = 0, childCount = group.getChildCount(); i < childCount; i++) {
            TextView text = barTextOf(group.getChildAt(i), depth + 1);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /**
     * YGEdge of the native layout engine.
     */
    private static final int YOGA_EDGE_LEFT = 0;
    private static final int YOGA_EDGE_RIGHT = 2;

    /**
     * Called from the injection point of each app.
     * <p>
     * Called for every Litho layout node that is given an exact width.
     *
     * @param nodePointer Pointer to the native node of the layout engine.
     */
    public static void onYogaSetWidth(long nodePointer, float width) {
        try {
            if (!RYD_ENABLED) {
                return;
            }
            ReturnYouTubeDislike videoData = currentVideoData();
            if (videoData == null) {
                return;
            }

            float[] previous = Objects.requireNonNull(previousWidths.get());
            final boolean afterSeparator =
                    previous[0] > OLD_BAR_BEFORE_SEPARATOR_MIN_WIDTH
                    && previous[0] < OLD_BAR_BEFORE_SEPARATOR_MAX_WIDTH
                    && isDimension(previous[1], OLD_BAR_SEPARATOR_EXACT_WIDTH);
            previous[0] = previous[1];
            previous[1] = width;

            if (!afterSeparator || !isDimension(width, oldBarDislikeIconExactWidth)) {
                return;
            }

            // Litho cannot be asked to lay out again, so a count that is not fetched by now gets
            // room for the widest one that can be shown.
            String dislikes = videoData.getFormattedDislikes();
            final int margin = oldBarWidthOf(dislikes != null ? dislikes
                    : SharedYouTubeSettings.RYD_DISLIKE_PERCENTAGE.get() ? "88.8%" : "8.8M");
            YogaNative.jni_YGNodeStyleSetMarginJNI(nodePointer,
                    Utils.isRightToLeftLocale() ? YOGA_EDGE_LEFT : YOGA_EDGE_RIGHT, margin);
            Logger.printDebug(() -> "Giving the dislike button a margin of " + margin);
            segmentedMarginGiven = true;
        } catch (Exception ex) {
            Logger.printException(() -> "onYogaSetWidth failure", ex);
        }
    }

    //
    // Icon only like and dislike buttons of the compact action bar.
    //

    private static final Typeface COUNT_TYPEFACE =
            Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private static final float ICON_BUTTON_COUNT_TEXT_SIZE_SP = 10;
    private static final int ICON_BUTTON_COUNT_BOTTOM_MARGIN = Dim.dp(3);
    private static final long ICON_BUTTON_FETCH_WAIT_MILLISECONDS = 5000;

    private static final long LIKES_HIDDEN = -1;
    private static final long LIKES_UNKNOWN = -2;

    /**
     * View tag Elements puts the accessibility id of a button in, such as "id.video.like.button".
     */
    private static final String ACCESSIBILITY_ID_TAG_NAME = "elements_accessibility_view_tag_id";
    private static final String LIKE_BUTTON_ACCESSIBILITY_ID = "id.video.like";
    private static final String DISLIKE_BUTTON_ACCESSIBILITY_ID = "id.video.dislike";

    private static final int accessibilityIdTag = ResourceUtils.getIdentifier(ResourceType.ID, ACCESSIBILITY_ID_TAG_NAME);

    /**
     * Set while this patch writes a description, since the hook is called again for it.
     */
    private static boolean rewritingDescription;

    /**
     * Litho recycles host views, so the counts are tracked per host and removed when it is reused.
     * Main thread only.
     */
    private static final Map<ComponentHost, IconButtonCountDrawable> iconButtonCounts = new WeakHashMap<>();

    @Nullable
    private static ReturnYouTubeDislike iconButtonPendingFetch;

    /**
     * @return The accessibility id of the button the host shows, or null if it is not a button.
     */
    @Nullable
    private static String accessibilityIdOf(View host) {
        Utils.verifyOnMainThread();
        if (accessibilityIdTag == 0) {
            return null;
        }
        Object tag = host.getTag(accessibilityIdTag);
        return tag == null ? null : tag.toString();
    }

    /**
     * Called from the injection point of each app.
     * <p>
     * Called on the main thread for every Litho host view, and with null when a recycled host is cleared.
     *
     * @return The description the host keeps, which for a dislike button includes the count.
     */
    @Nullable
    public static CharSequence onComponentHostContentDescription(ComponentHost host,
                                                                 @Nullable CharSequence description) {
        if (!RYD_ENABLED) {
            return description;
        }
        if (rewritingDescription) {
            return description;
        }
        try {
            IconButtonCountDrawable existing = iconButtonCounts.isEmpty()
                    ? null
                    : iconButtonCounts.get(host);

            String accessibilityId = description == null ? null : accessibilityIdOf(host);
            final boolean isLike = accessibilityId != null
                    && accessibilityId.startsWith(LIKE_BUTTON_ACCESSIBILITY_ID);
            final boolean isDislike = (accessibilityId != null
                    && accessibilityId.startsWith(DISLIKE_BUTTON_ACCESSIBILITY_ID))
                    || (accessibilityId == null && description != null
                    && existing != null && existing.untagged);

            if (!isLike && !isDislike) {
                if (existing != null) {
                    host.getOverlay().remove(existing);
                    iconButtonCounts.remove(host);
                }
                if (accessibilityId == null && description != null && segmentedMarginGiven) {
                    // Laid out and mounted only after this.
                    host.post(() -> addCountToUntaggedDislikeButton(host));
                }
                return description;
            }

            if (existing == null) {
                existing = new IconButtonCountDrawable(host);
                host.getOverlay().add(existing);
                iconButtonCounts.put(host, existing);
            }
            existing.setButton(description.toString(), isLike);
            Logger.printDebug(() -> "Button with a count: " + accessibilityId);
            refreshIconButtonCounts();

            // The caller stores what is returned, so a description set from here would be overwritten.
            String spoken = existing.spokenLabel;
            if (spoken != null) {
                return spoken;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostContentDescription failure", ex);
        }

        return description;
    }

    /**
     * If a dislike button was given room for its count, which the segmented button of YouTube Music
     * needs to be found, since it tags neither of its buttons.
     */
    private static volatile boolean segmentedMarginGiven;

    private static void addCountToUntaggedDislikeButton(ComponentHost host) {
        try {
            CharSequence description = host.getContentDescription();
            if (description == null || iconButtonCounts.containsKey(host)
                    || !isSegmentedDislikeButton(host)) {
                return;
            }

            IconButtonCountDrawable drawable = new IconButtonCountDrawable(host);
            drawable.untagged = true;
            host.getOverlay().add(drawable);
            iconButtonCounts.put(host, drawable);
            drawable.setButton(description.toString(), false);
            Logger.printDebug(() -> "Dislike button without a view tag");
            refreshIconButtonCounts();
        } catch (Exception ex) {
            Logger.printException(() -> "addCountToUntaggedDislikeButton failure", ex);
        }
    }

    /**
     * @return If the host is the dislike half of a segmented button: a single icon, widened by the
     *         margin, beside the like button with its animated icon. The like button is found by
     *         its place and not by the child order, which a like moves it to the end of.
     */
    private static boolean isSegmentedDislikeButton(ComponentHost host) {
        if (!host.isClickable() || host.getWidth() <= host.getHeight()
                || host.getChildCount() != 1 || !(host.getChildAt(0) instanceof ImageView)
                || !(host.getParent() instanceof ViewGroup parent)) {
            return false;
        }
        final boolean rightToLeft = Utils.isRightToLeftLocale();
        for (int i = 0, childCount = parent.getChildCount(); i < childCount; i++) {
            View sibling = parent.getChildAt(i);
            if (sibling == host) {
                continue;
            }
            final boolean before = rightToLeft
                    ? sibling.getLeft() >= host.getRight()
                    : sibling.getRight() <= host.getLeft();
            if (before && hasAnimatedIcon(sibling, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnimatedIcon(View view, int depth) {
        if (view.getClass().getName().equals("com.airbnb.lottie.LottieAnimationView")) {
            return true;
        }
        if (depth >= MAX_BAR_DEPTH || !(view instanceof ViewGroup group)) {
            return false;
        }
        for (int i = 0, childCount = group.getChildCount(); i < childCount; i++) {
            if (hasAnimatedIcon(group.getChildAt(i), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    public static void invalidateIconButtonCounts() {
        for (IconButtonCountDrawable drawable : iconButtonCounts.values()) {
            drawable.refresh();
        }
    }

    /**
     * YouTube gives the like button no description when the creator hides the likes, so the hook
     * never sees it. It is the button laid out just before the dislike button.
     */
    private static void addCountToUnlabeledLikeButton(ComponentHost dislikeHost) {
        try {
            ComponentHost likeHost = buttonBefore(dislikeHost);
            if (likeHost == null || iconButtonCounts.containsKey(likeHost)
                    || !TextUtils.isEmpty(likeHost.getContentDescription())) {
                return;
            }
            String accessibilityId = accessibilityIdOf(likeHost);
            if (accessibilityId != null && !accessibilityId.startsWith(LIKE_BUTTON_ACCESSIBILITY_ID)) {
                return;
            }

            IconButtonCountDrawable drawable = new IconButtonCountDrawable(likeHost);
            likeHost.getOverlay().add(drawable);
            iconButtonCounts.put(likeHost, drawable);
            drawable.setButton("", true);
            Logger.printDebug(() -> "Like button without a description: " + accessibilityId);
            refreshIconButtonCounts();
        } catch (Exception ex) {
            Logger.printException(() -> "addCountToUnlabeledLikeButton failure", ex);
        }
    }

    /**
     * Each button of the compact action bar sits in wrappers of its own width, so the first
     * ancestor with an earlier sibling of that width holds the previous button.
     */
    @Nullable
    private static ComponentHost buttonBefore(View button) {
        View view = button;
        for (int i = 0; i < MAX_BAR_PARENTS; i++) {
            if (!(view.getParent() instanceof ViewGroup parent)) {
                return null;
            }
            final int index = parent.indexOfChild(view);
            if (index > 0) {
                View sibling = parent.getChildAt(index - 1);
                return sibling.getWidth() == view.getWidth()
                        ? clickableHostOfSize(sibling, button, 0)
                        : null;
            }
            view = parent;
        }
        return null;
    }

    @Nullable
    private static ComponentHost clickableHostOfSize(View view, View button, int depth) {
        if (view instanceof ComponentHost host && host.isClickable()
                && host.getWidth() == button.getWidth() && host.getHeight() == button.getHeight()) {
            return host;
        }
        if (depth >= MAX_BAR_DEPTH || !(view instanceof ViewGroup group)) {
            return null;
        }
        for (int i = 0, childCount = group.getChildCount(); i < childCount; i++) {
            ComponentHost host = clickableHostOfSize(group.getChildAt(i), button, depth + 1);
            if (host != null) {
                return host;
            }
        }
        return null;
    }

    /**
     * Redraws the counts now, and again once the fetch completes if it is still loading.
     */
    public static void refreshIconButtonCounts() {
        if (iconButtonCounts.isEmpty()) {
            return;
        }
        invalidateIconButtonCounts();

        ReturnYouTubeDislike videoData = currentVideoData();
        if (videoData == null || videoData.fetchCompleted() || videoData == iconButtonPendingFetch) {
            return;
        }
        iconButtonPendingFetch = videoData;
        Utils.runOnBackgroundThread(() -> {
            videoData.getFetchData(ICON_BUTTON_FETCH_WAIT_MILLISECONDS);
            Utils.runOnMainThread(() -> {
                if (iconButtonPendingFetch == videoData) {
                    iconButtonPendingFetch = null;
                }
                invalidateIconButtonCounts();
            });
        });
    }

    /**
     * Reads the like count from a label such as "like this video along with 2,589 other people".
     *
     * @return The count, {@link #LIKES_HIDDEN} if the label has no number (hidden by the creator),
     *         or {@link #LIKES_UNKNOWN} if the number is not a whole count, such as a compact "2,5K".
     */
    private static long parseLabelCount(String label) {
        final int length = label.length();
        int index = 0;
        while (index < length && !Character.isDigit(label.charAt(index))) {
            index++;
        }
        if (index == length) {
            return LIKES_HIDDEN;
        }

        long count = 0;
        int digitsSinceSeparator = 0;
        boolean hasSeparator = false;
        for (; index < length; index++) {
            final char c = label.charAt(index);
            if (Character.isDigit(c)) {
                if (count > Long.MAX_VALUE / 10) {
                    return LIKES_UNKNOWN;
                }
                count = count * 10 + Character.digit(c, 10);
                digitsSinceSeparator++;
                continue;
            }
            // Swiss style grouping uses either apostrophe.
            final boolean isSeparator = c == ',' || c == '.' || c == '\'' || c == '’'
                    || Character.isSpaceChar(c);
            if (!isSeparator || index + 1 >= length || !Character.isDigit(label.charAt(index + 1))) {
                break;
            }
            // Grouping separators always split groups of three, anything else is a decimal.
            if (hasSeparator && digitsSinceSeparator != 3) {
                return LIKES_UNKNOWN;
            }
            hasSeparator = true;
            digitsSinceSeparator = 0;
        }

        if (hasSeparator && digitsSinceSeparator != 3) {
            return LIKES_UNKNOWN;
        }
        return count;
    }

    private static final int MAX_BAR_PARENTS = 5;
    private static final int MAX_BAR_DEPTH = 6;

    /**
     * The counts of the old action bar sit in the like button, a neighbor of the dislike button,
     * and Litho reports the text of one host only, so the whole bar is searched.
     *
     * @return If anything in the bar holding this button shows text, such as a count or a label.
     */
    private static boolean barShowsText(View host) {
        View bar = barOf(host);
        return bar == host ? hostShowsText(host) : subtreeShowsText(bar, 0);
    }

    /**
     * The height is used rather than the width, since the dislike button is no longer square.
     *
     * @return The bar or pill holding this button, or the button itself if it was not found.
     */
    private static View barOf(View host) {
        final int barWidth = 2 * host.getHeight();
        View view = host;

        for (int i = 0; i < MAX_BAR_PARENTS; i++) {
            ViewParent parent = view.getParent();
            if (!(parent instanceof View parentView)) {
                break;
            }
            view = parentView;
            // The first parent wider than the button is the bar or the pill holding it.
            if (view.getWidth() >= barWidth) {
                return view;
            }
        }

        return host;
    }

    private static boolean subtreeShowsText(View view, int depth) {
        if (hostShowsText(view)) {
            return true;
        }
        if (depth >= MAX_BAR_DEPTH || !(view instanceof ViewGroup group)) {
            return false;
        }
        for (int i = 0, childCount = group.getChildCount(); i < childCount; i++) {
            if (subtreeShowsText(group.getChildAt(i), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return If the Litho host has mounted any text, which needs the unobfuscated Litho classes
     *         since the extension cannot compile against them.
     */
    private static boolean hostShowsText(View view) {
        if (!(view instanceof ComponentHost host)) {
            return false;
        }
        try {
            TextContent textContent = host.getTextContent();
            if (textContent == null) {
                return false;
            }
            return !textContent.getTextItems().isEmpty();
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the text of: " + host);
            return false;
        }
    }

    /**
     * Draws the count below the icon of a compact action bar button, and beside the icon of the
     * old action bar dislike button, in the margin {@link #onYogaSetWidth(long, float)} gave it.
     */
    private static final class IconButtonCountDrawable extends Drawable {
        private final ComponentHost host;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 255;
        private String label = "";
        @Nullable
        private String spokenLabel;
        private boolean isLike;
        @Nullable
        private Boolean hasOwnLabel;
        /**
         * Like count from the label, {@link #LIKES_HIDDEN} or {@link #LIKES_UNKNOWN}.
         */
        private long youTubeLikes;
        /**
         * If the like button beside this dislike button was looked for, which needs the layout.
         */
        private boolean likeButtonSearched;
        /**
         * If the button was found by its place and not by its view tag.
         */
        private boolean untagged;

        IconButtonCountDrawable(ComponentHost host) {
            this.host = host;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(COUNT_TYPEFACE);
        }

        /**
         * @return If the count goes beside the icon, which only the dislike button of the old
         *         action bar has room for. Every other button is square.
         */
        private boolean drawsBeside() {
            return !isLike && hasOwnLabel() && host.getWidth() > host.getHeight();
        }

        void setButton(String label, boolean isLike) {
            this.label = label;
            this.isLike = isLike;
            youTubeLikes = isLike ? parseLabelCount(label) : LIKES_UNKNOWN;
            hasOwnLabel = null;
            spokenLabel = null;
            likeButtonSearched = false;
        }

        /**
         * @return If the bar already shows the counts, which tablets and the old action bar do.
         *         Litho mounts the text after the description, so this is answered on the first draw.
         */
        private boolean hasOwnLabel() {
            Boolean cached = hasOwnLabel;
            if (cached == null) {
                hasOwnLabel = cached = barShowsText(host);
            }
            return cached;
        }

        void refresh() {
            setBounds(0, 0, host.getWidth(), host.getHeight());
            updateSpokenLabel();
            invalidateSelf();
        }

        /**
         * @return Where to center the count, in the space the margin freed. The insets the
         *         button had while it was square cancel out, leaving half of the icon and the gap.
         */
        private float countCenterX() {
            final int start = oldBarDislikeIconWidth
                    + oldBarCountStartMargin - OLD_BAR_COUNT_END_MARGIN;
            return (host.getWidth() + (Utils.isRightToLeftLocale() ? -start : start)) / 2f;
        }

        /**
         * YouTube tells the like count to screen readers but never the dislike count,
         * and the number drawn here is not something a screen reader can see.
         */
        private void updateSpokenLabel() {
            if (isLike) {
                return;
            }
            String dislikes = getText();
            if (dislikes == null) {
                return;
            }

            // YouTube says the likes of a video as "like this video along with 1,406 other
            // people", and the dislikes are said the same way so that a screen reader announces
            // both buttons alike.  A percentage is not a count of people and keeps its own wording.
            String spoken = str((SharedYouTubeSettings.RYD_DISLIKE_PERCENTAGE.get()
                    ? "morphe_ryd_accessibility_dislike_percentage"
                    : "morphe_ryd_accessibility_dislike_count"), dislikes);
            if (spoken.equals(spokenLabel) && TextUtils.equals(host.getContentDescription(), spoken)) {
                return;
            }

            spokenLabel = spoken;
            rewritingDescription = true;
            try {
                host.setContentDescription(spoken);
            } finally {
                rewritingDescription = false;
            }
        }

        @Nullable
        private String getText() {
            ReturnYouTubeDislike videoData = currentVideoData();
            if (isLike && youTubeLikes >= 0) {
                // The label counts the other people who liked, which is one more
                // than the like count YouTube shows, and it leaves out the user's own like.
                long likes = youTubeLikes - 1;
                // The button keeps no state of its own, so only a vote of this session is known.
                if (videoData != null && videoData.isLikedByUser()) {
                    likes++;
                }
                return ReturnYouTubeDislike.formatLikeCount(likes);
            }
            if (videoData == null) {
                return null;
            }
            if (isLike) {
                return youTubeLikes == LIKES_HIDDEN && !SharedYouTubeSettings.RYD_ESTIMATED_LIKE.get()
                        ? null
                        : videoData.getFormattedLikes();
            }
            return videoData.getFormattedDislikes();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            // In case a recycled host was given a new description without passing through the hook.
            // A like button without a description is given an empty label.
            CharSequence current = host.getContentDescription();
            if (current == null) {
                current = "";
            }
            final boolean beside = drawsBeside();
            if (!isLike && !beside && !likeButtonSearched && host.getWidth() > 0) {
                likeButtonSearched = true;
                // Not while drawing, since this adds to the overlay of another view.
                host.post(() -> addCountToUnlabeledLikeButton(host));
            }
            if ((!TextUtils.equals(current, label) && !TextUtils.equals(current, spokenLabel))
                    || (hasOwnLabel() && !beside)) {
                return;
            }
            String text = getText();
            if (text == null) {
                return;
            }
            // A count beside the icon can be as large as the likes YouTube shows,
            // while one below it must stay small.
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    beside ? oldBarCountTextSizeSp : ICON_BUTTON_COUNT_TEXT_SIZE_SP,
                    host.getResources().getDisplayMetrics()));
            final int color = beside
                    ? oldBarCountColor(host)
                    : ThemeUtils.getAppForegroundColor();
            paint.setColor(color);
            paint.setAlpha(Color.alpha(color) * alpha / 255);

            if (beside) {
                Paint.FontMetrics fm = paint.getFontMetrics();
                canvas.drawText(text, countCenterX(),
                        (host.getHeight() - fm.bottom - fm.top) / 2f, paint);
                return;
            }
            canvas.drawText(text, host.getWidth() / 2f,
                    host.getHeight() - ICON_BUTTON_COUNT_BOTTOM_MARGIN, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
