/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.niconicocomments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import app.morphe.extension.shared.Logger;

/** Draws live and replay chat comments on the YouTube playback timeline. */
final class NiconicoCommentsOverlayView extends View {
    private static final long IDLE_FRAME_DELAY_MS = 250;
    private static final int MEMBERSHIP_COLOR = Color.rgb(43, 166, 64);
    private static final long EMOJI_PREFETCH_MS = 1_000;
    private static final String EMOJI_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/138 Mobile";
    private static final ThreadPoolExecutor EMOJI_EXECUTOR = EmojiImageCache.createExecutor();

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
    private final RectF emojiDestination = new RectF();
    private final CommentSchedule schedule = new CommentSchedule();
    private final EmojiImageCache<Bitmap> emojiImageCache = new EmojiImageCache<>();
    private final Set<String> neededEmojiUrls = new HashSet<>();
    private volatile int emojiGeneration;
    private final float density;
    private final float scaledDensity;
    private final float backgroundPadding;
    private final float backgroundRadius;

    private String videoId = "";
    private boolean overlayActive;
    private boolean videoPlaying;
    private int fontSizeSp = NiconicoCommentsController.DEFAULT_FONT_SIZE_SP;
    private int opacityPercent = NiconicoCommentsController.DEFAULT_OPACITY_PERCENT;
    private float laneHeight;
    private float firstBaseline;

    NiconicoCommentsOverlayView(Context context) {
        super(context);

        density = context.getResources().getDisplayMetrics().density;
        scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        backgroundPadding = 4 * density;
        backgroundRadius = 3 * density;

        fillPaint.setColor(Color.WHITE);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setTypeface(Typeface.DEFAULT_BOLD);

        strokePaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2 * density);
        strokePaint.setTypeface(Typeface.DEFAULT_BOLD);

