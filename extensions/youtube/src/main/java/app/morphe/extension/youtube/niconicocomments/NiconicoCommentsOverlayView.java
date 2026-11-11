/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * 
 * Niconico-style live/replay comments implementation is adapted from
 * https://github.com/david419kr/niconico-yt-morphe-patches (GPLv3)
 * Feature design is based on david419kr/niconico-yt (MIT License)
 */

package app.morphe.extension.youtube.niconicocomments;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Draws live and replay chat comments scrolling across the video.
 */
final class NiconicoCommentsOverlayView extends View {
    private static final long IDLE_FRAME_DELAY_MS = 250;
    private static final int MEMBERSHIP_COLOR = Color.rgb(43, 166, 64);
    private static final long EMOJI_PREFETCH_MS = 1_000;
    private static final int EMOJI_CONNECT_TIMEOUT_MS = 5_000;
    private static final int EMOJI_READ_TIMEOUT_MS = 10_000;
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
    private final float density;
    private final float scaledDensity;
    private final float backgroundPadding;
    private final float backgroundRadius;

    private final AtomicInteger emojiGeneration = new AtomicInteger();
    private String videoId = "";
    private boolean overlayActive;
    private boolean videoPlaying;
    @Nullable
    private Window activityWindow;
    /**
     * The refresh rate the overlay last set on the window, or 0.
     */
    private static float windowRefreshRate;
    private int maxRefreshRate = SeekBarPreference.clampToRange(Settings.NICONICO_COMMENTS_MAX_REFRESH_RATE);
    private float requestedRefreshRate;
    private int fontSizeSp = SeekBarPreference.clampToRange(Settings.NICONICO_COMMENTS_FONT_SIZE);
    private int opacityPercent = SeekBarPreference.clampToRange(Settings.NICONICO_COMMENTS_OPACITY);
    private int displayAreaPercent = SeekBarPreference.clampToRange(Settings.NICONICO_COMMENTS_DISPLAY_AREA);
    private float laneHeight;
    private float firstBaseline;

    NiconicoCommentsOverlayView(Context context) {
        super(context);

        density = context.getResources().getDisplayMetrics().density;
        final float fontScale = context.getResources().getConfiguration().fontScale;
        scaledDensity = density * (fontScale != 0 ? fontScale : 1.0f);
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
        setTravelDurationSeconds(SeekBarPreference.clampToRange(Settings.NICONICO_COMMENTS_TRAVEL_DURATION));
        setWillNotDraw(false);
        setVisibility(GONE);
    }

    void setVideoId(String nextVideoId) {
        if (nextVideoId.equals(videoId)) {
            return;
        }
        videoId = nextVideoId;
        emojiGeneration.incrementAndGet();
        emojiImageCache.clear();
        clearComments();
    }

    void setOverlayActive(boolean active) {
        if (overlayActive == active) {
            return;
        }
        overlayActive = active;
        setVisibility(active ? VISIBLE : GONE);
        if (!active) {
            setHighFrameRate(false);
        }
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
        if (fontSizeSp == nextFontSizeSp) {
            return;
        }
        fontSizeSp = nextFontSizeSp;
        applyFontSize();
        if (getHeight() > 0) {
            redistributeLanes();
        }
        invalidate();
    }

    void setCommentOpacityPercent(int nextOpacityPercent) {
        if (opacityPercent == nextOpacityPercent) {
            return;
        }
        opacityPercent = nextOpacityPercent;
        invalidate();
    }

    void setDisplayAreaPercent(int nextDisplayAreaPercent) {
        if (displayAreaPercent == nextDisplayAreaPercent) {
            return;
        }
        displayAreaPercent = nextDisplayAreaPercent;
        if (getHeight() > 0) {
            redistributeLanes();
        }
        invalidate();
    }

    void setTravelDurationSeconds(int seconds) {
        final long durationMs = seconds * 1000L;
        if (schedule.getCommentDurationMs() == durationMs) {
            return;
        }
        schedule.setCommentDurationMs(durationMs);
        if (getHeight() > 0) {
            redistributeLanes();
        }
        invalidate();
    }

    void setHideCrowdedComments(boolean hide) {
        schedule.setHideCrowdedComments(hide);
    }

    void setMaxRefreshRate(int nextMaxRefreshRate) {
        if (maxRefreshRate == nextMaxRefreshRate) {
            return;
        }
        maxRefreshRate = nextMaxRefreshRate;
        requestedRefreshRate = 0;
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
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        NiconicoCommentsController.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (height > 0 && (height != oldHeight || width != oldWidth)) {
            redistributeLanes();
            requestNextFrame();
        }
    }

    /**
     * Places the comments again with the current sizes. A new size always comes through here.
     */
    private void redistributeLanes() {
        // Half the font size between comments, and at least room for the backgrounds of paid messages.
        float minGap = Math.max(fillPaint.getTextSize() / 2, 2 * backgroundPadding + strokePaint.getStrokeWidth());
        schedule.setLayout(getWidth(), minGap, this::measureCommentWidth);
        schedule.redistributeLanes(getLaneCount());
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!overlayActive || videoId.isEmpty()) {
            setHighFrameRate(false);
            return;
        }

