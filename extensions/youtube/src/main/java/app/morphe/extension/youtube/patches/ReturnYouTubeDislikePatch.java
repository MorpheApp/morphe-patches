/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3075
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislike.Vote;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;
import com.facebook.yoga.YogaNative;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislike;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * Handles all interaction of UI patch components.
 */
@SuppressWarnings("unused")
public class ReturnYouTubeDislikePatch {

    private static final Boolean RYD_ENABLED = Settings.RYD_ENABLED.get();

    /**
     * RYD data for the current video on screen.
     */
    @Nullable
    private static volatile ReturnYouTubeDislike currentVideoData;

    /**
     * Last video ID prefetched. Field is to prevent prefetching the same video ID multiple times in a row.
     */
    @Nullable
    private static volatile String lastPrefetchedVideoId;

    private static void clearData() {
        currentVideoData = null;

        // Rolling number text should not be cleared,
        // as it's used if incognito Short is opened/closed
        // while a regular video is on screen.
    }

    //
    // Litho player for both regular videos and Shorts.
    //

    /**
     * Injection point.
     * <p>
     * Logs if new litho text layout is used.
     */
    public static boolean useNewLithoTextCreation(boolean useNewLithoTextCreation) {
        // Don't force flag on/off unless debugging patch hooks,
        // because forcing off with newer YT targets causes Shorts player to show no buttons,
        // presumably because the old litho data isn't in the layout data.
        Logger.printDebug(() -> "useNewLithoTextCreation: " + useNewLithoTextCreation);
        return useNewLithoTextCreation;
    }

    /**
     * Injection point.
     * <p>
     * Called when a litho text component is created, and also when a Span is later reused
     * (such as scrolling off and back on screen).  Usually called off the main thread, and
     * can be called several times for the same element.
     * <p>
     * Only a segmented button that YouTube lays out itself is handled here.  When the old action
     * bar is restored the dislike count is drawn over the button instead.
     *
     * @param original Original char sequence created or reused by Litho.
     * @return The original char sequence, or a replacement that contains the dislikes.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface,
                                                 CharSequence original) {
        try {
            if (!RYD_ENABLED || OLD_ACTION_BAR_ENABLED) {
                return original;
            }

            StringBuilder pathBuilder = contextInterface.patch_getPathBuilder();
            if (!pathBuilder.toString().contains("segmented_like_dislike_button.e")) {
                return original;
            }

            ReturnYouTubeDislike videoData = currentVideoData;
            if (videoData == null) {
                return original; // User enabled RYD while a video was on screen.
            }
            if (!(original instanceof Spanned)) {
                original = new SpannableString(original);
            }
            return videoData.getDislikesSpanForRegularVideo((Spanned) original, true, false);
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }
        return original;
    }

    //
    // Dislike button width of the old action bar.
    //

    /**
     * The segmented button of the old action bar has a count for the likes only, so the dislike
     * count is drawn over the button and the button is given room for it.
     */
    private static final boolean OLD_ACTION_BAR_ENABLED =
            RYD_ENABLED && Settings.RESTORE_OLD_VIDEO_ACTION_BAR.get();

    private static final int OLD_BAR_DISLIKE_ICON_WIDTH = Dim.dp16;
    private static final int OLD_BAR_SEPARATOR_WIDTH = Dim.dp1;
    private static final float OLD_BAR_COUNT_TEXT_SIZE_SP = 12;
    private static final int OLD_BAR_COUNT_SIDE_MARGIN = Dim.dp4;

    /**
     * Nothing identifies the button, since the whole bar is a single Litho component, so it is
     * found by the separator that is always laid out immediately before it.
     */
    private static final ThreadLocal<Boolean> previousWidthWasSeparator = new ThreadLocal<>();

    private static Paint oldBarCountPaint;

    private static boolean isDimension(float width, int dimension) {
        return Math.abs(width - dimension) < 1;
    }

