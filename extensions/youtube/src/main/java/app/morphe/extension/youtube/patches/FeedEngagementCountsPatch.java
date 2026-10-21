/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3786
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislike;
import app.morphe.extension.shared.returnyoutubedislike.requests.RYDVoteData;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Shows the like count and the like ratio of feed videos at the right side of their thumbnails.
 * Part of the Return YouTube Dislike patch.
 * <p>
 * Feed videos do not include their like count, so it's fetched from the Return YouTube Dislike API,
 * with the same cache as the player. A video is fetched when its card is laid out, and the counts
 * fade in once the fetch completes.
 * The counts are not clickable, so tapping them opens the video.
 */
@SuppressWarnings("unused")
public final class FeedEngagementCountsPatch {

    private static final boolean RYD_ENABLED = Settings.RYD_ENABLED.get();
    private static final boolean LIKE_COUNT = RYD_ENABLED && Settings.RYD_FEED_LIKE_COUNT.get();
    private static final boolean LIKE_RATIO = RYD_ENABLED && Settings.RYD_FEED_LIKE_RATIO.get();
    public static final boolean ENABLED = LIKE_COUNT || LIKE_RATIO;

    /** Longest wait for a fetch, which is done off the main thread. */
    private static final long FETCH_TIMEOUT_MS = 10_000;
    /** Time between checks of a card that is not visible yet. */
    private static final long HIDDEN_RETRY_DELAY_MS = 400;
    /** Checks of a card that is not visible, before its fetch waits for the next layout. */
    private static final int MAX_HIDDEN_RETRIES = 10;

    /** Top margin of a thumbnail that is too small to fit the badge above the duration. */
    private static final int MARGIN_DP = 10;
    /** Same as the duration badge and the Watch later button, so their right edges line up. */
    private static final int RIGHT_MARGIN_DP = 8;
    /** Space between the badge and the duration badge below it. */
    private static final int DURATION_GAP_DP = 4;
    /** Height of the duration badge, which is 20 dp and 8 dp above the bottom, plus a gap. */
    private static final int ABOVE_DURATION_DP = 8 + 20 + DURATION_GAP_DP;
    private static final int PADDING_DP = 6;
    private static final int CORNER_RADIUS_DP = 8;
    private static final int ICON_DP = 16;
    private static final int GAP_DP = 3;
    private static final int BAR_HEIGHT_DP = 3;
    private static final int MIN_WIDTH_DP = 34;
    private static final float COUNT_TEXT_SP = 11f;
    private static final float RATIO_TEXT_SP = 10f;

    /** A little darker than the duration badge, for the smaller texts. */
    private static final int BACKGROUND_COLOR = 0xA6000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int LIKE_BAR_COLOR = 0xFF2BA640;
    private static final int DISLIKE_BAR_COLOR = 0xFFE53935;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** Root hosts that show the counts of a video. Used only on the main thread. */
    private static final Map<ComponentHost, CountsBadge> badges = new WeakHashMap<>();

    @Nullable
    private static Drawable likeIcon;
    private static boolean likeIconLoaded;

    @Nullable
    private static NumberFormat percentFormat;

    private FeedEngagementCountsPatch() {
    }

    @Nullable
    private static Drawable getLikeIcon() {
        if (!likeIconLoaded) {
            likeIconLoaded = true;
            Drawable icon = ResourceUtils.getDrawable("yt_outline_thumb_up_black_24");
            if (icon == null) {
                icon = ResourceUtils.getDrawable("yt_delhi_thumbs_up_not_filled_24dp");
            }
            if (icon != null) {
                icon = icon.mutate();
                icon.setColorFilter(new PorterDuffColorFilter(TEXT_COLOR, PorterDuff.Mode.SRC_IN));
            }
            likeIcon = icon;
        }
        return likeIcon;
    }

    private static String formatPercent(float fraction) {
        if (percentFormat == null) {
            percentFormat = NumberFormat.getPercentInstance();
            percentFormat.setMaximumFractionDigits(0);
        }
        return percentFormat.format(fraction);
    }

