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
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
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
 * Shows the like count and the like ratio of feed videos on their thumbnails.
 * Part of the Return YouTube Dislike patch.
 * <p>
 * Feed videos do not include their like count, so it's fetched from the Return YouTube Dislike API,
 * with the same cache as the player. A video is fetched when its card is laid out. Until the fetch
 * completes, the badge shows a loading spinner, and the counts fade in once they are fetched.
 * The counts are not clickable, so tapping them opens the video.
 * <p>
 * The counts are shown in one row at the bottom of the thumbnail, continuing the view count badge,
 * or below each other at an edge of the thumbnail, see {@link FeedCountsPosition}.
 */
@SuppressWarnings("unused")
public final class FeedEngagementCountsPatch {

    private static final boolean RYD_ENABLED = Settings.RYD_ENABLED.get();
    private static final boolean LIKE_COUNT = RYD_ENABLED && Settings.RYD_FEED_LIKE_COUNT.get();
    private static final boolean LIKE_RATIO = RYD_ENABLED && Settings.RYD_FEED_LIKE_RATIO.get();
    public static final boolean ENABLED = LIKE_COUNT || LIKE_RATIO;
    /**
     * If the counts are shown in one row at the bottom of the thumbnail, right after the view count badge,
     * or where it would be. Thumbnails too small for the row show the counts at the top.
     */
    private static final boolean NEXT_TO_VIEW_COUNT = ENABLED
            && Settings.RYD_FEED_COUNTS_POSITION.get() == FeedCountsPosition.NEXT_TO_VIEW_COUNT;
    /**
     * If the badge is at the middle of the right edge of the thumbnail, or of its left edge
     * in right to left languages, instead of at its top left corner.
     */
    private static final boolean MIDDLE_RIGHT = ENABLED
            && Settings.RYD_FEED_COUNTS_POSITION.get() == FeedCountsPosition.MIDDLE_RIGHT;

    /**
     * Where the counts are shown on the thumbnails.
     */
    public enum FeedCountsPosition {
        /** In one row at the bottom of the thumbnail, continuing the view count badge. */
        NEXT_TO_VIEW_COUNT,
        /** Below each other, at the middle of the right edge of the thumbnail, below the Watch later button. */
        MIDDLE_RIGHT,
        /** Below each other, at the top left corner of the thumbnail. */
        TOP_LEFT
    }

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
    /**
     * Thumbnails narrower than this part of the smallest side of the screen, such as of the rows
     * of a playlist, do not show the like ratio, as it does not fit next to the duration.
     * The thumbnails of the videos of a channel are wider, and show it.
     */
    private static final float MAX_SMALL_THUMBNAIL_OF_SCREEN = 0.4f;
    /** Height of the Watch later button at the top right of the thumbnail, with the space below it. */
    private static final int TOP_BUTTON_DP = 40;
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