    private static Paint oldBarCountPaint() {
        if (oldBarCountPaint == null) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(COUNT_TYPEFACE);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    OLD_BAR_COUNT_TEXT_SIZE_SP,
                    Utils.getContext().getResources().getDisplayMetrics()));
            oldBarCountPaint = paint;
        }
        return oldBarCountPaint;
    }

    private static int oldBarWidthOf(String count) {
        return 2 * OLD_BAR_COUNT_SIDE_MARGIN
                + (int) Math.ceil(oldBarCountPaint().measureText(count));
    }

    /**
     * The count of the segmented button is drawn by the layout engine and not by the text view
     * beside it, so its color cannot be changed.  The count drawn here takes the color of that
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
     * Injection point.
     * <p>
     * Called for every Litho layout node that is given an exact width.
     *
     * @param nodePointer Pointer to the native node of the layout engine.
     */
    public static void onYogaSetWidth(long nodePointer, float width) {
        try {
            if (!OLD_ACTION_BAR_ENABLED) {
                return;
            }
            ReturnYouTubeDislike videoData = currentVideoData;
            if (videoData == null) {
                return;
            }

            final boolean afterSeparator = Boolean.TRUE.equals(previousWidthWasSeparator.get());
            previousWidthWasSeparator.set(isDimension(width, OLD_BAR_SEPARATOR_WIDTH));

            if (!afterSeparator || !isDimension(width, OLD_BAR_DISLIKE_ICON_WIDTH)) {
                return;
            }

            // Litho cannot be asked to lay out again, so a count that is not fetched by now gets
            // room for the widest one that can be shown.
            String dislikes = videoData.getFormattedDislikes();
            final int margin = oldBarWidthOf(dislikes != null ? dislikes
                    : Settings.RYD_DISLIKE_PERCENTAGE.get() ? "88.8%" : "8.8M");
            YogaNative.jni_YGNodeStyleSetMarginJNI(nodePointer,
                    Utils.isRightToLeftLocale() ? YOGA_EDGE_LEFT : YOGA_EDGE_RIGHT, margin);
            Logger.printDebug(() -> "Giving the dislike button a margin of " + margin);
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
     * Injection point.
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
            final boolean isDislike = accessibilityId != null
                    && accessibilityId.startsWith(DISLIKE_BUTTON_ACCESSIBILITY_ID);

            if (!isLike && !isDislike) {
                if (existing != null) {
                    host.getOverlay().remove(existing);
                    iconButtonCounts.remove(host);
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

    private static void invalidateIconButtonCounts() {
        for (IconButtonCountDrawable drawable : iconButtonCounts.values()) {
            drawable.refresh();
        }
    }

    /**
     * Redraws the counts now, and again once the fetch completes if it is still loading.
     */
    private static void refreshIconButtonCounts() {
        if (iconButtonCounts.isEmpty()) {
            return;
        }
        invalidateIconButtonCounts();

        ReturnYouTubeDislike videoData = currentVideoData;
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

        IconButtonCountDrawable(ComponentHost host) {
            this.host = host;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(COUNT_TYPEFACE);
        }

        /**
         * @return If the count goes beside the icon, which only the dislike button of the old
         *         action bar has room for.  Every other button is square.
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
         * @return Where to center the count, in the space the margin freed.  The insets the
         *         button had while it was square cancel out, leaving half of the icon.
         */
        private float countCenterX() {
            final int icon = Utils.isRightToLeftLocale()
                    ? -OLD_BAR_DISLIKE_ICON_WIDTH
                    : OLD_BAR_DISLIKE_ICON_WIDTH;
            return (host.getWidth() + icon) / 2f;
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
            String spoken = str((Settings.RYD_DISLIKE_PERCENTAGE.get()
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
            ReturnYouTubeDislike videoData = currentVideoData;
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
                return youTubeLikes == LIKES_HIDDEN && !Settings.RYD_ESTIMATED_LIKE.get()
                        ? null
                        : videoData.getFormattedLikes();
            }
            return videoData.getFormattedDislikes();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            // In case a recycled host was given a new description without passing through the hook.
            CharSequence current = host.getContentDescription();
            final boolean beside = drawsBeside();
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
                    beside ? OLD_BAR_COUNT_TEXT_SIZE_SP : ICON_BUTTON_COUNT_TEXT_SIZE_SP,
                    host.getResources().getDisplayMetrics()));
            final int color = beside
                    ? oldBarCountColor(host)
                    : ThemeUtils.getAppForegroundColor();
            paint.setColor(color);
            paint.setAlpha(Color.alpha(color) * alpha / 255);

            if (beside) {
                TextView likeTextView = barTextOf(barOf(host), 0);
                final float baselineY;
                if (likeTextView != null && likeTextView.getBaseline() != -1 && likeTextView.isAttachedToWindow()) {
                    int[] textLoc = new int[2];
                    int[] hostLoc = new int[2];
                    likeTextView.getLocationInWindow(textLoc);
                    host.getLocationInWindow(hostLoc);
                    baselineY = (textLoc[1] + likeTextView.getBaseline()) - hostLoc[1];
                } else {
                    Paint.FontMetrics fm = paint.getFontMetrics();
                    baselineY = (host.getHeight() - fm.bottom - fm.top) / 2f;
                }
                canvas.drawText(text, countCenterX(), baselineY, paint);
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

    //
    // Video ID and voting hooks (all players).
    //

    /**
     * Injection point.  Uses 'playback response' video ID hook to preload RYD.
     */
    public static void preloadVideoId(String videoId, boolean isShortAndOpeningOrPlaying) {
        try {
            if (!RYD_ENABLED) {
                return;
            }
            if (videoId.equals(lastPrefetchedVideoId)) {
                return;
            }
            if (!Utils.isNetworkConnected()) {
                Logger.printDebug(() -> "Cannot pre-fetch RYD, network is not connected");
                lastPrefetchedVideoId = null;
                return;
            }

            // Shorts shelf in home and subscription feed causes player response hook to be called,
            // and the 'is opening/playing' parameter will be false.
            //
            // Do not load RYD for any Shorts, including Shorts viewed in the regular player.
            // In June 2026 YouTube removed the dislike button from the Shorts player.
            // As of 2026/07/01, RYD’s like/dislike estimates for Shorts are potentially incorrect
            // because the RYD API is still accepting Shorts like/dislike submissions.
            //
            // Since users cannot dislike content in the Shorts player, the RYD estimates
            // may be heavily or entirely biased toward "everyone likes this Short",
            // making the estimated dislikes at best unreliable and at worst completely wrong.
            if (VideoInformation.lastPlayerResponseIsShort()) {
                Logger.printDebug(() -> "Ignoring short video ID: " + videoId);
                lastPrefetchedVideoId = videoId;
                return;
            }

            Logger.printDebug(() -> "Prefetching RYD for video: " + videoId);
            ReturnYouTubeDislike fetch = ReturnYouTubeDislike.getFetchForVideoId(videoId);

            lastPrefetchedVideoId = videoId;
        } catch (Exception ex) {
            Logger.printException(() -> "preloadVideoId failure", ex);
        }
    }

    /**
     * Injection point. Uses 'current playing' video ID hook. Always called on main thread.
     */
    public static void newVideoLoaded(String videoId) {
        try {
            if (!RYD_ENABLED) return;
            if (videoId == null || videoId.isBlank()) {
                Logger.printDebug(() -> "Ignoring blank videoId");
                return;
            }

            PlayerType currentPlayerType = PlayerType.getCurrent();
            if (currentPlayerType.isNoneHiddenOrSlidingMinimized()) {
                // Must clear here, otherwise the wrong data can be used for a minimized regular video.
                clearData();
                return;
            }

            if (videoIdIsSame(currentVideoData, videoId)) {
                return;
            }
            Logger.printDebug(() -> "New video ID: " + videoId + " playerType: " + currentPlayerType);

            if (!Utils.isNetworkConnected()) {
                Logger.printDebug(() -> "Cannot fetch RYD, network is not connected");
                currentVideoData = null;
                return;
            }

            // Do not fetch if missing, so Shorts in regular player don't show bogus Shorts data.
            currentVideoData = ReturnYouTubeDislike.getFetchForVideoIdOrNull(videoId);
            // The compact bar can mount before the video ID is known.
            refreshIconButtonCounts();
        } catch (Exception ex) {
            Logger.printException(() -> "newVideoLoaded failure", ex);
        }
    }

    private static boolean videoIdIsSame(@Nullable ReturnYouTubeDislike fetch, @Nullable String videoId) {
        return (fetch == null && videoId == null)
                || (fetch != null && fetch.getVideoId().equals(videoId));
    }

    /**
     * Injection point.
     * <p>
     * Called when the user likes or dislikes.
     *
     * @param endpoint      string that matches {@link Vote#endpoint}
     * @param videoId       video ID included in the endpoint request body
     */
    public static void sendVote(String endpoint, String videoId) {
        try {
            if (!RYD_ENABLED) {
                return;
            }
            if (!Utils.isNotEmpty(videoId)) {
                Logger.printDebug(() -> "Ignore playlist votes");
                return;
            }

            if (PlayerType.getCurrent().isNoneHiddenOrMinimized()) {
                return;
            }

            ReturnYouTubeDislike videoData = currentVideoData;
            if (videoData == null) {
                Logger.printDebug(() -> "Cannot send vote, as current video data is null");
                return; // User enabled RYD while a regular video was minimized.
            } else if (!videoIdIsSame(videoData, videoId)) {
                Logger.printDebug(() -> "Cannot vote for video, as video id does not match"
                        + " videoData: " + videoData.getVideoId() + ", endPoint: " + videoId);
                return;
            }

            for (Vote v : Vote.values()) {
                if (v.endpoint.equals(endpoint)) {
                    videoData.sendVote(v);
                    invalidateIconButtonCounts();
                    return;
                }
            }

            Logger.printException(() -> "Unknown endpoint: " + endpoint);
        } catch (Exception ex) {
            Logger.printException(() -> "sendVote failure", ex);
        }
    }
}