    /**
     * Called by {@link FeedVideoCardsPatch} on the main thread after a root host is laid out.
     *
     * @param videoId The video of the card, or null if the host is not a video card
     *                or its video is not known.
     */
    public static void onCardLaidOut(ComponentHost host, @Nullable String videoId) {
        if (!ENABLED) {
            return;
        }
        if (videoId != null && FeedVideoCardsPatch.isInDialog(host)) {
            videoId = null;
        }
        CountsBadge badge = badges.get(host);
        if (videoId == null) {
            if (badge != null) {
                badge.detach(host);
                badges.remove(host);
            }
            return;
        }
        if (badge == null) {
            badge = new CountsBadge(host);
            badges.put(host, badge);
        }
        badge.bind(host, videoId);
    }

    /**
     * Dark rounded badge with the like count and the like ratio, drawn on the overlay of the card.
     */
    private static final class CountsBadge extends Drawable implements View.OnAttachStateChangeListener {
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ratioPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;
        private final WeakReference<ComponentHost> hostRef;

        @Nullable
        private String videoId;
        @Nullable
        private String countText;
        @Nullable
        private String ratioText;
        private float likeFraction = -1;
        private boolean added;
        @Nullable
        private Runnable pendingFetch;
        private boolean fetching;
        private int alpha = 255;
        @Nullable
        private ValueAnimator fadeIn;
        /**
         * False after the card was detached, such as when the feed reuses it for another video.
         * Nothing is drawn until the card is checked again, so the counts of the previous video
         * are not shown on the next one.
         */
        private boolean confirmed = true;

        CountsBadge(ComponentHost host) {
            hostRef = new WeakReference<>(host);
            host.addOnAttachStateChangeListener(this);
            density = host.getResources().getDisplayMetrics().density;
            final float scaledDensity = density * host.getResources().getConfiguration().fontScale;

            backgroundPaint.setColor(BACKGROUND_COLOR);

            countPaint.setColor(TEXT_COLOR);
            countPaint.setTextAlign(Paint.Align.CENTER);
            countPaint.setTypeface(Typeface.DEFAULT_BOLD);
            countPaint.setTextSize(COUNT_TEXT_SP * scaledDensity);

            ratioPaint.setColor(TEXT_COLOR);
            ratioPaint.setTextAlign(Paint.Align.CENTER);
            ratioPaint.setTextSize(RATIO_TEXT_SP * scaledDensity);
        }

        private int px(float dp) {
            return Math.round(dp * density);
        }

        void bind(ComponentHost host, String newVideoId) {
            confirmed = true;
            if (!newVideoId.equals(videoId)) {
                videoId = newVideoId;
                countText = null;
                ratioText = null;
                likeFraction = -1;
                fetching = false;
                cancelPendingFetch();
                endFadeIn();
            }

            if (!hasContent()) {
                ReturnYouTubeDislike cached = ReturnYouTubeDislike.getFetchForVideoIdOrNull(newVideoId);
                RYDVoteData data = cached == null || !cached.fetchCompleted() ? null : cached.getFetchData(0);
                if (data != null) {
                    setData(data);
                } else {
                    // Also when the card is laid out again, such as after returning to the app,
                    // so a fetch that was skipped or failed is tried again.
                    requestFetch(host, newVideoId, 0);
                }
            }
            place(host);
        }

        /**
         * Fetches the video now, or once the card is visible.
         */
        private void requestFetch(ComponentHost host, String fetchVideoId, int attempt) {
            if (pendingFetch != null || fetching) {
                return;
            }
            if (host.isShown()) {
                fetch(host, fetchVideoId);
                return;
            }
            // The app is not visible yet, such as while it returns from the background.
            if (attempt >= MAX_HIDDEN_RETRIES) {
                // A later layout of the card requests it again.
                return;
            }
            Runnable retry = () -> {
                pendingFetch = null;
                if (fetchVideoId.equals(videoId) && host.isAttachedToWindow()) {
                    requestFetch(host, fetchVideoId, attempt + 1);
                }
            };
            pendingFetch = retry;
            handler.postDelayed(retry, HIDDEN_RETRY_DELAY_MS);
        }

        private void cancelPendingFetch() {
            if (pendingFetch != null) {
                handler.removeCallbacks(pendingFetch);
                pendingFetch = null;
            }
        }

