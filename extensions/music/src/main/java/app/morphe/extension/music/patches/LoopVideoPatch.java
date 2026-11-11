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
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class LoopVideoPatch {

    private static final long SEEK_COOLDOWN_MS = 2000;

    /** Playback positions this close before the loop start count as inside the loop. */
    private static final long RANGE_TOLERANCE_MS = 250;

    /** How far from the seekbar a touch still counts as a long press on it. */
    private static final float TOUCH_TOLERANCE_DP = 24;

    private static final float MARKER_WIDTH_PX = 4;
    private static final float MARKER_MIN_OVERHANG_PX = 6;

    private static final Paint markerStartPaint = createMarkerPaint(Color.rgb(16, 185, 129));
    private static final Paint markerEndPaint = createMarkerPaint(Color.rgb(59, 130, 246));

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static final Runnable longPressRunnable = LoopVideoPatch::onLongPress;

    private static volatile long rangeStartMs = -1;
    private static volatile long rangeEndMs = -1;

    /** Video the loop points belong to. */
    private static volatile String rangeVideoId = "";

    private static long lastSeekTimeMs;

    /**
     * Bounds of the seekbar thumb center, from the time bar Rect.
     * Updated on every draw, so they are always set before any touch.
     */
    private static final Rect seekbarBounds = new Rect();

    private static boolean scrubbing;
    private static long scrubTimeMs;
    private static float downX;
    private static float downY;

    private static Paint createMarkerPaint(int color) {
        Paint paint = new Paint();
        paint.setColor(color);
        paint.setStrokeWidth(MARKER_WIDTH_PX);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setAntiAlias(true);
        return paint;
    }

    private static boolean isRangeActive() {
        return rangeStartMs >= 0 && rangeEndMs > rangeStartMs;
    }

    private static void clearRange() {
        rangeStartMs = -1;
        rangeEndMs = -1;
        rangeVideoId = "";
    }

    private static long clampToRange(long timeMs) {
        if (!isRangeActive()) return timeMs;
        return Math.max(rangeStartMs, Math.min(rangeEndMs, timeMs));
    }

    private static boolean hasSeekbarBounds() {
        return seekbarBounds.right > seekbarBounds.left;
    }

    private static long timeFromX(float x, long videoLength) {
        float ratio = (x - seekbarBounds.left) / seekbarBounds.width();
        return (long) (Math.max(0, Math.min(1, ratio)) * videoLength);
    }

    private static float xFromTime(long timeMs, long videoLength) {
        float ratio = (float) timeMs / videoLength;
        return seekbarBounds.left + Math.max(0, Math.min(1, ratio)) * seekbarBounds.width();
    }

    private static String formatMillis(long timeMs) {
        timeMs = Math.max(0, timeMs);
        final long hours = TimeUnit.MILLISECONDS.toHours(timeMs);
        final long minutes = TimeUnit.MILLISECONDS.toMinutes(timeMs) % 60;
        final long seconds = TimeUnit.MILLISECONDS.toSeconds(timeMs) % 60;
        final long millis = timeMs % 1000;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis);
        }
        return String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, millis);
    }

    /**
     * Injection point.
     */
    public static void videoIdChanged(String videoId) {
        if (Settings.AB_LOOP.get() && rangeStartMs >= 0 && !rangeVideoId.equals(videoId)) {
            clearRange();
        }
    }

    /**
     * Injection point. Called about once a second.
     */
    public static void videoTimeChanged(long time) {
        try {
            if (!Settings.AB_LOOP.get() || !isRangeActive() || scrubbing || time < 0) {
                return;
            }

            // Playback crosses the loop end without any seek, so it is pulled back here.
            if (time >= rangeStartMs - RANGE_TOLERANCE_MS && time < rangeEndMs) return;

            final long now = System.currentTimeMillis();
            if (now - lastSeekTimeMs < SEEK_COOLDOWN_MS) return;
            lastSeekTimeMs = now;

            VideoInformation.seekTo(rangeStartMs);
        } catch (Exception ex) {
            Logger.printException(() -> "videoTimeChanged failure", ex);
        }
    }

    /**
     * Injection point. Keeps player seeks (in app and from the media session) inside the loop.
     */
    public static long clampSeekPosition(long time) {
        if (!Settings.AB_LOOP.get()) return time;
        return clampToRange(time);
    }

    /**
     * Injection point. Keeps the dragged seekbar thumb inside the loop.
     */
    public static float clampSeekX(float x) {
        try {
            if (!Settings.AB_LOOP.get() || !isRangeActive() || !hasSeekbarBounds()) {
                return x;
            }

            final long videoLength = VideoInformation.getVideoLength();
            if (videoLength <= 0) return x;

            return Math.max(xFromTime(rangeStartMs, videoLength),
                    Math.min(xFromTime(rangeEndMs, videoLength), x));
        } catch (Exception ex) {
            Logger.printException(() -> "clampSeekX failure", ex);
            return x;
        }
    }

    /**
     * Injection point. Tracks long presses and dragging. Never consumes the event.
     */
    public static void onTouchEvent(View view, MotionEvent event) {
        if (!Settings.AB_LOOP.get()) return;

        try {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    resetTouchState();
                    final float x = event.getX();
                    final float y = event.getY();
                    if (!view.isEnabled() || !isOnSeekbar(view, x, y)) return;

                    scrubbing = true;
                    downX = x;
                    downY = y;
                    updateScrubTime(x);
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                    view.invalidate();
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (!scrubbing) return;

                    final int touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                    if (Math.abs(event.getX() - downX) > touchSlop
                            || Math.abs(event.getY() - downY) > touchSlop) {
                        handler.removeCallbacks(longPressRunnable);
                    }
                    updateScrubTime(event.getX());
                    view.invalidate();
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetTouchState();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onTouchEvent failure", ex);
        }
    }

    private static void resetTouchState() {
        scrubbing = false;
        handler.removeCallbacks(longPressRunnable);
    }

    private static void updateScrubTime(float x) {
        scrubTimeMs = clampToRange(timeFromX(clampSeekX(x), VideoInformation.getVideoLength()));
    }

    private static boolean isOnSeekbar(View view, float x, float y) {
        if (!hasSeekbarBounds()) return false;

        final float tolerancePx = TOUCH_TOLERANCE_DP * view.getResources().getDisplayMetrics().density;
        return x >= seekbarBounds.left - tolerancePx && x <= seekbarBounds.right + tolerancePx
                && Math.abs(y - seekbarBounds.exactCenterY()) <= tolerancePx;
    }

    /**
     * First long press sets the loop start, the second sets the loop end, the third clears the loop.
     */
    private static void onLongPress() {
        try {
            final long videoLength = VideoInformation.getVideoLength();
            if (videoLength <= 0 || !hasSeekbarBounds()) return;

            final long timeMs = timeFromX(downX, videoLength);

            if (rangeStartMs < 0) {
                rangeStartMs = timeMs;
                rangeVideoId = VideoInformation.getVideoId();
                Utils.showToastShort(str("morphe_music_ab_loop_toast_start", formatMillis(timeMs)));
            } else if (rangeEndMs < 0) {
                if (timeMs > rangeStartMs) {
                    rangeEndMs = timeMs;
                    Utils.showToastShort(str("morphe_music_ab_loop_toast_end", formatMillis(timeMs)));
                } else {
                    // Pressed before the start, so the old start becomes the end.
                    rangeEndMs = rangeStartMs;
                    rangeStartMs = timeMs;
                    Utils.showToastShort(str("morphe_music_ab_loop_toast_start", formatMillis(timeMs)));
                }
            } else {
                clearRange();
                Utils.showToastShort(str("morphe_music_ab_loop_toast_cleared"));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLongPress failure", ex);
        }
    }

    /**
     * Injection point. Called at the end of the time bar draw.
     */
    public static void drawLoopMarkers(Canvas canvas, Rect seekbarRect) {
        if (!Settings.AB_LOOP.get()) return;

        try {
            seekbarBounds.set(seekbarRect);
            if (rangeStartMs < 0 || !hasSeekbarBounds()) return;

            final long videoLength = VideoInformation.getVideoLength();
            if (videoLength <= 0) return;

            drawMarker(canvas, rangeStartMs, videoLength, markerStartPaint);
            if (rangeEndMs >= 0) {
                drawMarker(canvas, rangeEndMs, videoLength, markerEndPaint);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "drawLoopMarkers failure", ex);
        }
    }

    private static void drawMarker(Canvas canvas, long timeMs, long videoLength, Paint paint) {
        final float x = xFromTime(timeMs, videoLength);
        final float overhang = Math.max(MARKER_MIN_OVERHANG_PX, seekbarBounds.height());
        canvas.drawLine(x, seekbarBounds.top - overhang, x, seekbarBounds.bottom + overhang, paint);
    }

    /**
     * Injection point. Elapsed time label, shown with milliseconds while dragging.
     */
    public static String formatElapsedTime(String original) {
        if (!Settings.AB_LOOP.get() || !scrubbing) return original;
        return formatMillis(scrubTimeMs);
    }

    /**
     * Injection point. Remaining time label, shown with milliseconds while dragging.
     */
    public static String formatRemainingTime(String original) {
        if (!Settings.AB_LOOP.get() || !scrubbing) return original;
        return "-" + formatMillis(VideoInformation.getVideoLength() - scrubTimeMs);
    }
}
