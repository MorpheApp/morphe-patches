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
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ui.Dim;

/**
 * Three dots in the place of the next line during a long instrumental break, which fill up one
 * after another as the break runs out. Open, it takes a row of its own in the lyrics.
 * Created in code with its break, and never inflated from a layout, so it has no layout constructors.
 */
@SuppressLint("ViewConstructor")
final class GapLineView extends View {
    /**
     * @param endMs           When the dots are full and start to close.
     * @param nextStartMs     When the line after the break starts.
     * @param beforeLineIndex The line the break comes before.
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
    private static final long GAP_SHOW_DELAY_MS = 260L;
    private static final int GAP_ENTER_DURATION_MS = 320;
    private static final int GAP_FADE_MS = 220;
    private static final long GAP_HIDE_FADE_DELAY_MS = 480L;
    private static final int GAP_ENDED_MS = 780;
    private static final long GAP_CLOSE_MS = 820L;
    /** How long before the next line the dots start to close. */
    static final long GAP_EXIT_DURATION_MS = GAP_ENDED_MS;

    private static final TimeInterpolator GAP_EASE = LyricsViewSupport.EASE;
    private static final TimeInterpolator GAP_ENTER_EASE = new android.view.animation.PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
    private static final float INTERVAL_DOT_RADIUS_DP = 5.0f;
    private static final float INTERVAL_DOT_GAP_DP = 8.5f;
    private static final int INTERVAL_SECONDARY_ALPHA = 0x52;
    private static final int INTERVAL_PRIMARY_ALPHA = 0xFF;
    static final float TARGET_HEIGHT_DP = 44f;
    static final float EXTRA_SECTION_CLEARANCE_DP = 10f;

    private final GapInterval interval;
    @Nullable
    private LyricsScrollEngine scrollEngine;
    private boolean hasTagBelow = false;
    private int lastReportedLayoutH = 0;
    private int committedLayoutH = 0;
    private int animatedCollapseH = -1;
    private boolean isActive = false;
    private boolean isOpen = false;
    private boolean isExiting = false;
    private float groupScale = 0f;
    private float groupAlpha = 0f;
    private float lastRenderedScale = 1.0f;
    private float enterProgress = 1.0f;
    @Nullable
    private ValueAnimator expandAnimator;
    @Nullable
    private ValueAnimator exitAnimator;
    @Nullable
    private ValueAnimator collapseAnimator;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LyricsViewSupport.BlurTarget blurTarget = new LyricsViewSupport.BlurTarget(this);

    GapLineView(Context context, GapInterval interval) {
        super(context);
        this.interval = interval;
    }

    void setScrollEngine(@Nullable LyricsScrollEngine engine) {
        this.scrollEngine = engine;
    }

    void setHasTagBelow(boolean hasTagBelow) {
        if (this.hasTagBelow != hasTagBelow) {
            this.hasTagBelow = hasTagBelow;
            if (isOpen || isExiting) {
                updateLayoutState();
            }
        }
    }

    int targetOpenHeightPx() {
        float dp = TARGET_HEIGHT_DP + (hasTagBelow ? EXTRA_SECTION_CLEARANCE_DP : 0f);
        return Dim.dp(dp);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        destroy();
    }

    GapInterval getInterval() {
        return interval;
    }