        private void fetch(ComponentHost host, String fetchVideoId) {
            fetching = true;
            ReturnYouTubeDislike fetch = ReturnYouTubeDislike.getFetchForVideoId(fetchVideoId);
            Utils.runOnBackgroundThread(() -> {
                RYDVoteData data = fetch.getFetchData(FETCH_TIMEOUT_MS);
                Utils.runOnMainThread(() -> {
                    try {
                        // The card may show another video now.
                        if (!fetchVideoId.equals(videoId) || badges.get(host) != this) {
                            return;
                        }
                        fetching = false;
                        if (data != null) {
                            setData(data);
                            startFadeIn();
                            place(host);
                        }
                    } catch (Exception ex) {
                        Logger.printException(() -> "Feed counts update failure", ex);
                    }
                });
            });
        }

        private void startFadeIn() {
            endFadeIn();
            ValueAnimator animator = ValueAnimator.ofInt(0, 255);
            // Counts already in the cache are shown at once.
            animator.setDuration(FeedVideoCardsPatch.BADGE_FADE_IN_MS);
            animator.addUpdateListener(animation -> setAlpha((int) animation.getAnimatedValue()));
            fadeIn = animator;
            setAlpha(0);
            animator.start();
        }

        /**
         * Ends the fade in, so the counts are fully shown.
         */
        private void endFadeIn() {
            if (fadeIn != null) {
                ValueAnimator animator = fadeIn;
                fadeIn = null;
                animator.end();
            }
        }

