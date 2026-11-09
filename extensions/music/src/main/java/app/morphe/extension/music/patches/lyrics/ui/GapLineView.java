/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ui.Dim;

/**
 * Three dots in the place of the next line during a long instrumental break, which fill up one
 * after another as the break runs out. Open, it takes a row of its own in the lyrics.
 */
final class GapLineView extends View {
    /**
     * @param endMs            When the dots are full and start to close.
     * @param nextStartMs      When the line after the break starts.
     * @param beforeLineIndex  The line the break comes before.
     */
    record GapInterval(
            long startMs,
            long endMs,
            long nextStartMs,
            int beforeLineIndex,
            boolean isRightAligned,
            boolean isCentered,
            boolean isRtl) {
    }

    /** The dots show up this long after the line before the break ends. */
    static final long LYRICS_GAP_START_LAG_MS = 310L;
    private static final long GAP_SHOW_DELAY_MS = 300L;
    private static final int GAP_FADE_MS = 200;
    private static final long GAP_HIDE_FADE_DELAY_MS = 500L;
    private static final int GAP_ENDED_MS = 800;
    private static final long GAP_CLOSE_MS = 850L;
    /** How long before the next line the dots start to close. */
    static final long GAP_EXIT_DURATION_MS = GAP_ENDED_MS;

    private static final TimeInterpolator GAP_EASE = LyricsViewSupport.EASE;
    private static final float INTERVAL_DOT_RADIUS_DP = 5.0f;
    private static final float INTERVAL_DOT_GAP_DP = 8.0f;
    private static final int INTERVAL_SECONDARY_ALPHA = 0x55;
    private static final int INTERVAL_PRIMARY_ALPHA = 0xFF;
    static final float TARGET_HEIGHT_DP = 38f;

    private final GapInterval interval;
    @Nullable
    private LyricsScrollEngine scrollEngine;
    private int lastReportedLayoutH = 0;
    private int committedLayoutH = 0;
    private boolean isActive = false;
    private boolean isOpen = false;
    private boolean isExiting = false;
    private float groupScale = 0f;
    private float groupAlpha = 0f;
    @Nullable
    private ValueAnimator expandAnimator;
    @Nullable
    private ValueAnimator exitAnimator;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    GapLineView(Context context, GapInterval interval) {
        super(context);
        this.interval = interval;
    }

    void setScrollEngine(@Nullable LyricsScrollEngine engine) {
        this.scrollEngine = engine;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        destroy();
    }

    GapInterval getInterval() {
        return interval;
    }

    /**
     * Net layout delta that has been requested on this view's LayoutParams but not yet
     * applied by a layout pass. Used when computing scroll targets in the same frame a
     * gap opens or collapses.
     */
    int getPendingLayoutDelta() {
        return lastReportedLayoutH - committedLayoutH;
    }

    /** Closes the dots at once. */
    void hide() {
        resetImmediate();
    }

    void updateState(long currentPos, boolean active) {
        if (active) {
            if (exitAnimator != null && exitAnimator.isRunning()) {
                exitAnimator.cancel();
                exitAnimator = null;
            }
            if (!isActive) {
                isActive = true;
                startExpandAnimation();
            }
            invalidate();
        } else {
            if (isActive) {
                isActive = false;
                if (expandAnimator != null && expandAnimator.isRunning()) {
                    expandAnimator.cancel();
                    expandAnimator = null;
                }
                if (currentPos >= interval.endMs() && currentPos < interval.nextStartMs()) {
                    startExitAnimation();
                } else {
                    resetImmediate();
                }
            } else if (exitAnimator != null && exitAnimator.isRunning()) {
                if (currentPos < interval.startMs() || currentPos >= interval.nextStartMs()) {
                    resetImmediate();
                }
            } else if (isOpen && !isExiting && currentPos >= interval.endMs() && currentPos < interval.nextStartMs()) {
                startExitAnimation();
            } else if (isOpen && (currentPos < interval.startMs() || currentPos >= interval.nextStartMs())) {
                resetImmediate();
            }
        }
    }