        long videoTimeMs = NiconicoCommentsController.getVideoTime();
        if (videoTimeMs < 0) {
            setHighFrameRate(false);
            if (videoPlaying) {
                postInvalidateDelayed(IDLE_FRAME_DELAY_MS);
            }
            return;
        }

        schedule.removeExpired(videoTimeMs);
        updateEmojiDemand(videoTimeMs);
        int laneCount = getLaneCount();
        boolean hasActiveOrUpcomingComment = false;
        boolean hasVisibleComment = false;
        for (CommentSchedule.ScheduledComment scheduled : schedule.comments()) {
            long elapsedMs = videoTimeMs - scheduled.startTimeMs;
            if (elapsedMs < 0) {
                hasActiveOrUpcomingComment = true;
                continue;
            }

            hasActiveOrUpcomingComment = true;
            hasVisibleComment = true;
            NiconicoComment comment = scheduled.comment;
            float commentWidth = measureCommentWidth(comment);
            float progress = (float) elapsedMs / schedule.getCommentDurationMs();
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

        setHighFrameRate(videoPlaying && hasVisibleComment);
        if (videoPlaying && hasActiveOrUpcomingComment) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * The display can lower its refresh rate to match the video frame rate (often 24 or 30 Hz),
     * which makes the scrolling comments choppy. Request the highest refresh rate for the window,
     * up to the max refresh rate setting, only while comments are moving across the screen.
     * <p>
     * A view frame rate category is not used, because some devices ignore it.
     */
    private void setHighFrameRate(boolean high) {
        Window window = activityWindow;
        if (window == null) {
            return;
        }
        WindowManager.LayoutParams params = window.getAttributes();
        // The App refresh rate setting uses a display mode, which takes priority.
        if (params.preferredDisplayModeId != 0) {
            return;
        }
        float rate = 0;
        if (high) {
            if (requestedRefreshRate == 0) {
                requestedRefreshRate = findRefreshRate();
            }
            rate = requestedRefreshRate;
        }
        // Leave a refresh rate that was set by something else.
        if (params.preferredRefreshRate == rate || params.preferredRefreshRate != windowRefreshRate) {
            return;
        }
        params.preferredRefreshRate = rate;
        windowRefreshRate = rate;
        window.setAttributes(params);
    }

    /**
     * @return The highest refresh rate of the current display resolution that is not above
     *         the max refresh rate setting, or the lowest one if all are above it.
     */
    private float findRefreshRate() {
        Display display = getDisplay();
        if (display == null) {
            return 0;
        }
        final float limit = maxRefreshRate >= Settings.NICONICO_COMMENTS_MAX_REFRESH_RATE_NO_LIMIT
                ? Float.MAX_VALUE
                : maxRefreshRate + 0.5f;
        Display.Mode current = display.getMode();
        float highest = 0;
        float lowest = Float.MAX_VALUE;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                float rate = mode.getRefreshRate();
                lowest = Math.min(lowest, rate);
                if (rate <= limit) {
                    highest = Math.max(highest, rate);
                }
            }
        }
        return highest > 0 ? highest : lowest;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Context context = getContext();
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        activityWindow = context instanceof Activity activity ? activity.getWindow() : null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setHighFrameRate(false);
        activityWindow = null;
    }

    private void applyFontSize() {
        float textSize = fontSizeSp * scaledDensity;
        fillPaint.setTextSize(textSize);
        strokePaint.setTextSize(textSize);
        fillPaint.getFontMetrics(fontMetrics);
        laneHeight = fontMetrics.descent - fontMetrics.ascent + (8 * density);
        firstBaseline = -fontMetrics.ascent + (12 * density);
    }

    /**
     * @return The number of lanes that fit in the display area, which starts at the top of the player.
     */
    private int getLaneCount() {
        float height = getHeight() * displayAreaPercent / 100f;
        return Math.max(1, (int) ((height - firstBaseline) / laneHeight) + 1);
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

    @Nullable
    private EmojiImageCache.Entry<Bitmap> ensureEmojiImage(String imageUrl) {
        EmojiImageCache.Entry<Bitmap> existing = emojiImageCache.get(imageUrl);
        if (existing != null) return existing;
        // Loads are only queued from the main thread, so the queue cannot fill up after this check.
        if (!overlayActive || !videoPlaying || EMOJI_EXECUTOR.getQueue().remainingCapacity() == 0) return null;

        EmojiImageCache.Entry<Bitmap> state = emojiImageCache.beginLoad(imageUrl, neededEmojiUrls);
        if (state == null) return null;
        final int generation = emojiGeneration.get();
        EMOJI_EXECUTOR.execute(() -> loadEmojiImage(imageUrl, state, generation));
        return state;
    }

    private void loadEmojiImage(String imageUrl, EmojiImageCache.Entry<Bitmap> state, int generation) {
        if (generation != emojiGeneration.get()) return;
        HttpURLConnection connection = null;
        Bitmap bitmap = null;
        try {
            connection = Requester.openConnection(imageUrl);
            connection.setConnectTimeout(EMOJI_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(EMOJI_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", EMOJI_USER_AGENT);
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream input = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                }
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not load emoji: " + imageUrl, ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        Bitmap loadedBitmap = bitmap;
        post(() -> {
            if (generation == emojiGeneration.get() && emojiImageCache.complete(imageUrl, state, loadedBitmap)) {
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
