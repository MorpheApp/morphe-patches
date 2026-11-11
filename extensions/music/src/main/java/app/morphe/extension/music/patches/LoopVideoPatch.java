/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4685
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.RequiresApi;

import java.util.Locale;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class LoopVideoPatch {
    private static final Handler mainThread = new Handler(Looper.getMainLooper());

    private static final long SEEK_COOLDOWN_MS = 2000;

    /** Positions within this distance of A are treated as inside the loop. */
    private static final long RANGE_TOLERANCE_MS = 250;

    private static final long LONG_PRESS_TIMEOUT_MS = 400;

    private static final float TOUCH_TOLERANCE_DP = 24f;

    public static volatile long rangeStartMs = -1;

    public static volatile long rangeEndMs = -1;

    /** Video ID when range was set; clears on track change. */
    public static volatile String rangeVideoId = "";

    private static volatile long lastSeekTimeMs = 0;

    /** Loop-logic seek; skips the clamp hook. */
    private static boolean loopSeekActive;

    public static volatile boolean scrubbing;

    private static volatile long scrubTimeMs;

    private static boolean touching;

    // Thumb center bounds from MusicPlaybackControlsTimeBar.o, matching Labjf.c(F).
    private static int seekbarLeft;
    private static int seekbarRight;
    private static int seekbarTop;
    private static int seekbarBottom;

    private static long longPressStartTime;
    private static float downX;
    private static float downY;

    private static final Paint markerPaintA;
    static {
        markerPaintA = new Paint();
        markerPaintA.setColor(Color.rgb(16, 185, 129));
        markerPaintA.setStrokeWidth(4f);
        markerPaintA.setStrokeCap(Paint.Cap.ROUND);
        markerPaintA.setAntiAlias(true);
    }

    private static final Paint markerPaintB;
    static {
        markerPaintB = new Paint();
        markerPaintB.setColor(Color.rgb(59, 130, 246));
        markerPaintB.setStrokeWidth(4f);
        markerPaintB.setStrokeCap(Paint.Cap.ROUND);
        markerPaintB.setAntiAlias(true);
    }

    private static final String TIMEBAR_CLASS = "com.google.android.apps.youtube.music.watchpage.MusicPlaybackControlsTimeBar";

    private static final Runnable longPressCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (longPressStartTime != 0 && touching) {
                longPressStartTime = 0;
                fireLongPress();
            }
        }
    };

    /** Injection point: player init. */
    public static void initialize() {
        rangeStartMs = -1;
        rangeEndMs = -1;
        rangeVideoId = "";
    }

    /** Injection point: track change. */
    public static void videoIdChanged(String id) {
        if (rangeVideoId.isEmpty()) return;
        if (!rangeVideoId.equals(id)) {
            clearRange();
        }
    }

    /** Injection point: ~1 Hz playback time. */
    public static void videoTimeChanged(long time) {
        if (time < 0) return;
        if (!Settings.LOOP_VIDEO.get()) return;
        if (!isRangeActive()) return;
        // Do not fight an active drag; the user is choosing a position.
        if (scrubbing) return;

        // Playback can cross B without any seek, so it must be pulled back by polling.
        // The lower bound also covers seeks that bypass clampSeekPosition().
        if (time >= rangeStartMs - RANGE_TOLERANCE_MS && time < rangeEndMs) return;

        long now = System.currentTimeMillis();
        if (now - lastSeekTimeMs < SEEK_COOLDOWN_MS) return;
        lastSeekTimeMs = now;

        loopSeekActive = true;
        try {
            VideoInformation.seekTo(rangeStartMs);
        } finally {
            loopSeekActive = false;
        }
    }

    /** Injection point: clamps seeks to [A, B]; loop-logic seeks bypass. */
    public static long clampSeekPosition(long time) {
        if (loopSeekActive) return time;
        if (!isRangeActive()) return time;
        if (time < rangeStartMs) return rangeStartMs;
        if (time > rangeEndMs) return rangeEndMs;
        return time;
    }

    /** Injection point: long-press + scrub tracking. Never consumes the event. */
    public static void onTouchEvent(View view, MotionEvent event) {
        if (!Settings.LOOP_VIDEO.get()) {
            resetTouchState();
            return;
        }

        // Only handle MusicPlaybackControlsTimeBar instances (other classes extend Labjf too).
        if (!view.getClass().getName().equals(TIMEBAR_CLASS)) {
            return;
        }

        if (!view.isEnabled()) {
            resetTouchState();
            return;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX();
                float y = event.getY();
                resetTouchState();
                if (!isOnSeekbar(view, x, y)) return;

                touching = true;
                scrubbing = true;
                longPressStartTime = event.getEventTime();
                downX = x;
                downY = y;
                scrubTimeMs = computeTimeFromX(clampSeekX(x));

                mainThread.postDelayed(longPressCheckRunnable, LONG_PRESS_TIMEOUT_MS);
                view.postInvalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!touching) return;
                cancelLongPressIfMoved(view, event);
                scrubTimeMs = computeTimeFromX(clampSeekX(event.getX()));
                if (scrubbing) view.postInvalidate();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                resetTouchState();
                break;
            }
            default:
                break;
        }
    }

    private static void resetTouchState() {
        touching = false;
        scrubbing = false;
        scrubTimeMs = 0;
        longPressStartTime = 0;
        mainThread.removeCallbacks(longPressCheckRunnable);
    }

    private static void cancelLongPressIfMoved(View view, MotionEvent event) {
        int touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        if (Math.abs(event.getX() - downX) > touchSlop
                || Math.abs(event.getY() - downY) > touchSlop) {
            longPressStartTime = 0;
            mainThread.removeCallbacks(longPressCheckRunnable);
        }
    }

    private static boolean isOnSeekbar(View view, float x, float y) {
        if (seekbarRight <= seekbarLeft) return false;
        float tolerancePx = TOUCH_TOLERANCE_DP * view.getResources().getDisplayMetrics().density;
        float centerY = (seekbarTop + seekbarBottom) / 2f;
        return x >= seekbarLeft - tolerancePx && x <= seekbarRight + tolerancePx
                && Math.abs(y - centerY) <= tolerancePx;
    }

    private static void fireLongPress() {
        long videoLength = VideoInformation.getVideoLength();
        if (videoLength <= 0 || seekbarRight <= seekbarLeft) {
            return;
        }

        float ratio = (downX - seekbarLeft) / (seekbarRight - seekbarLeft);
        if (ratio < 0f) ratio = 0f;
        if (ratio > 1f) ratio = 1f;
        long timeMs = (long) (ratio * videoLength);

        if (rangeStartMs < 0) {
            // First long-press: set A (loop start).
            rangeStartMs = timeMs;
            rangeVideoId = VideoInformation.getVideoId();
            Utils.showToastShort(str("morphe_loop_video_range_toast_start", formatTime(timeMs)));
        } else if (rangeEndMs <= 0) {
            // Second long-press: set B (loop end), or auto-swap if B < A.
            if (timeMs <= rangeStartMs) {
                long oldA = rangeStartMs;
                rangeStartMs = timeMs;
                rangeEndMs = oldA;
                Utils.showToastShort(str("morphe_loop_video_range_toast_start", formatTime(rangeStartMs)));
                Utils.showToastShort(str("morphe_loop_video_range_toast_end", formatTime(rangeEndMs)));
            } else {
                rangeEndMs = timeMs;
                Utils.showToastShort(str("morphe_loop_video_range_toast_end", formatTime(timeMs)));
            }
        } else {
            // Third long-press: clear the loop.
            clearRange();
            Utils.showToastShort(str("morphe_loop_video_range_toast_cleared"));
        }
    }

    public static boolean isRangeActive() {
        return rangeStartMs >= 0 && rangeEndMs > rangeStartMs;
    }

    public static void clearRange() {
        rangeStartMs = -1;
        rangeEndMs = -1;
        rangeVideoId = "";
    }

    /** Injection point: clamps thumb x to [A, B]. */
    public static float clampSeekX(float x) {
        if (!Settings.LOOP_VIDEO.get() || !isRangeActive() || seekbarRight <= seekbarLeft) {
            return x;
        }

        long videoLength = VideoInformation.getVideoLength();
        if (videoLength <= 0) return x;

        float width = seekbarRight - seekbarLeft;
        float startX = seekbarLeft + ((float) rangeStartMs / videoLength) * width;
        float endX = seekbarLeft + ((float) rangeEndMs / videoLength) * width;
        return Math.max(startX, Math.min(endX, x));
    }

    private static long computeTimeFromX(float x) {
        if (seekbarRight <= seekbarLeft) return 0;
        float ratio = (x - seekbarLeft) / (float) (seekbarRight - seekbarLeft);
        if (ratio < 0f) ratio = 0f;
        if (ratio > 1f) ratio = 1f;
        return clampToLoopRange((long) (ratio * VideoInformation.getVideoLength()));
    }

    private static long clampToLoopRange(long timeMs) {
        if (!isRangeActive()) return timeMs;
        if (timeMs < rangeStartMs) return rangeStartMs;
        if (timeMs > rangeEndMs) return rangeEndMs;
        return timeMs;
    }

    /** Injection point: seekbar bounds for markers. */
    public static void setSeekbarRectangle(Rect rect) {
        if (rect == null) {
            seekbarLeft = 0;
            seekbarRight = 0;
            seekbarTop = 0;
            seekbarBottom = 0;
            return;
        }
        // These are the thumb center bounds used by Labjf.c(F), not the inset track bounds.
        seekbarLeft = rect.left;
        seekbarRight = rect.right;
        seekbarTop = rect.top;
        seekbarBottom = rect.bottom;
    }

    /** Injection point: draws A/B markers. */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    public static void drawLoopMarkers(Canvas canvas) {
        if (!Settings.LOOP_VIDEO.get()) return;
        if (seekbarRight <= seekbarLeft) return;

        long videoLength = VideoInformation.getVideoLength();
        if (videoLength <= 0) return;

        drawMarker(canvas, rangeStartMs, videoLength, markerPaintA);
        if (rangeEndMs > 0) {
            drawMarker(canvas, rangeEndMs, videoLength, markerPaintB);
        }
    }

    private static void drawMarker(Canvas canvas, long timeMs, long videoLength, Paint paint) {
        if (timeMs < 0) return;

        float ratio = (float) timeMs / videoLength;
        if (ratio < 0f) ratio = 0f;
        if (ratio > 1f) ratio = 1f;

        float x = seekbarLeft + ratio * (seekbarRight - seekbarLeft);
        float pad = Math.max(6f, seekbarBottom - seekbarTop);
        canvas.drawLine(x, seekbarTop - pad, x, seekbarBottom + pad, paint);
    }

    /** Injection point: scrub time formatting. */
    public static String formatScrubOrDefault(String defaultValue) {
        if (!scrubbing || !Settings.LOOP_VIDEO.get()) return defaultValue;
        return formatMillis(scrubTimeMs);
    }

    /** Injection point: remaining-time scrub formatting. */
    public static String formatScrubRemainingOrDefault(String defaultValue) {
        if (!scrubbing || !Settings.LOOP_VIDEO.get()) return defaultValue;
        long remainingMs = VideoInformation.getVideoLength() - scrubTimeMs;
        if (remainingMs < 0) remainingMs = 0;
        return "-" + formatMillis(remainingMs);
    }

    private static String formatMillis(long timeMs) {
        if (timeMs < 0) timeMs = 0;
        return String.format(Locale.US, "%d:%02d.%03d",
                timeMs / 60000, (timeMs / 1000) % 60, timeMs % 1000);
    }

    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