    boolean isGapActive() {
        return isActive || isOpen || (exitAnimator != null && exitAnimator.isRunning());
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
            if (collapseAnimator != null && collapseAnimator.isRunning()) {
                collapseAnimator.cancel();
                collapseAnimator = null;
                animatedCollapseH = -1;
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
                if (currentPos >= interval.endMs() && currentPos < interval.nextStartMs() + GAP_CLOSE_MS) {
                    startExitAnimation();
                } else {
                    resetImmediate();
                }
            } else if (exitAnimator != null && exitAnimator.isRunning()) {
                // Only abort an in-flight exit animation on a seek/jump outside the transition window;
                // never cut off the final frames right as playback crosses nextStartMs.
                if (isSeekOutsideExitWindow(currentPos)) {
                    resetImmediate();
                }
            } else if (isOpen && !isExiting && currentPos >= interval.endMs() && currentPos < interval.nextStartMs()) {
                startExitAnimation();
            } else if (isOpen && !isExiting && (collapseAnimator == null || !collapseAnimator.isRunning())
                    && isOutsideBreak(currentPos)) {
                resetImmediate();
            }
        }
    }

    /**
     * @return If the position is before the break, or at or after the line that follows it.
     */
    private boolean isOutsideBreak(long currentPos) {
        return currentPos < interval.startMs() || currentPos >= interval.nextStartMs();
    }

    private boolean isSeekOutsideExitWindow(long currentPos) {
        return currentPos < interval.startMs() || currentPos > interval.nextStartMs() + GAP_CLOSE_MS + 250L;
    }

    /**
     * Animates seamless entrance for active gap line indicator:
     * - Layout opens immediately without overlapping neighboring section tags
     * - Smoothly scales from 0.58 -> 1.0 (or live scale if re-entering) with staggered dot bloom
     */
    private void startExpandAnimation() {
        if (expandAnimator != null) expandAnimator.cancel();
        if (exitAnimator != null) exitAnimator.cancel();
        if (collapseAnimator != null) {
            collapseAnimator.cancel();
            collapseAnimator = null;
        }
        animatedCollapseH = -1;

        final boolean reenteringVisible = (isOpen || isExiting) && groupAlpha > 0.02f;
        isOpen = true;
        isExiting = false;
        final float startAlpha = reenteringVisible ? groupAlpha : 0f;
        final float startScale = reenteringVisible ? Math.max(0.58f, lastRenderedScale) : 0.58f;
        if (!reenteringVisible) {
            groupAlpha = 0f;
            groupScale = startScale;
            enterProgress = 0f;
        }
        updateLayoutState();

        final long delayMs = reenteringVisible ? 0L : GAP_SHOW_DELAY_MS;
        final long totalMs = delayMs + GAP_ENTER_DURATION_MS;

        expandAnimator = ValueAnimator.ofFloat(0.0f, (float) totalMs);
        expandAnimator.setDuration(totalMs);
        expandAnimator.setInterpolator(new LinearInterpolator());
        expandAnimator.addUpdateListener(anim -> {
            float elapsed = (float) anim.getAnimatedValue();
            if (elapsed < delayMs) {
                groupAlpha = startAlpha;
                groupScale = startScale;
                enterProgress = 0f;
            } else {
                float f = Math.max(0f, Math.min(1f, (elapsed - delayMs) / (float) GAP_ENTER_DURATION_MS));
                float eased = GAP_ENTER_EASE.getInterpolation(f);
                enterProgress = eased;
                groupAlpha = startAlpha + (1.0f - startAlpha) * GAP_EASE.getInterpolation(f);
                groupScale = startScale + (1.0f - startScale) * eased;
            }
            invalidate();
        });
        expandAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                expandAnimator = null;
                if (!cancelled) {
                    groupScale = 1.0f;
                    groupAlpha = 1.0f;
                    enterProgress = 1.0f;
                    invalidate();
                }
            }
        });
        expandAnimator.start();
    }

    /**
     * Animates exit when the instrumental break ends: starts C0-continuously from
     * {@code lastRenderedScale} -> 1.18x anticipation pop -> 0.0x collapse, calibrated to finish
     * cleanly as the next line begins word sync.
     */
    private void startExitAnimation() {
        if (expandAnimator != null) {
            expandAnimator.cancel();
            expandAnimator = null;
        }
        if (exitAnimator != null) {
            exitAnimator.cancel();
            exitAnimator = null;
        }
        if (collapseAnimator != null) {
            collapseAnimator.cancel();
            collapseAnimator = null;
        }
        animatedCollapseH = -1;

        if (groupAlpha <= 0.02f) {
            resetImmediate();
            return;
        }

        isOpen = true;
        isExiting = true;
        enterProgress = 1.0f;
        final float startAlpha = groupAlpha;
        final float startScale = Math.max(0.25f, lastRenderedScale);
        final float peakScale = Math.max(startScale + 0.08f, 1.18f);
        updateLayoutState();

        final long posNow = LyricsManager.getInstance().getPositionMs();
        final long remainingToNext = interval.nextStartMs() - posNow;
        final long duration = remainingToNext > 80L
                ? Math.min(GAP_ENDED_MS, remainingToNext)
                : GAP_ENDED_MS;
        final float timeScale = (float) GAP_ENDED_MS / (float) Math.max(1L, duration);

        exitAnimator = ValueAnimator.ofFloat(0.0f, (float) duration);
        exitAnimator.setDuration(duration);
        exitAnimator.setInterpolator(new LinearInterpolator());
        exitAnimator.addUpdateListener(anim -> {
            float rawElapsed = (float) anim.getAnimatedValue();
            float virtualMs = rawElapsed * timeScale;

            float peakTime = GAP_ENDED_MS * 0.35f;
            if (virtualMs <= peakTime) {
                float k = Math.max(0f, Math.min(1f, virtualMs / peakTime));
                groupScale = startScale + (peakScale - startScale) * GAP_EASE.getInterpolation(k);
            } else if (virtualMs <= GAP_ENDED_MS) {
                float k = Math.max(0f, Math.min(1f, (virtualMs - peakTime) / (GAP_ENDED_MS - peakTime)));
                groupScale = peakScale * (1.0f - GAP_EASE.getInterpolation(k));
            } else {
                groupScale = 0f;
            }

            if (virtualMs < GAP_HIDE_FADE_DELAY_MS) {
                groupAlpha = startAlpha;
            } else if (virtualMs <= GAP_HIDE_FADE_DELAY_MS + GAP_FADE_MS) {
                float k = Math.max(0f, Math.min(1f, (virtualMs - GAP_HIDE_FADE_DELAY_MS) / (float) GAP_FADE_MS));
                groupAlpha = startAlpha * (1.0f - GAP_EASE.getInterpolation(k));
            } else {
                groupAlpha = 0f;
            }

            invalidate();
        });
        exitAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                exitAnimator = null;
                if (!cancelled) {
                    finishSeamlessCollapse();
                }
            }
        });
        exitAnimator.start();
    }

    private void finishSeamlessCollapse() {
        isActive = false;
        isOpen = false;
        isExiting = false;
        groupScale = 0f;
        groupAlpha = 0f;
        lastRenderedScale = 0f;
        final boolean springOn = Settings.LYRICS_SPRING_SCROLL.get();
        if (springOn || getHeight() <= 0 || !isAttachedToWindow()) {
            animatedCollapseH = -1;
            updateLayoutState();
            invalidate();
            return;
        }
        final int startH = getHeight();
        animatedCollapseH = startH;
        collapseAnimator = ValueAnimator.ofInt(startH, 0);
        collapseAnimator.setDuration(240L);
        collapseAnimator.setInterpolator(GAP_EASE);
        collapseAnimator.addUpdateListener(anim -> {
            animatedCollapseH = (int) anim.getAnimatedValue();
            updateLayoutState();
        });
        collapseAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                collapseAnimator = null;
                animatedCollapseH = -1;
                updateLayoutState();
            }
        });
        collapseAnimator.start();
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
        if (collapseAnimator != null) {
            collapseAnimator.cancel();
            collapseAnimator = null;
        }
        animatedCollapseH = -1;
        isActive = false;
        isOpen = false;
        isExiting = false;
        groupScale = 0f;
        groupAlpha = 0f;
        lastRenderedScale = 0f;
        enterProgress = 0f;
        updateLayoutState();
        invalidate();
    }

    private void updateLayoutState() {
        int fullH = targetOpenHeightPx();
        int desiredH;
        if (isOpen || isExiting) {
            desiredH = fullH;
        } else if (animatedCollapseH >= 0) {
            desiredH = animatedCollapseH;
        } else {
            desiredH = 0;
        }

        if (lastReportedLayoutH != desiredH) {
            int delta = desiredH - lastReportedLayoutH;
            lastReportedLayoutH = desiredH;
            if (scrollEngine != null) {
                scrollEngine.recordLayoutShift(this, delta);
            }
        }

        ViewGroup.LayoutParams rawLp = getLayoutParams();
        boolean changed = false;
        if (rawLp != null) {
            if (rawLp.height != desiredH) {
                rawLp.height = desiredH;
                changed = true;
            }
            if (rawLp instanceof ViewGroup.MarginLayoutParams mlp && mlp.bottomMargin != 0) {
                mlp.bottomMargin = 0;
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

    void animateBlur(float targetRadius, boolean instant) {
        blurTarget.animateTo(targetRadius, instant, false);
    }

    void destroy() {
        blurTarget.cancel();
        if (expandAnimator != null) expandAnimator.cancel();
        if (exitAnimator != null) exitAnimator.cancel();
        if (collapseAnimator != null) collapseAnimator.cancel();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height;
        if (isOpen || isExiting) {
            height = targetOpenHeightPx();
        } else if (animatedCollapseH >= 0) {
            height = animatedCollapseH;
        } else {
            height = 0;
        }
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
    protected void onDraw(@NonNull Canvas canvas) {
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
            // Smooth 4s alternate group breathing (0.9x <-> 1.1x) blended in after entrance.
            long elapsedSinceStart = Math.max(0L, currentTime - interval.startMs());
            float breatheBlend = Math.min(1f, elapsedSinceStart / 600f) * enterProgress;
            float cosWave = (float) Math.cos((elapsedSinceStart / 4000.0) * Math.PI);
            float loopScale = 1.0f + 0.10f * breatheBlend * cosWave;
            currentScale = groupScale * loopScale;
        }
        lastRenderedScale = currentScale;

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
        final int extraClearancePx = hasTagBelow ? Dim.dp(EXTRA_SECTION_CLEARANCE_DP) : 0;
        final float usableHeight = Math.max(1f, getHeight() - extraClearancePx);
        final float cy = usableHeight * 0.48f;

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
            if (isExiting || currentTime >= sylEnd) {
                dotFillAlpha = (float) INTERVAL_PRIMARY_ALPHA;
            } else if (currentTime < sylStart) {
                dotFillAlpha = (float) INTERVAL_SECONDARY_ALPHA;
            } else {
                float t = (currentTime - sylStart) / Math.max(1f, syllableDuration);
                t = Math.max(0f, Math.min(1f, t));
                float interpolated = GAP_EASE.getInterpolation(t);
                dotFillAlpha = INTERVAL_SECONDARY_ALPHA
                        + (INTERVAL_PRIMARY_ALPHA - INTERVAL_SECONDARY_ALPHA) * interpolated;
            }

            float cx = interval.isRtl()
                    ? (startX + totalWidth - r - i * step)
                    : (startX + r + i * step);

            int finalAlpha = Math.max(0, Math.min(255, Math.round(dotFillAlpha * groupAlpha)));
            dotPaint.setColor(Color.argb(finalAlpha, red, green, blue));
            canvas.drawCircle(cx, cy, r, dotPaint);
        }

        canvas.restore();

        if ((isActive || isExiting || (expandAnimator != null && expandAnimator.isRunning())
                || (exitAnimator != null && exitAnimator.isRunning()))
                && LyricsManager.getInstance().isPlaying()) {
            postInvalidateOnAnimation();
        }
    }
}