    /**
     * Animates entrance for active gap line indicator:
     * - Layout opens immediately
     * - Scale snaps to 1.0f
     * - Alpha waits GAP_SHOW_DELAY_MS then fades in over GAP_FADE_MS with GAP_EASE
     */
    private void startExpandAnimation() {
        if (expandAnimator != null) expandAnimator.cancel();
        if (exitAnimator != null) exitAnimator.cancel();

        isOpen = true;
        isExiting = false;
        groupScale = 1.0f;
        final float startAlpha = groupAlpha;
        updateLayoutState();

        final long totalMs = GAP_SHOW_DELAY_MS + GAP_FADE_MS;

        expandAnimator = ValueAnimator.ofFloat(0.0f, (float) totalMs);
        expandAnimator.setDuration(totalMs);
        expandAnimator.setInterpolator(new LinearInterpolator());
        expandAnimator.addUpdateListener(anim -> {
            float elapsed = (float) anim.getAnimatedValue();
            if (elapsed < GAP_SHOW_DELAY_MS) {
                groupAlpha = startAlpha;
                groupScale = 0.5f;
            } else {
                float f = Math.max(0f, Math.min(1f, (elapsed - GAP_SHOW_DELAY_MS) / (float) GAP_FADE_MS));
                float eased = GAP_EASE.getInterpolation(f);
                groupAlpha = startAlpha + (1.0f - startAlpha) * eased;
                groupScale = 0.5f + 0.5f * eased;
            }
            invalidate();
        });
        expandAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                expandAnimator = null;
                groupScale = 1.0f;
                groupAlpha = 1.0f;
                invalidate();
            }
        });
        expandAnimator.start();
    }

    /**
     * Animates exit transition when instrumental gap ends:
     * - Scale keyframes over 800ms (GAP_ENDED_MS): 0% -> 1.0, 35% -> 1.2, 100% -> 0.0
     * - Alpha waits 500ms (GAP_HIDE_FADE_DELAY_MS) then fades 1.0 -> 0.0 over 200ms (GAP_FADE_MS)
     *   so the 800ms exit bounce is never clipped and the upcoming active lyric line never jumps later.
     */
    private void startExitAnimation() {
        if (expandAnimator != null) expandAnimator.cancel();
        if (exitAnimator != null) exitAnimator.cancel();

        isOpen = true;
        isExiting = true;
        if (groupAlpha <= 0.05f) {
            groupAlpha = 1.0f;
        }
        updateLayoutState();

        final long remaining = Math.max(150L, interval.nextStartMs() - LyricsManager.getInstance().getPositionMs());
        final long duration = Math.min(GAP_CLOSE_MS, Math.max((long) GAP_ENDED_MS, remaining));
        final float timeScale = (float) GAP_CLOSE_MS / (float) duration;

        exitAnimator = ValueAnimator.ofFloat(0.0f, (float) duration);
        exitAnimator.setDuration(duration);
        exitAnimator.setInterpolator(new LinearInterpolator());
        exitAnimator.addUpdateListener(anim -> {
            float rawElapsed = (float) anim.getAnimatedValue();
            float virtualMs = rawElapsed * timeScale;

            float peakTime = GAP_ENDED_MS * 0.35f;
            if (virtualMs <= peakTime) {
                float k = Math.max(0f, Math.min(1f, virtualMs / peakTime));
                groupScale = 1.0f + 0.2f * GAP_EASE.getInterpolation(k);
            } else if (virtualMs <= GAP_ENDED_MS) {
                float k = Math.max(0f, Math.min(1f, (virtualMs - peakTime) / (GAP_ENDED_MS - peakTime)));
                groupScale = 1.2f * (1.0f - GAP_EASE.getInterpolation(k));
            } else {
                groupScale = 0f;
            }

            if (virtualMs < GAP_HIDE_FADE_DELAY_MS) {
                groupAlpha = 1.0f;
            } else if (virtualMs <= GAP_HIDE_FADE_DELAY_MS + GAP_FADE_MS) {
                float k = Math.max(0f, Math.min(1f, (virtualMs - GAP_HIDE_FADE_DELAY_MS) / (float) GAP_FADE_MS));
                groupAlpha = 1.0f - GAP_EASE.getInterpolation(k);
            } else {
                groupAlpha = 0f;
            }

            invalidate();
        });
        exitAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                exitAnimator = null;
                resetImmediate();
            }
        });
        exitAnimator.start();
    }

    private void resetImmediate() {
        if (expandAnimator != null) {
            expandAnimator.cancel();
            expandAnimator = null;
        }
        if (exitAnimator != null) {
            exitAnimator.cancel();
            exitAnimator = null;
        }
        isActive = false;
        isOpen = false;
        isExiting = false;
        groupScale = 0f;
        groupAlpha = 0f;
        updateLayoutState();
        invalidate();
    }

    private void updateLayoutState() {
        int fullH = Dim.dp(TARGET_HEIGHT_DP);
        final boolean springOn = Settings.LYRICS_SPRING_SCROLL.get();
        int netLayoutH = (isOpen && !isExiting) ? fullH : 0;
        int canvasH = (isOpen || isExiting) ? fullH : 0;
        int bottomMargin = (isOpen && isExiting && springOn) ? -fullH : 0;

        if (lastReportedLayoutH != netLayoutH) {
            int delta = netLayoutH - lastReportedLayoutH;
            lastReportedLayoutH = netLayoutH;
            if (scrollEngine != null) {
                scrollEngine.recordLayoutShift(this, delta);
            }
        }

        ViewGroup.LayoutParams rawLp = getLayoutParams();
        boolean changed = false;
        if (rawLp != null) {
            if (rawLp.height != canvasH) {
                rawLp.height = canvasH;
                changed = true;
            }
            if (rawLp instanceof ViewGroup.MarginLayoutParams mlp && mlp.bottomMargin != bottomMargin) {
                mlp.bottomMargin = bottomMargin;
                changed = true;
            }
        }
        if (getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
            changed = true;
        }
        if (changed) {
            requestLayout();
        }
    }

    void destroy() {
        if (expandAnimator != null) expandAnimator.cancel();
        if (exitAnimator != null) exitAnimator.cancel();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (isOpen || isExiting) ? Dim.dp(TARGET_HEIGHT_DP) : 0;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int h = bottom - top;
        ViewGroup.LayoutParams lp = getLayoutParams();
        int bm = (lp instanceof ViewGroup.MarginLayoutParams mlp) ? mlp.bottomMargin : 0;
        committedLayoutH = Math.max(0, h + bm);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if ((!isOpen && !isExiting) || getHeight() <= 0 || groupAlpha <= 0f || groupScale <= 0f) {
            return;
        }

        long currentTime = LyricsManager.getInstance().getPositionMs();
        long gapDuration = interval.endMs() - interval.startMs();
        if (gapDuration <= 0) return;

        float segmentDurationMs = (float) gapDuration / 3f;
        float syllableDuration = segmentDurationMs * 0.7f;
        float gapPadding = segmentDurationMs * 0.3f;

        float currentScale = groupScale;
        if (isActive && !isExiting) {
            // Continuous idle pulsing: scale between 1.1 and 0.9 with alternate cycle
            long elapsedSinceStart = Math.max(0L, currentTime - interval.startMs());
            long mod = elapsedSinceStart % 8000L;
            float loopScale;
            if (mod < 4000L) {
                loopScale = 1.1f - 0.2f * GAP_EASE.getInterpolation(mod / 4000f);
            } else {
                loopScale = 0.9f + 0.2f * GAP_EASE.getInterpolation((mod - 4000L) / 4000f);
            }
            currentScale = groupScale * loopScale;
        }

        final float r = Dim.dp(INTERVAL_DOT_RADIUS_DP);
        final float gapPx = Dim.dp(INTERVAL_DOT_GAP_DP);
        final float step = 2f * r + gapPx;
        final float totalWidth = 2f * step + 2f * r;

        final float textMargin = Math.max(Dim.dp(3f), LyricsViewSupport.sectionDotInset(getContext()));
        final float startX;
        if (interval.isCentered()) {
            startX = (getWidth() - totalWidth) / 2f;
        } else if (interval.isRightAligned()) {
            startX = getWidth() - totalWidth - textMargin;
        } else {
            startX = textMargin;
        }
        final float groupCenterX = startX + totalWidth / 2f;
        final float cy = getHeight() * 0.38f;

        int baseColor = LyricsColors.lineTextColor();
        int red = Color.red(baseColor);
        int green = Color.green(baseColor);
        int blue = Color.blue(baseColor);

        canvas.save();
        canvas.scale(currentScale, currentScale, groupCenterX, cy);

        for (int i = 0; i < 3; i++) {
            float sylStart = interval.startMs() + (i * segmentDurationMs) + gapPadding;
            float sylEnd = sylStart + syllableDuration;

            float dotFillAlpha;
            float dotScale = 1.0f;
            if (isExiting || currentTime >= sylEnd) {
                dotFillAlpha = (float) INTERVAL_PRIMARY_ALPHA;
            } else if (currentTime < sylStart) {
                dotFillAlpha = (float) INTERVAL_SECONDARY_ALPHA;
            } else {
                float t = (float) (currentTime - sylStart) / Math.max(1f, syllableDuration);
                t = Math.max(0f, Math.min(1f, t));
                float interpolated = GAP_EASE.getInterpolation(t);
                dotFillAlpha = INTERVAL_SECONDARY_ALPHA + (INTERVAL_PRIMARY_ALPHA - INTERVAL_SECONDARY_ALPHA) * interpolated;
                dotScale = 1.0f + 0.22f * (float) Math.sin(t * Math.PI);
            }

            int finalAlpha = Math.max(0, Math.min(255, Math.round(dotFillAlpha * groupAlpha)));
            dotPaint.setColor(Color.argb(finalAlpha, red, green, blue));

            float cx = interval.isRtl()
                    ? (startX + totalWidth - r - i * step)
                    : (startX + r + i * step);
            canvas.drawCircle(cx, cy, r * dotScale, dotPaint);
        }

        canvas.restore();

        if ((isActive || isExiting || (exitAnimator != null && exitAnimator.isRunning()))
                && LyricsManager.getInstance().isPlaying()) {
            postInvalidateOnAnimation();
        }
    }
}