        private void setData(RYDVoteData data) {
            countText = LIKE_COUNT ? ReturnYouTubeDislike.formatLikeCount(data.getLikeCount()) : null;

            final long votes = data.getLikeCount() + data.getDislikeCount();
            final float fraction = data.getLikePercentage();
            if (LIKE_RATIO && votes > 0 && fraction >= 0 && fraction <= 1) {
                likeFraction = fraction;
                ratioText = formatPercent(fraction);
            } else {
                likeFraction = -1;
                ratioText = null;
            }
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        private boolean hasContent() {
            return countText != null || ratioText != null;
        }

        /**
         * Places the badge on the right side of the thumbnail, just above the duration.
         */
        void place(ComponentHost host) {
            if (!hasContent()) {
                // Nothing to show yet, or the fetch failed. A pending fetch is kept.
                removeFromOverlay(host);
                return;
            }

            final int padding = px(PADDING_DP);
            final int gap = px(GAP_DP);
            Paint.FontMetrics countMetrics = countPaint.getFontMetrics();
            Paint.FontMetrics ratioMetrics = ratioPaint.getFontMetrics();

            float contentWidth = px(ICON_DP);
            float contentHeight = 0;
            if (countText != null) {
                contentWidth = Math.max(contentWidth, countPaint.measureText(countText));
                contentHeight += px(ICON_DP) + gap + (countMetrics.descent - countMetrics.ascent);
            }
            if (ratioText != null) {
                contentWidth = Math.max(contentWidth, ratioPaint.measureText(ratioText));
                if (contentHeight > 0) {
                    contentHeight += gap * 2;
                }
                contentHeight += px(BAR_HEIGHT_DP) + gap + (ratioMetrics.descent - ratioMetrics.ascent);
            }

            final int width = Math.max(px(MIN_WIDTH_DP), Math.round(contentWidth) + padding * 2);
            final int height = Math.round(contentHeight) + padding * 2;

            // Cards of some layouts show the thumbnail inset from their edges, such as the shelves
            // of a channel, or below other content, such as the video of a channel post.
            // So the badge is placed from the duration badge, or else from the thumbnail.
            FeedViewCountPatch.ThumbnailFrame frame = FeedViewCountPatch.findThumbnailFrame(host);
            final Rect duration = frame.duration;
            final Rect thumbnail = frame.thumbnail;
            final int right;
            final int bottom;
            final int thumbnailTop;
            if (duration != null) {
                right = duration.right;
                bottom = duration.top - px(DURATION_GAP_DP);
                if (thumbnail != null && thumbnail.top < duration.top) {
                    thumbnailTop = thumbnail.top;
                } else {
                    // Thumbnails are centered on the card, and at least 16:9.
                    final int thumbnailRight = duration.right + px(RIGHT_MARGIN_DP);
                    final int thumbnailWidth = thumbnailRight - Math.max(0, host.getWidth() - thumbnailRight);
                    thumbnailTop = duration.bottom + px(RIGHT_MARGIN_DP)
                            - Math.round(thumbnailWidth * FeedVideoCardsPatch.THUMBNAIL_RATIO);
                }
            } else if (thumbnail != null) {
                right = thumbnail.right - px(RIGHT_MARGIN_DP);
                bottom = thumbnail.bottom - px(ABOVE_DURATION_DP);
                thumbnailTop = thumbnail.top;
            } else {
                right = host.getWidth() - px(RIGHT_MARGIN_DP);
                bottom = FeedVideoCardsPatch.getThumbnailBottom(host) - px(ABOVE_DURATION_DP);
                thumbnailTop = 0;
            }
            final int top = Math.max(thumbnailTop + px(MARGIN_DP), bottom - height);

            setBounds(right - width, top, right, top + height);
            if (!added) {
                host.getOverlay().add(this);
                added = true;
            }
            invalidateSelf();
        }

        void detach(ComponentHost host) {
            host.removeOnAttachStateChangeListener(this);
            cancelPendingFetch();
            endFadeIn();
            removeFromOverlay(host);
        }

        @Override
        public void onViewAttachedToWindow(@NonNull View view) {
            try {
                // A card that shows the same video may not be laid out again.
                FeedVideoCardsPatch.requestUpdate((ComponentHost) view);
            } catch (Exception ex) {
                Logger.printException(() -> "onViewAttachedToWindow failure", ex);
            }
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View view) {
            confirmed = false;
            cancelPendingFetch();
            endFadeIn();
        }

        private void removeFromOverlay(ComponentHost host) {
            if (added) {
                host.getOverlay().remove(this);
                added = false;
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (!confirmed || !hasContent()) {
                return;
            }
            ComponentHost host = hostRef.get();
            if (host != null && FeedVideoCardsPatch.isCardPlayingInline(host)) {
                // The inline player shows its own buttons over the thumbnail.
                return;
            }
            Rect bounds = getBounds();
            final float radius = px(CORNER_RADIUS_DP);
            backgroundPaint.setAlpha(Color.alpha(BACKGROUND_COLOR) * alpha / 255);
            countPaint.setAlpha(alpha);
            ratioPaint.setAlpha(alpha);
            rect.set(bounds);
            canvas.drawRoundRect(rect, radius, radius, backgroundPaint);

            final int padding = px(PADDING_DP);
            final int gap = px(GAP_DP);
            final float centerX = bounds.exactCenterX();
            float y = bounds.top + padding;

            if (countText != null) {
                final int iconSize = px(ICON_DP);
                Drawable icon = getLikeIcon();
                if (icon != null) {
                    final int left = Math.round(centerX - iconSize / 2f);
                    icon.setBounds(left, Math.round(y), left + iconSize, Math.round(y) + iconSize);
                    // Shared by all badges, so the alpha is set for each draw.
                    icon.setAlpha(alpha);
                    icon.draw(canvas);
                }
                y += iconSize + gap;
                Paint.FontMetrics metrics = countPaint.getFontMetrics();
                canvas.drawText(countText, centerX, y - metrics.ascent, countPaint);
                y += metrics.descent - metrics.ascent;
            }

            if (ratioText != null) {
                if (countText != null) {
                    y += gap * 2;
                }
                final float barLeft = bounds.left + padding;
                final float barRight = bounds.right - padding;
                final float barHeight = px(BAR_HEIGHT_DP);
                final float split = barLeft + (barRight - barLeft) * likeFraction;
                final float barRadius = barHeight / 2;

                barPaint.setColor(DISLIKE_BAR_COLOR);
                barPaint.setAlpha(alpha);
                rect.set(barLeft, y, barRight, y + barHeight);
                canvas.drawRoundRect(rect, barRadius, barRadius, barPaint);
                if (split > barLeft) {
                    barPaint.setColor(LIKE_BAR_COLOR);
                    barPaint.setAlpha(alpha);
                    rect.set(barLeft, y, split, y + barHeight);
                    canvas.drawRoundRect(rect, barRadius, barRadius, barPaint);
                }
                y += barHeight + gap;

                Paint.FontMetrics metrics = ratioPaint.getFontMetrics();
                canvas.drawText(ratioText, centerX, y - metrics.ascent, ratioPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
