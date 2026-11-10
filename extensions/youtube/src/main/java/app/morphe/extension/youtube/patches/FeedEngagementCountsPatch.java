/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3786
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
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
 * Shows the like count and the like ratio of feed videos at the top left corner of their thumbnails.
 * Part of the Return YouTube Dislike patch.
 * <p>
 * Feed videos do not include their like count, so it's fetched from the Return YouTube Dislike API,
 * with the same cache as the player. A video is fetched when its card is laid out. Until the fetch
 * completes, the badge shows a loading spinner, and the counts fade in once they are fetched.
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
    /**
     * Waits for a fetch that is still running, such as while the API answers slowly.
     * The fetch keeps running after a wait, and the counts are shown once it completes.
     */
    private static final int MAX_FETCH_WAITS = 3;
    /**
     * Time before a failed fetch is tried again while its card is shown.
     * Return YouTube Dislike keeps a failed fetch for 3 minutes, and gives it again until then.
     */
    private static final long FAILED_FETCH_RETRY_DELAY_MS = 3 * 60 * 1000 + 1000;
    /** Tries of a fetch that failed, before it waits for the next layout of the card. */
    private static final int MAX_FAILED_FETCH_RETRIES = 3;
    /** Time between checks of a card that is not visible yet. */
    private static final long HIDDEN_RETRY_DELAY_MS = 400;
    /** Checks of a card that is not visible, before its fetch waits for the next layout. */
    private static final int MAX_HIDDEN_RETRIES = 10;

    /**
     * Space between the duration badge and the right edge of the thumbnail, or its left edge
     * in right to left languages, used if the duration badge is not found.
     */
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
    /** About as tall as the like count. */
    private static final int SPINNER_DP = 12;
    private static final float COUNT_TEXT_SP = 11f;
    private static final float RATIO_TEXT_SP = 10f;

    /**
     * The compact badge is a single row as tall as the duration badge, used on thumbnails
     * too small to fit the badge above the duration, such as of the rows of a list on phones.
     */
    private static final int COMPACT_HEIGHT_DP = 20;
    private static final int COMPACT_PADDING_DP = 4;
    private static final int COMPACT_ICON_DP = 12;
    private static final int COMPACT_CORNER_RADIUS_DP = 4;
    private static final int COMPACT_BAR_HEIGHT_DP = 2;

    /** A little darker than the duration badge, for the smaller texts. */
    private static final int BACKGROUND_COLOR = 0xA6000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int LIKE_BAR_COLOR = 0xFF2BA640;
    private static final int DISLIKE_BAR_COLOR = 0xFFE53935;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Hosts that show the counts of a video: the hosts of the cards, or the thumbnails of the videos
     * that are not drawn by Litho. Used only on the main thread.
     */
    private static final Map<View, CountsBadge> badges = new WeakHashMap<>();

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
     * Called by {@link FeedVideoCardsPatch} on the main thread after a card or a thumbnail is laid out.
     *
     * @param host    The root host of the card, a card inside it, or the thumbnail of a video
     *                that is not drawn by Litho.
     * @param videoId The video of the card, or null if the host is not a video card
     *                or its video is not known.
     * @param frame   Where the thumbnail and its duration are, or null to find them on the card.
     *                Must be set for a host that is not a Litho card.
     */
    public static void onCardLaidOut(View host, @Nullable String videoId,
                                     @Nullable FeedViewCountPatch.ThumbnailFrame frame) {
        if (!ENABLED) {
            return;
        }
        if (videoId != null && host instanceof ComponentHost componentHost
                && (FeedVideoCardsPatch.isInDialog(componentHost) || FeedVideoCardsPatch.isLiveCard(componentHost))) {
            videoId = null;
        }
        CountsBadge badge = badges.get(host);
        if (videoId == null) {
            if (badge == null) {
                return;
            }
            // While the card is laid out again, its video can be not found for a moment.
            // The card still shows the same video if it was not detached, and the badge is
            // kept until the card is checked again, so it does not blink.
            if (badge.confirmed && badge.hasContent() && host instanceof ComponentHost componentHost
                    && FeedVideoCardsPatch.isRetryPending(componentHost)) {
                return;
            }
            badge.detach(host);
            badges.remove(host);
            return;
        }
        if (badge == null) {
            badge = new CountsBadge(host);
            badges.put(host, badge);
        }
        badge.frame = frame;
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
        private final Paint spinnerPaint;
        private final RectF rect = new RectF();
        private final float density;
        private final WeakReference<View> hostRef;
        /** Where the thumbnail and its duration are, or null to find them on the card. */
        @Nullable
        private FeedViewCountPatch.ThumbnailFrame frame;

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
        /**
         * Fetch that is tried again after it failed. Not shown as loading, as it waits for minutes.
         */
        @Nullable
        private Runnable failedFetchRetry;
        private int failedFetchRetries;
        private int alpha = 255;
        @Nullable
        private ValueAnimator fadeIn;
        /**
         * False after the card was detached, such as when the feed reuses it for another video.
         * Nothing is drawn until the card is checked again, so the counts of the previous video
         * are not shown on the next one.
         */
        private boolean confirmed = true;
        /**
         * Where the badge was placed from the duration badge of the video. The duration badge
         * is not always found, such as when the host of the whole thumbnail draws it, and the
         * place found from the thumbnail can differ, so the badge would move.
         */
        private int durationBottom;
        private int durationThumbnailTop;
        private int durationThumbnailLeft;
        private int durationThumbnailRight;
        private int durationMargin;
        /** Width of the card when the place was found, or -1 if it is not known. */
        private int durationHostWidth = -1;
        /** If the badge is the compact single row, see {@link #COMPACT_HEIGHT_DP}. */
        private boolean compact;

        CountsBadge(View host) {
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

            spinnerPaint = FeedVideoCardsPatch.createSpinnerPaint(density, TEXT_COLOR);
        }

        private int px(float dp) {
            return Math.round(dp * density);
        }

        void bind(View host, String newVideoId) {
            confirmed = true;
            if (!newVideoId.equals(videoId)) {
                videoId = newVideoId;
                countText = null;
                ratioText = null;
                likeFraction = -1;
                fetching = false;
                durationHostWidth = -1;
                failedFetchRetries = 0;
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
        private void requestFetch(View host, String fetchVideoId, int attempt) {
            if (pendingFetch != null || fetching) {
                return;
            }
            if (host.isShown()) {
                fetch(host, fetchVideoId, 0);
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
            if (failedFetchRetry != null) {
                handler.removeCallbacks(failedFetchRetry);
                failedFetchRetry = null;
            }
        }

        /**
         * @param wait The number of waits for the same fetch before this one, see {@link #MAX_FETCH_WAITS}.
         */
        private void fetch(View host, String fetchVideoId, int wait) {
            fetching = true;
            ReturnYouTubeDislike fetch = ReturnYouTubeDislike.getFetchForVideoId(fetchVideoId);
            Utils.runOnBackgroundThread(() -> {
                RYDVoteData data = fetch.getFetchData(FETCH_TIMEOUT_MS);
                final boolean completed = fetch.fetchCompleted();
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
                        } else if (!completed && wait + 1 < MAX_FETCH_WAITS && host.isAttachedToWindow()) {
                            // The fetch is still running, and the loading spinner is shown until it completes.
                            fetch(host, fetchVideoId, wait + 1);
                            return;
                        } else if (failedFetchRetry == null && failedFetchRetries < MAX_FAILED_FETCH_RETRIES
                                && host.isAttachedToWindow()) {
                            // The fetch failed, such as without a connection or while the API limits the requests.
                            // The card is not laid out again while it's shown, so the fetch is tried again later.
                            // A layout of the card meanwhile gets the same failed fetch, and keeps this retry.
                            failedFetchRetries++;
                            Runnable retry = () -> {
                                failedFetchRetry = null;
                                if (fetchVideoId.equals(videoId) && host.isAttachedToWindow() && !hasContent()) {
                                    requestFetch(host, fetchVideoId, 0);
                                    place(host);
                                }
                            };
                            failedFetchRetry = retry;
                            handler.postDelayed(retry, FAILED_FETCH_RETRY_DELAY_MS);
                        }
                        // The counts replace the loading spinner, or the badge is removed if the fetch failed.
                        place(host);
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
         * @return If the counts are being fetched, and the loading spinner is shown until then.
         */
        private boolean isLoading() {
            return !hasContent() && (fetching || pendingFetch != null);
        }

        /**
         * Places the badge at the top left corner of the thumbnail.
         */
        void place(View host) {
            final boolean loading = isLoading();
            if (!hasContent() && !loading) {
                // The fetch failed, or it waits for the next layout of the card.
                removeFromOverlay(host);
                return;
            }

            final int padding = px(PADDING_DP);
            final int gap = px(GAP_DP);
            Paint.FontMetrics countMetrics = countPaint.getFontMetrics();
            Paint.FontMetrics ratioMetrics = ratioPaint.getFontMetrics();

            float contentWidth = px(ICON_DP);
            float contentHeight = 0;
            if (loading) {
                // The like icon, if the like count is shown, and the spinner below it.
                contentHeight = (LIKE_COUNT ? px(ICON_DP) + gap : 0) + px(SPINNER_DP);
            }
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
            FeedViewCountPatch.ThumbnailFrame frame = this.frame != null || !(host instanceof ComponentHost componentHost)
                    ? this.frame
                    : FeedViewCountPatch.findThumbnailFrame(componentHost);
            if (frame == null) {
                removeFromOverlay(host);
                return;
            }
            final Rect duration = frame.duration;
            final Rect thumbnail = frame.thumbnail;
            // In right to left languages the duration badge is at the left.
            final boolean rtl = FeedViewCountPatch.isRtl(host);
            // The top of the duration badge, or of the row it would be in.
            final int bottom;
            final int thumbnailTop;
            final int thumbnailLeft;
            final int thumbnailRight;
            // Space between the duration badge and the edges of the thumbnail.
            final int margin;
            if (duration != null) {
                bottom = duration.top - px(DURATION_GAP_DP);
                if (thumbnail != null && thumbnail.top < duration.top) {
                    thumbnailTop = thumbnail.top;
                    thumbnailLeft = thumbnail.left;
                    thumbnailRight = thumbnail.right;
                    margin = Math.max(0, rtl ? duration.left - thumbnail.left : thumbnail.right - duration.right);
                } else {
                    // Thumbnails are centered on the card, and at least 16:9.
                    final int thumbnailWidth;
                    if (rtl) {
                        thumbnailLeft = duration.left - px(RIGHT_MARGIN_DP);
                        thumbnailWidth = host.getWidth() - thumbnailLeft - Math.max(0, thumbnailLeft);
                        thumbnailRight = thumbnailLeft + thumbnailWidth;
                    } else {
                        thumbnailRight = duration.right + px(RIGHT_MARGIN_DP);
                        thumbnailWidth = thumbnailRight - Math.max(0, host.getWidth() - thumbnailRight);
                        thumbnailLeft = thumbnailRight - thumbnailWidth;
                    }
                    thumbnailTop = duration.bottom + px(RIGHT_MARGIN_DP)
                            - Math.round(thumbnailWidth * FeedVideoCardsPatch.THUMBNAIL_RATIO);
                    margin = px(RIGHT_MARGIN_DP);
                }
                durationBottom = bottom;
                durationThumbnailTop = thumbnailTop;
                durationThumbnailLeft = thumbnailLeft;
                durationThumbnailRight = thumbnailRight;
                durationMargin = margin;
                durationHostWidth = host.getWidth();
            } else if (durationHostWidth == host.getWidth()) {
                bottom = durationBottom;
                thumbnailTop = durationThumbnailTop;
                thumbnailLeft = durationThumbnailLeft;
                thumbnailRight = durationThumbnailRight;
                margin = durationMargin;
            } else if (thumbnail != null) {
                bottom = thumbnail.bottom - px(ABOVE_DURATION_DP);
                thumbnailTop = thumbnail.top;
                thumbnailLeft = thumbnail.left;
                thumbnailRight = thumbnail.right;
                margin = px(RIGHT_MARGIN_DP);
            } else {
                bottom = (host instanceof ComponentHost componentHost
                        ? FeedVideoCardsPatch.getThumbnailBottom(componentHost)
                        : host.getHeight()) - px(ABOVE_DURATION_DP);
                thumbnailTop = 0;
                thumbnailLeft = 0;
                thumbnailRight = host.getWidth();
                margin = px(RIGHT_MARGIN_DP);
            }
            // The top left corner of the thumbnail, as far from the edges as the duration badge.
            // Video cards have the Watch later button at the top right in any language. Cards of Shorts
            // have their menu at the top right, or at the top left in right to left languages,
            // so their badge is at the top right then, as is the badge of the thumbnails of compact videos.
            final boolean atRight = rtl && this.frame != null;
            final int top = thumbnailTop + margin;
            // The badge does not overlap the row of the duration badge, which can also have the view count.
            compact = top + height > bottom;
            if (compact) {
                // The badge does not fit above the row of the duration, so it's a single row.
                float compactWidth = 0;
                if (countText != null || (loading && LIKE_COUNT)) {
                    compactWidth += px(COMPACT_ICON_DP) + gap;
                }
                if (loading) {
                    compactWidth += px(SPINNER_DP);
                } else {
                    if (countText != null) {
                        compactWidth += countPaint.measureText(countText);
                    }
                    if (ratioText != null) {
                        compactWidth += (countText != null ? gap * 2 : 0) + ratioPaint.measureText(ratioText);
                    }
                }
                final int compactPadding = px(COMPACT_PADDING_DP);
                final int rowWidth = Math.round(compactWidth) + compactPadding * 2;
                final int rowHeight = px(COMPACT_HEIGHT_DP);
                final int rowLeft = atRight ? thumbnailRight - margin - rowWidth : thumbnailLeft + margin;
                setBounds(rowLeft, top, rowLeft + rowWidth, top + rowHeight);
            } else {
                final int left = atRight ? thumbnailRight - margin - width : thumbnailLeft + margin;
                setBounds(left, top, left + width, top + height);
            }
            if (!added) {
                host.getOverlay().add(this);
                added = true;
            }
            invalidateSelf();
        }

        void detach(View host) {
            host.removeOnAttachStateChangeListener(this);
            cancelPendingFetch();
            endFadeIn();
            removeFromOverlay(host);
        }

        @Override
        public void onViewAttachedToWindow(@NonNull View view) {
            try {
                // A card that shows the same video may not be laid out again.
                if (view instanceof ComponentHost host) {
                    FeedVideoCardsPatch.requestUpdate(host);
                } else if (videoId != null) {
                    // A thumbnail that is not drawn by Litho is bound again when it shows another video.
                    bind(view, videoId);
                }
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

        private void removeFromOverlay(View host) {
            if (added) {
                host.getOverlay().remove(this);
                added = false;
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final boolean loading = isLoading();
            if (!confirmed || (!hasContent() && !loading)) {
                return;
            }
            if (hostRef.get() instanceof ComponentHost host && FeedVideoCardsPatch.isCardPlayingInline(host)) {
                // The inline player shows its own buttons over the thumbnail.
                return;
            }
            Rect bounds = getBounds();
            final float radius = px(compact ? COMPACT_CORNER_RADIUS_DP : CORNER_RADIUS_DP);
            // The background was shown with the loading spinner, so only the counts fade in.
            countPaint.setAlpha(alpha);
            ratioPaint.setAlpha(alpha);
            rect.set(bounds);
            canvas.drawRoundRect(rect, radius, radius, backgroundPaint);

            if (compact) {
                // A single row: the like icon, the like count and the like ratio, with the like bar at the bottom.
                final int gap = px(GAP_DP);
                final float centerY = bounds.exactCenterY();
                float x = bounds.left + px(COMPACT_PADDING_DP);
                if (countText != null || (loading && LIKE_COUNT)) {
                    final int iconSize = px(COMPACT_ICON_DP);
                    Drawable icon = getLikeIcon();
                    if (icon != null) {
                        final int top = Math.round(centerY - iconSize / 2f);
                        icon.setBounds(Math.round(x), top, Math.round(x) + iconSize, top + iconSize);
                        icon.setAlpha(255);
                        icon.draw(canvas);
                    }
                    x += iconSize + gap;
                }
                if (loading) {
                    final float spinnerSize = px(SPINNER_DP);
                    rect.set(x, centerY - spinnerSize / 2, x + spinnerSize, centerY + spinnerSize / 2);
                    FeedVideoCardsPatch.drawSpinner(canvas, rect, spinnerPaint);
                    invalidateSelf();
                    return;
                }
                if (countText != null) {
                    final float textWidth = countPaint.measureText(countText);
                    Paint.FontMetrics metrics = countPaint.getFontMetrics();
                    canvas.drawText(countText, x + textWidth / 2, centerY - (metrics.ascent + metrics.descent) / 2, countPaint);
                    x += textWidth + gap * 2;
                }
                if (ratioText != null) {
                    final float textWidth = ratioPaint.measureText(ratioText);
                    Paint.FontMetrics metrics = ratioPaint.getFontMetrics();
                    canvas.drawText(ratioText, x + textWidth / 2, centerY - (metrics.ascent + metrics.descent) / 2, ratioPaint);

                    final float barLeft = bounds.left + radius;
                    final float barRight = bounds.right - radius;
                    final float barBottom = bounds.bottom - px(1);
                    final float barTop = barBottom - px(COMPACT_BAR_HEIGHT_DP);
                    final float split = barLeft + (barRight - barLeft) * likeFraction;
                    barPaint.setColor(DISLIKE_BAR_COLOR);
                    barPaint.setAlpha(alpha);
                    rect.set(barLeft, barTop, barRight, barBottom);
                    canvas.drawRect(rect, barPaint);
                    if (split > barLeft) {
                        barPaint.setColor(LIKE_BAR_COLOR);
                        barPaint.setAlpha(alpha);
                        rect.set(barLeft, barTop, split, barBottom);
                        canvas.drawRect(rect, barPaint);
                    }
                }
                return;
            }

            final int padding = px(PADDING_DP);
            final int gap = px(GAP_DP);
            final float centerX = bounds.exactCenterX();
            float y = bounds.top + padding;

            if (countText != null || (loading && LIKE_COUNT)) {
                final int iconSize = px(ICON_DP);
                Drawable icon = getLikeIcon();
                if (icon != null) {
                    final int left = Math.round(centerX - iconSize / 2f);
                    icon.setBounds(left, Math.round(y), left + iconSize, Math.round(y) + iconSize);
                    // Shared by all badges, so the alpha is set for each draw.
                    // Shown with the loading spinner, so it does not fade in.
                    icon.setAlpha(255);
                    icon.draw(canvas);
                }
                y += iconSize + gap;
            }

            if (loading) {
                final float spinnerSize = px(SPINNER_DP);
                rect.set(centerX - spinnerSize / 2, y, centerX + spinnerSize / 2, y + spinnerSize);
                FeedVideoCardsPatch.drawSpinner(canvas, rect, spinnerPaint);
                // Drawn again, so the spinner turns.
                invalidateSelf();
                return;
            }

            if (countText != null) {
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