        applyFontSize();
        setWillNotDraw(false);
        setVisibility(GONE);
    }

    void setVideoId(String nextVideoId) {
        if (nextVideoId.equals(videoId)) {
            return;
        }
        videoId = nextVideoId;
        emojiGeneration++;
        emojiImageCache.clear();
        clearComments();
    }

    void setOverlayActive(boolean active) {
        if (overlayActive == active) {
            return;
        }
        overlayActive = active;
        setVisibility(active ? VISIBLE : GONE);
        requestNextFrame();
    }

    void setVideoPlaying(boolean playing) {
        if (videoPlaying == playing) {
            return;
        }
        videoPlaying = playing;
        requestNextFrame();
    }

    void setCommentFontSizeSp(int nextFontSizeSp) {
        int clampedSize = NiconicoCommentsController.clampFontSizeSp(nextFontSizeSp);
        if (fontSizeSp == clampedSize) {
            return;
        }
        fontSizeSp = clampedSize;
        applyFontSize();
        if (getHeight() > 0) {
            schedule.redistributeLanes(getLaneCount());
        }
        invalidate();
    }

    void setCommentOpacityPercent(int nextOpacityPercent) {
        int clampedOpacity = NiconicoCommentsController.clampOpacityPercent(
                nextOpacityPercent
        );
        if (opacityPercent == clampedOpacity) {
            return;
        }
        opacityPercent = clampedOpacity;
        invalidate();
    }

    void addComments(List<NiconicoComment> incoming, long liveSpreadWindowMs) {
        if (incoming.isEmpty()) {
            return;
        }
        long videoTimeMs = NiconicoCommentsController.getVideoTime();
        if (videoTimeMs < 0) {
            return;
        }

        schedule.addComments(
                incoming,
                videoTimeMs,
                liveSpreadWindowMs,
                getLaneCount()
        );
        updateEmojiDemand(videoTimeMs);
        requestNextFrame();
    }

    void clearComments() {
        schedule.clear();
        neededEmojiUrls.clear();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (height > 0 && height != oldHeight) {
            schedule.redistributeLanes(getLaneCount());
            requestNextFrame();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!overlayActive || videoId.isEmpty()) {
            return;
        }

        long videoTimeMs = NiconicoCommentsController.getVideoTime();
        if (videoTimeMs < 0) {
            if (videoPlaying) {
                postInvalidateDelayed(IDLE_FRAME_DELAY_MS);
            }
            return;
        }

        schedule.removeExpired(videoTimeMs);
        updateEmojiDemand(videoTimeMs);
        int laneCount = getLaneCount();
        boolean hasActiveOrUpcomingComment = false;
        for (CommentSchedule.ScheduledComment scheduled : schedule.comments()) {
            long elapsedMs = videoTimeMs - scheduled.startTimeMs;
            if (elapsedMs < 0) {
                hasActiveOrUpcomingComment = true;
                continue;
            }

            hasActiveOrUpcomingComment = true;
            NiconicoComment comment = scheduled.comment;
            float commentWidth = measureCommentWidth(comment);
            float progress = (float) elapsedMs / CommentSchedule.COMMENT_DURATION_MS;
            float x = getWidth() - (progress * (getWidth() + commentWidth));
            float y = firstBaseline + (Math.floorMod(scheduled.lane, laneCount) * laneHeight);

            if (comment.backgroundColor != Color.TRANSPARENT) {
                backgroundPaint.setColor(applyOpacity(comment.backgroundColor));
                canvas.drawRoundRect(
                        x - backgroundPadding,
                        y + fontMetrics.ascent - backgroundPadding,
                        x + commentWidth + backgroundPadding,
                        y + fontMetrics.descent + backgroundPadding,
                        backgroundRadius,
                        backgroundRadius,
                        backgroundPaint
                );
            }

            fillPaint.setColor(applyOpacity(
                    comment.membership ? MEMBERSHIP_COLOR : Color.WHITE
            ));
            strokePaint.setColor(applyOpacity(Color.BLACK));
            drawCommentSegments(canvas, comment, x, y);
        }

        if (videoPlaying && hasActiveOrUpcomingComment) {
            postInvalidateOnAnimation();
        }
    }

    private void applyFontSize() {
        float textSize = fontSizeSp * scaledDensity;
        fillPaint.setTextSize(textSize);
        strokePaint.setTextSize(textSize);
        fillPaint.getFontMetrics(fontMetrics);
        laneHeight = fontMetrics.descent - fontMetrics.ascent + (8 * density);
        firstBaseline = -fontMetrics.ascent + (12 * density);
    }

    private int getLaneCount() {
        return Math.max(1, (int) ((getHeight() - firstBaseline) / laneHeight) + 1);
    }

    private int applyOpacity(int color) {
        int adjustedAlpha = Math.round(Color.alpha(color) * (opacityPercent / 100f));
        return Color.argb(
                adjustedAlpha,
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }

    private float measureCommentWidth(NiconicoComment comment) {
        float width = 0;
        for (NiconicoComment.Segment segment : comment.segments) {
            width += segment.isEmoji() ? fillPaint.getTextSize() : fillPaint.measureText(segment.text);
        }
        return width;
    }

    private void drawCommentSegments(
            Canvas canvas,
            NiconicoComment comment,
            float startX,
            float baseline
    ) {
        float x = startX;
        for (NiconicoComment.Segment segment : comment.segments) {
            if (!segment.isEmoji()) {
                canvas.drawText(segment.text, x, baseline, strokePaint);
                canvas.drawText(segment.text, x, baseline, fillPaint);
                x += fillPaint.measureText(segment.text);
                continue;
            }

            float emojiSize = fillPaint.getTextSize();
            EmojiImageCache.Entry<Bitmap> state = ensureEmojiImage(segment.imageUrl);
            if (state != null && state.value != null) {
                float centerY = baseline + ((fontMetrics.ascent + fontMetrics.descent) / 2);
                emojiDestination.set(
                        x,
                        centerY - (emojiSize / 2),
                        x + emojiSize,
                        centerY + (emojiSize / 2)
                );
                canvas.drawBitmap(state.value, null, emojiDestination, fillPaint);
            } else if (state == null || state.failed) {
                canvas.drawText("□", x, baseline, strokePaint);
                canvas.drawText("□", x, baseline, fillPaint);
            }
            x += emojiSize;
        }
    }

    private void updateEmojiDemand(long videoTimeMs) {
        neededEmojiUrls.clear();
        for (CommentSchedule.ScheduledComment scheduled : schedule.comments()) {
            if (scheduled.startTimeMs > videoTimeMs + EMOJI_PREFETCH_MS) continue;
            for (NiconicoComment.Segment segment : scheduled.comment.segments) {
                if (segment.isEmoji()) neededEmojiUrls.add(segment.imageUrl);
            }
        }
        for (String url : neededEmojiUrls) ensureEmojiImage(url);
    }

    private EmojiImageCache.Entry<Bitmap> ensureEmojiImage(String imageUrl) {
        EmojiImageCache.Entry<Bitmap> existing = emojiImageCache.get(imageUrl);
        if (existing != null) return existing;
        if (!overlayActive || !videoPlaying || EMOJI_EXECUTOR.getQueue().remainingCapacity() == 0) return null;

        EmojiImageCache.Entry<Bitmap> state = emojiImageCache.beginLoad(imageUrl, neededEmojiUrls);
        if (state == null) return null;
        int generation = emojiGeneration;
        try {
            EMOJI_EXECUTOR.execute(() -> loadEmojiImage(imageUrl, state, generation));
        } catch (RejectedExecutionException exception) {
            emojiImageCache.discard(imageUrl, state);
            return null;
        }
        return state;
    }

    private void loadEmojiImage(String imageUrl, EmojiImageCache.Entry<Bitmap> state, int generation) {
        if (generation != emojiGeneration) return;
        HttpURLConnection connection = null;
        Bitmap bitmap = null;
        try {
            connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", EMOJI_USER_AGENT);
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream input = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                }
            }
        } catch (Exception exception) {
            Logger.printDebug(() -> "Could not load YouTube emoji", exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        Bitmap loadedBitmap = bitmap;
        post(() -> {
            if (generation == emojiGeneration && emojiImageCache.complete(imageUrl, state, loadedBitmap)) {
                requestNextFrame();
            }
        });
    }

    private void requestNextFrame() {
        if (overlayActive) {
            postInvalidateOnAnimation();
        }
    }

}