    /** The row next to the view count, see {@link #NEXT_TO_VIEW_COUNT}. */
    private static final int ROW_PADDING_DP = 5;
    /** Space at each side of the divider between the view count and the like count, as seen. */
    private static final int ROW_DIVIDER_SPACE_DP = 5;
    /** Space around the shape of the like icon, relative to its size, as the icon is 20 of 24 dp wide. */
    private static final float ROW_ICON_INSET_OF_SIZE = 2f / 24;
    /**
     * Smaller text sizes tried, relative to the text size of the view count, if the row does not fit
     * on the thumbnail, such as of the Shorts of a channel, which are shown in three columns.
     */
    private static final float[] ROW_TEXT_SCALES = {1f, 0.9f, 0.8f};
    private static final float ROW_DIVIDER_WIDTH_DP = 1f;
    /** Height of the divider, relative to the height of the row. */
    private static final float ROW_DIVIDER_OF_HEIGHT = 0.5f;
    /** Space between the like count and the like bar. */
    private static final int ROW_GROUP_GAP_DP = 6;
    private static final int ROW_BAR_WIDTH_DP = 3;
    /** Size of the like icon, which has some space around it, relative to the text size. */
    private static final float ROW_ICON_OF_TEXT = 1.15f;
    /** Height of the like bar and of the spinner, relative to the text size. */
    private static final float ROW_BAR_OF_TEXT = 0.85f;
    /** Space of the row from the edges of the thumbnail if the duration badge is not found. */
    private static final int ROW_FALLBACK_MARGIN_DP = 8;
    private static final int ROW_FALLBACK_HEIGHT_DP = 20;
    private static final int ROW_MIN_HEIGHT_DP = 12;
    private static final int DIVIDER_COLOR = 0x80FFFFFF;

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
        // The video passed, as a card that enters the list can still be known as the video it showed before.
        if (videoId != null && host instanceof ComponentHost componentHost
                && (FeedVideoCardsPatch.isInDialog(componentHost) || FeedVideoCardsPatch.isLiveVideo(videoId))) {
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
     * Called by {@link FeedViewCountPatch} on the main thread after the view count badge of a card
     * is placed or removed, so the counts shown next to it are placed again.
     */
    public static void onViewCountBadgeChanged(View host) {
        if (!NEXT_TO_VIEW_COUNT) {
            return;
        }
        try {
            CountsBadge badge = badges.get(host);
            if (badge != null && badge.videoId != null) {
                badge.place(host);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onViewCountBadgeChanged failure", ex);
        }
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
        /** Texts of the row next to the view count, with the font of the view count. */
        private final Paint rowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path backgroundPath = new Path();
        private final float[] radii = new float[8];
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
        /** The like ratio of the video. */
        @Nullable
        private String videoRatioText;
        /** The like ratio, if shown on this thumbnail, see {@link #MAX_SMALL_THUMBNAIL_OF_SCREEN}. */
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
        /** If the badge is the row next to the view count, see {@link #NEXT_TO_VIEW_COUNT}. */
        private boolean row;
        /** If the row continues the view count badge, with a divider between them. */
        private boolean joined;
        /** If the view count badge is at the right of the row, in right to left languages. */
        private boolean joinedAtRight;
        /** Where the content of the row starts, and the divider is, relative to the left of the row. */
        private float rowContentLeft;
        private float rowDividerX;

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
            // Also the custom font of the Custom font patch, which a paint without a typeface does not use.
            ratioPaint.setTypeface(Typeface.DEFAULT);
            ratioPaint.setTextSize(RATIO_TEXT_SP * scaledDensity);

            spinnerPaint = FeedVideoCardsPatch.createSpinnerPaint(density, TEXT_COLOR);

            rowPaint.setColor(TEXT_COLOR);
            rowPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

            dividerPaint.setColor(DIVIDER_COLOR);
            dividerPaint.setStrokeWidth(ROW_DIVIDER_WIDTH_DP * density);
        }

        private int px(float dp) {
            return Math.round(dp * density);
        }

        void bind(View host, String newVideoId) {
            confirmed = true;
            if (!newVideoId.equals(videoId)) {
                videoId = newVideoId;
                countText = null;
                videoRatioText = null;
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
                videoRatioText = formatPercent(fraction);
            } else {
                likeFraction = -1;
                videoRatioText = null;
            }
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        private boolean hasContent() {
            return countText != null || videoRatioText != null;
        }

        /**
         * @return If the counts are being fetched, and the loading spinner is shown until then.
         */
        private boolean isLoading() {
            return !hasContent() && (fetching || pendingFetch != null);
        }

        /**
         * Places the badge on the thumbnail.
         */
        void place(View host) {
            final boolean loading = isLoading();
            if (!hasContent() && !loading) {
                // The fetch failed, or it waits for the next layout of the card.
                removeFromOverlay(host);
                return;
            }
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
            // The smallest thumbnails, such as of the rows of a playlist, show only the like count.
            final Rect frameThumbnail = frame.thumbnail();
            final int frameWidth = frameThumbnail != null ? frameThumbnail.width() : host.getWidth();
            final DisplayMetrics metrics = host.getResources().getDisplayMetrics();
            final boolean small = frameWidth
                    < Math.min(metrics.widthPixels, metrics.heightPixels) * MAX_SMALL_THUMBNAIL_OF_SCREEN;
            ratioText = small && LIKE_COUNT ? null : videoRatioText;

            if (NEXT_TO_VIEW_COUNT && placeRow(host, frame, loading)) {
                return;
            }
            row = false;
            setJoined(host, false);

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

            final Rect duration = frame.duration();
            final Rect thumbnail = frame.thumbnail();
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
            // The top left corner of the thumbnail, as far from the edges as the duration badge,
            // or the middle of its right edge.
            // Video cards have the Watch later button at the top right in any language. Cards of Shorts
            // have their menu at the top right, or at the top left in right to left languages,
            // so their badge is at the top right then, as is the badge of the thumbnails of compact videos.
            // At the middle of the right edge, the badge is at the left in right to left languages,
            // as the duration badge.
            final boolean atRight = MIDDLE_RIGHT ? !rtl : rtl && this.frame != null;
            // At the right edge, the badge is below the Watch later button, or the menu of a Short.
            final int cornerTop = thumbnailTop + margin;
            final int minTop = cornerTop + (MIDDLE_RIGHT ? px(TOP_BUTTON_DP) : 0);
            // The badge does not overlap the row of the duration badge, which can also have the view count.
            compact = minTop + height > bottom;
            // The bottom of the thumbnail is about as far below the row of the duration as the duration badge.
            final int middle = (thumbnailTop + bottom + px(ABOVE_DURATION_DP)) / 2;
            final int top = MIDDLE_RIGHT && !compact
                    ? Math.max(minTop, Math.min(bottom - height, middle - height / 2))
                    : minTop;
            if (compact && this.frame != null && this.frame.estimated()) {
                // The row of the duration can move down to where the full badge fits, so the badge waits
                // for it instead of changing its layout.
                removeFromOverlay(host);
                return;
            }
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
                // Small thumbnails, such as of the rows of a list, have no Watch later button,
                // so the row can be above where the button would be.
                final int rowTop = MIDDLE_RIGHT
                        ? Math.max(cornerTop, Math.min(bottom - rowHeight, middle - rowHeight / 2))
                        : top;
                setBounds(rowLeft, rowTop, rowLeft + rowWidth, rowTop + rowHeight);
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

        /**
         * Places the badge as one row at the bottom of the thumbnail, right after the view count badge,
         * or where the view count badge would be if it's not shown.
         *
         * @return If the badge is placed or waits, or false if the row does not fit on the thumbnail.
         */
        private boolean placeRow(View host, FeedViewCountPatch.ThumbnailFrame frame, boolean loading) {
            if (frame.estimated()) {
                // The row of the duration moves down once the title of the Short is drawn.
                row = false;
                setJoined(host, false);
                removeFromOverlay(host);
                return true;
            }
            final Rect duration = frame.duration();
            final Rect thumbnail = frame.thumbnail();
            final boolean rtl = FeedViewCountPatch.isRtl(host);
            final int thumbnailLeft = thumbnail != null ? thumbnail.left : 0;
            final int thumbnailRight = thumbnail != null ? thumbnail.right : host.getWidth();
            final Rect viewCount = FeedViewCountPatch.getBadgeBounds(host);

            // Same height and bottom as the view count badge and the duration badge.
            final int top;
            final int bottom;
            if (viewCount != null) {
                top = viewCount.top;
                bottom = viewCount.bottom;
            } else if (duration != null) {
                top = duration.top;
                bottom = duration.bottom;
            } else {
                final int thumbnailBottom = thumbnail != null
                        ? thumbnail.bottom
                        : host instanceof ComponentHost componentHost
                        ? FeedVideoCardsPatch.getThumbnailBottom(componentHost)
                        : host.getHeight();
                bottom = thumbnailBottom - px(RIGHT_MARGIN_DP);
                top = bottom - px(ROW_FALLBACK_HEIGHT_DP);
            }
            final int height = bottom - top;
            if (height < px(ROW_MIN_HEIGHT_DP)) {
                return false;
            }
            // The same space from the edge of the thumbnail as the view count badge.
            final int margin = duration != null
                    ? Math.max(0, rtl ? duration.left - thumbnailLeft : thumbnailRight - duration.right)
                    + px(FeedViewCountPatch.EXTRA_MARGIN_DP)
                    : px(ROW_FALLBACK_MARGIN_DP);
            final float textSize = FeedViewCountPatch.getBadgeTextSize(host, height);
            final int gap = px(DURATION_GAP_DP);
            // The row does not overlap the duration badge, or the edge of the thumbnail.
            final int durationLimit = duration != null
                    ? (rtl ? duration.right + gap : duration.left - gap)
                    : (rtl ? thumbnailLeft + margin : thumbnailRight - margin);

            // Right after the view count badge.
            if (viewCount != null && setRow(host, loading, textSize, true,
                    rtl ? viewCount.left : viewCount.right, top, bottom, durationLimit, rtl)) {
                return true;
            }
            final int start = rtl ? thumbnailRight - margin : thumbnailLeft + margin;
            // Where the view count badge would be, if it's not shown.
            if (viewCount == null) {
                for (float scale : ROW_TEXT_SCALES) {
                    if (setRow(host, loading, textSize * scale, false, start, top, bottom, durationLimit, rtl)) {
                        return true;
                    }
                }
            }
            // Above the row of the duration, if the row does not fit next to the duration badge,
            // such as on the small thumbnails of the rows of a list, or the Shorts of a shelf.
            final int aboveBottom = top - gap;
            final int aboveTop = aboveBottom - height;
            if (aboveTop < (thumbnail != null ? thumbnail.top : 0) + margin) {
                return false;
            }
            final int edgeLimit = rtl ? thumbnailLeft + margin : thumbnailRight - margin;
            for (float scale : ROW_TEXT_SCALES) {
                if (setRow(host, loading, textSize * scale, false, start, aboveTop, aboveBottom, edgeLimit, rtl)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Places the row if it fits.
         *
         * @param start Where the row starts: its left, or its right in right to left languages.
         * @param limit Where the row must end at most.
         * @return If the row fits.
         */
        private boolean setRow(View host, boolean loading, float textSize, boolean join,
                               int start, int top, int bottom, int limit, boolean rtl) {
            rowPaint.setTextSize(textSize);
            final int width = Math.round(measureRow(loading, join, rtl));
            final int left = rtl ? start - width : start;
            final int right = left + width;
            if (rtl ? left < limit : right > limit) {
                return false;
            }
            row = true;
            compact = false;
            joinedAtRight = rtl;
            setBounds(left, top, right, bottom);
            setJoined(host, join);
            if (!added) {
                host.getOverlay().add(this);
                added = true;
            }
            invalidateSelf();
            return true;
        }

        /**
         * Measures the row next to the view count, with the text size set to {@link #rowPaint},
         * and sets where its content and its divider are.
         *
         * @return The width of the row.
         */
        private float measureRow(boolean loading, boolean withDivider, boolean rtl) {
            final float textSize = rowPaint.getTextSize();
            final int gap = px(GAP_DP);
            final boolean likePart = countText != null || (loading && LIKE_COUNT);
            final float iconSize = Math.round(textSize * ROW_ICON_OF_TEXT);

            float content = 0;
            if (likePart) {
                content += iconSize + gap;
            }
            if (loading) {
                content += textSize * ROW_BAR_OF_TEXT;
            } else {
                if (countText != null) {
                    content += rowPaint.measureText(countText);
                }
                if (ratioText != null) {
                    if (countText != null) {
                        content += px(ROW_GROUP_GAP_DP);
                    }
                    content += px(ROW_BAR_WIDTH_DP) + gap + rowPaint.measureText(ratioText);
                }
            }

            final int padding = px(ROW_PADDING_DP);
            if (!withDivider) {
                rowContentLeft = padding;
                return padding * 2 + content;
            }
            // The same space is seen at each side of the divider. The view count badge has its padding
            // before the divider, and the like icon has some space around its shape after it.
            final float space = px(ROW_DIVIDER_SPACE_DP);
            final float dividerWidth = ROW_DIVIDER_WIDTH_DP * density;
            final float viewCountSide = Math.max(0, space - px(FeedViewCountPatch.PADDING_DP));
            if (rtl) {
                // The like count is at the left, and the view count badge at the right.
                rowContentLeft = padding;
                rowDividerX = padding + content + space + dividerWidth / 2;
                return rowDividerX + dividerWidth / 2 + viewCountSide;
            }
            final float contentSide = Math.max(0, space - (likePart ? iconSize * ROW_ICON_INSET_OF_SIZE : 0));
            rowDividerX = viewCountSide + dividerWidth / 2;
            rowContentLeft = viewCountSide + dividerWidth + contentSide;
            return rowContentLeft + content + padding;
        }

        /**
         * Sets if the view count badge is continued by this badge.
         */
        private void setJoined(View host, boolean joined) {
            if (this.joined != joined) {
                this.joined = joined;
                FeedViewCountPatch.setBadgeJoined(host, joined);
            }
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
            setJoined(host, false);
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
            if (row) {
                drawRow(canvas, loading);
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

        /**
         * Draws the row next to the view count: a divider, the like icon and the like count,
         * and a vertical like bar with the like ratio.
         */
        private void drawRow(Canvas canvas, boolean loading) {
            Rect bounds = getBounds();
            // Same corners as the view count badge, which this row continues on the joined side.
            final float radius = px(CORNER_RADIUS_DP);
            final float leftRadius = joined && !joinedAtRight ? 0 : radius;
            final float rightRadius = joined && joinedAtRight ? 0 : radius;
            radii[0] = radii[1] = leftRadius;
            radii[2] = radii[3] = rightRadius;
            radii[4] = radii[5] = rightRadius;
            radii[6] = radii[7] = leftRadius;
            rect.set(bounds);
            backgroundPath.reset();
            backgroundPath.addRoundRect(rect, radii, Path.Direction.CW);
            canvas.drawPath(backgroundPath, backgroundPaint);

            final float textSize = rowPaint.getTextSize();
            final float centerY = bounds.exactCenterY();
            final int gap = px(GAP_DP);
            if (joined) {
                final float dividerX = bounds.left + rowDividerX;
                final float dividerHalf = bounds.height() * ROW_DIVIDER_OF_HEIGHT / 2;
                dividerPaint.setAlpha(Math.round((DIVIDER_COLOR >>> 24) * alpha / 255f));
                canvas.drawLine(dividerX, centerY - dividerHalf, dividerX, centerY + dividerHalf, dividerPaint);
            }
            float x = bounds.left + rowContentLeft;

            if (countText != null || (loading && LIKE_COUNT)) {
                final int iconSize = Math.round(textSize * ROW_ICON_OF_TEXT);
                Drawable icon = getLikeIcon();
                if (icon != null) {
                    final int top = Math.round(centerY - iconSize / 2f);
                    icon.setBounds(Math.round(x), top, Math.round(x) + iconSize, top + iconSize);
                    // Shared by all badges, and shown with the loading spinner, so it does not fade in.
                    icon.setAlpha(255);
                    icon.draw(canvas);
                }
                x += iconSize + gap;
            }

            if (loading) {
                final float spinnerSize = textSize * ROW_BAR_OF_TEXT;
                rect.set(x, centerY - spinnerSize / 2, x + spinnerSize, centerY + spinnerSize / 2);
                FeedVideoCardsPatch.drawSpinner(canvas, rect, spinnerPaint);
                // Drawn again, so the spinner turns.
                invalidateSelf();
                return;
            }

            rowPaint.setAlpha(alpha);
            Paint.FontMetrics metrics = rowPaint.getFontMetrics();
            final float baseline = centerY - (metrics.ascent + metrics.descent) / 2;
            if (countText != null) {
                canvas.drawText(countText, x, baseline, rowPaint);
                x += rowPaint.measureText(countText);
            }

            if (ratioText != null) {
                if (countText != null) {
                    x += px(ROW_GROUP_GAP_DP);
                }
                // A vertical bar, with the likes in green from the bottom and the dislikes in red at the top.
                final float barWidth = px(ROW_BAR_WIDTH_DP);
                final float barHeight = textSize * ROW_BAR_OF_TEXT;
                final float barTop = centerY - barHeight / 2;
                final float barBottom = centerY + barHeight / 2;
                final float barRadius = barWidth / 2;
                final float split = barBottom - barHeight * likeFraction;
                rect.set(x, barTop, x + barWidth, barBottom);
                barPaint.setColor(DISLIKE_BAR_COLOR);
                barPaint.setAlpha(alpha);
                canvas.drawRoundRect(rect, barRadius, barRadius, barPaint);
                if (split < barBottom) {
                    canvas.save();
                    canvas.clipRect(x, split, x + barWidth, barBottom);
                    barPaint.setColor(LIKE_BAR_COLOR);
                    barPaint.setAlpha(alpha);
                    canvas.drawRoundRect(rect, barRadius, barRadius, barPaint);
                    canvas.restore();
                }
                x += barWidth + gap;

                canvas.drawText(ratioText, x, baseline, rowPaint);
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

        @Deprecated
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
