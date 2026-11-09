/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
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
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.icu.text.BreakIterator;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.ui.SpringEasing.LyricsSpring;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

final class LyricsLineView extends TextView {
    private static final int REVEAL_CONTENT = 0;
    private static final int REVEAL_TRANS = 1;
    private static final int REVEAL_ROMA = 2;

    /**
     * A background vocal opens over about this long when its lead line comes up, unless the
     * scroll sets its own time.
     */
    private static final long SECONDARY_EXPAND_MILLISECONDS = 400;
    /** A closed background vocal is drawn this much smaller, so it grows out of its lead line. */
    private static final float SECONDARY_CLOSED_SCALE = 0.85f;
    /** Room between a background vocal and its lead line. */
    private static final int SECONDARY_SPACING_PX = Dim.dp4;
    private static final TimeInterpolator TRANSITION_INTERPOLATOR = LyricsViewSupport.EASE;
    /** A line look change runs at least this long. */
    private static final int LINE_VISIBILITY_MS = 300;

    private List<LyricsSpanBuilder.WordTiming> wordTimings = Collections.emptyList();
    long positionMs = Long.MIN_VALUE;
    boolean allSung = false;
    private int unsungColor;
    private boolean sweepEnabled;
    int originalTextStart;
    private float[] lineMaxSungX;
    private Layout cachedLayout;
    /** Where the word starts and ends being sung, in the direction its script runs. */
    private float[] cachedWordLeadX;
    private float[] cachedWordTrailX;
    private int[] cachedWordLine;
    private int[] cachedWordWrappedLine;
    private int cachedWordCount;
    private int cachedLineCount;
    private int[] cachedLineTop;
    private int[] cachedLineBottom;
    private float[] cachedLineLeft;
    private float[] cachedLineRight;
    private int cachedOrigStart;
    private CharSequence cachedText;
    private String cachedTextStr;
    /** The hand-built layout of a line drawn with syllable readings, see {@link #syllableCacheFor}. */
    @Nullable
    private SyllableRomanCache syllableCache;

    @SuppressWarnings("ConstantValue")
    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        wordTimings = Collections.emptyList();
        positionMs = Long.MIN_VALUE;
        allSung = false;
        cachedLayout = null;
        cachedText = null;
        syllableCache = null;
        if (fittedState != null && !Objects.equals(fittedState.text, text)) {
            fittedState.width = -1;
        }
        invalidate();
    }

    private final LyricsFit.FittedState fittedState = new LyricsFit.FittedState();

    int transStart = -1;
    int transEnd = -1;
    int romaStart = -1;
    int romaEnd = -1;
    boolean perWordReading;
    boolean showSyllableRomanization;
    boolean rubyActive;
    boolean smallRunHoldsWords;
    float lastTouchY;
    float lastTouchX;
    private final Paint layerPaint = new Paint();
    private PorterDuffColorFilter tintFilter;
    private int tintFilterColor;
    private int baseTextAlpha = 255;
    float lineAlpha = 1f;
    float lineScale = 1f;
    float fadeTargetAlpha = 1f;
    boolean activeLine;
    ValueAnimator fadeAnimator;

    // The look a line is moving from, to, and is drawn with now: its alpha, the alphas of its
    // unsung words, translation and reading, and its scale.
    private boolean lineLookInitialized = false;
    private float lookFromAlpha = 1f;
    private float lookToAlpha = 1f;
    float lookCurAlpha = 1f;
    private float lookFromUnsungAlpha = LyricsColors.UNSUNG_ALPHA;
    private float lookToUnsungAlpha = LyricsColors.UNSUNG_ALPHA;
    float lookCurUnsungAlpha = LyricsColors.UNSUNG_ALPHA;
    private float lookFromTransAlpha = LyricsColors.ACTIVE_TRANSLATION_ALPHA;
    private float lookToTransAlpha = LyricsColors.ACTIVE_TRANSLATION_ALPHA;
    float lookCurTransAlpha = LyricsColors.ACTIVE_TRANSLATION_ALPHA;
    private float lookFromReadingAlpha = LyricsColors.ACTIVE_READING_ALPHA;
    private float lookToReadingAlpha = LyricsColors.ACTIVE_READING_ALPHA;
    float lookCurReadingAlpha = LyricsColors.ACTIVE_READING_ALPHA;
    private float lookFromScale = 1f;
    private float lookToScale = 1f;
    float lookCurScale = 1f;
    @Nullable
    private ValueAnimator lineLookAnimator;

    @Nullable
    private LyricsScrollEngine scrollEngine;
    private final LyricsScrollEngine.LyricsItemFlip mainVocalFlip = new LyricsScrollEngine.LyricsItemFlip();
    /** The translation glides on its own when the text above it changes height. */
    private final LyricsScrollEngine.LyricsItemFlip translationFlip = new LyricsScrollEngine.LyricsItemFlip();
    private float translationFlipOffsetY = 0f;

    float contentReveal = 1f;
    float transReveal = 1f;
    float romaReveal = 1f;
    private ValueAnimator contentRevealAnimator;
    private ValueAnimator transRevealAnimator;
    private ValueAnimator romaRevealAnimator;

    /** A background vocal, drawn in its lead line's row and only open around that line. */
    private boolean isSecondary;
    private int parentLineIndex = -1;
    /** Sung ahead of its lead line, so it sits above it and opens upwards. */
    private boolean bgBeforeMain;
    private boolean isRightAligned;
    /** Apple's centred vocal, whose row ignores the duet alignment. */
    private boolean isCentered;
    /** How far a background vocal is open. Its measured height follows this. */
    private float secondaryFraction = 1f;
    private int secondaryFullHeight;
    /** Whether the background vocal takes room in the layout, which it gives up at once when closing. */
    private boolean secondaryLayoutVisible = true;
    /** Closing, so drawn while a negative margin already hands its room back. */
    private boolean secondaryAnimatingExit = false;
    private int lastSecondaryReportedH = -1;
    private int committedSecondaryLayoutH = 0;
    @Nullable
    private ValueAnimator secondaryAnimator;

    LyricsLineView(Context context) {
        super(context);
    }

    void setScrollEngine(@Nullable LyricsScrollEngine engine) {
        this.scrollEngine = engine;
    }

    /**
     * {@return how much room a background vocal is about to take or give back, which the
     * layout has not applied yet}
     */
    int getPendingLayoutDelta() {
        if (!isSecondary) {
            return 0;
        }
        final int fullH = secondaryFullHeight > 0 ? secondaryFullHeight : getMeasuredHeight();
        final int targetH = secondaryLayoutVisible
                ? (fullH > 0 ? fullH + SECONDARY_SPACING_PX : Dim.dp(32))
                : 0;
        final int committedH = getVisibility() == View.GONE ? 0 : committedSecondaryLayoutH;
        return targetH - committedH;
    }

    /** {@return the playback position now, for a sweep drawn between position updates} */
    private long getLiveClockPositionMs() {
        return LyricsManager.getInstance().getPositionMs();
    }

    void setSecondary(boolean secondary) {
        isSecondary = secondary;
    }

    boolean isSecondary() {
        return isSecondary;
    }

    void setParentLineIndex(int index) {
        parentLineIndex = index;
    }

    int getParentLineIndex() {
        return parentLineIndex;
    }

    void setBgBeforeMain(boolean beforeMain) {
        bgBeforeMain = beforeMain;
    }

    void setIsRightAligned(boolean rightAligned) {
        isRightAligned = rightAligned;
    }

    void setIsCentered(boolean centered) {
        isCentered = centered;
    }

    boolean isRightAligned() {
        return isRightAligned;
    }

    boolean isCentered() {
        return isCentered;
    }


    boolean hasWordTimings() {
        if (wordTimings == null || wordTimings.isEmpty()) {
            return false;
        }
        for (int i = 0; i < wordTimings.size(); i++) {
            LyricsSpanBuilder.WordTiming wt = wordTimings.get(i);
            if (wt != null && wt.startMs() != LyricsLine.NO_TIME && wt.endMs() != LyricsLine.NO_TIME
                    && wt.endMs() >= wt.startMs()) {
                return true;
            }
        }
        return false;
    }

    long getLastWordEndMs() {
        if (wordTimings == null || wordTimings.isEmpty()) {
            return LyricsLine.NO_TIME;
        }
        long maxEnd = LyricsLine.NO_TIME;
        for (int i = 0; i < wordTimings.size(); i++) {
            long e = wordTimings.get(i).endMs();
            if (e != LyricsLine.NO_TIME) {
                maxEnd = (maxEnd == LyricsLine.NO_TIME) ? e : Math.max(maxEnd, e);
            }
        }
        return maxEnd;
    }

    private float computeLinePivotX(int w) {
        if (isCentered) {
            return w * 0.5f;
        }
        return isRightAligned ? w - getPaddingRight() : getPaddingLeft();
    }

    private float computeSecondaryPivotY() {
        float h = getHeight() > 0 ? (float) getHeight() : (float) secondaryFullHeight;
        return bgBeforeMain ? h : 0f;
    }

    /**
     * Secondary vocals slide down from behind the main vocal (or slide up from behind when bgBeforeMain).
     */
    private float computeSecondarySlideMaxY() {
        float em = getTextSize() > 0f ? getTextSize() : Dim.dp(20);
        return (bgBeforeMain ? 1f : -1f) * em * 1.35f;
    }

    private void updateSecondaryLayoutMargins(int fullH) {
        if (!isSecondary) return;
        ViewGroup.LayoutParams rawLp = getLayoutParams();
        if (!(rawLp instanceof ViewGroup.MarginLayoutParams mlp)) return;
        final int spacingPx = SECONDARY_SPACING_PX;
        int topM;
        int bottomM;
        if (secondaryLayoutVisible) {
            topM = bgBeforeMain ? 0 : spacingPx;
            bottomM = bgBeforeMain ? spacingPx : 0;
        } else if (secondaryAnimatingExit && fullH > 0) {
            if (bgBeforeMain) {
                topM = 0;
                bottomM = -fullH;
            } else {
                topM = spacingPx;
                bottomM = -(fullH + spacingPx);
            }
        } else {
            topM = 0;
            bottomM = 0;
        }
        if (mlp.topMargin != topM || mlp.bottomMargin != bottomM) {
            mlp.topMargin = topM;
            mlp.bottomMargin = bottomM;
        }
    }

    void setSecondaryExpandFraction(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        this.secondaryFraction = clamped;
        boolean shouldShowLayout = clamped > 0.001f;
        if (!shouldShowLayout && this.lastSecondaryReportedH < 0) {
            this.lastSecondaryReportedH = 0;
        }
        this.secondaryAnimatingExit = false;
        float revealScale = secondaryScale(clamped);
        float slideY = computeSecondarySlideMaxY() * (1f - clamped);
        setPivotX(computeLinePivotX(getWidth()));
        setPivotY(computeSecondaryPivotY());
        setScaleX(revealScale);
        setScaleY(revealScale);
        setTranslationY(slideY);
        setAlpha(shouldShowLayout ? LyricsColors.ACTIVE_BG_ALPHA : 0f);
        setVisibility(shouldShowLayout ? View.VISIBLE : View.GONE);
        if (this.secondaryLayoutVisible != shouldShowLayout) {
            this.secondaryLayoutVisible = shouldShowLayout;
            updateSecondaryLayoutMargins(secondaryFullHeight);
            requestLayout();
        }
        invalidate();
    }

    private static float secondaryScale(float fraction) {
        return SECONDARY_CLOSED_SCALE + (1f - SECONDARY_CLOSED_SCALE) * fraction;
    }

    /**
     * Opens or closes a background vocal using spring easing and negative-margin exit collapse
     * while reporting the layout shift once to LyricsScrollEngine so sibling/following lines
     * glide via FLIP without per-frame requestLayout() thrashing.
     */
    void animateSecondaryExpansion(boolean expand, boolean instant) {
        if (!Settings.LYRICS_SPLIT_BACKGROUND.get()) {
            return;
        }
        if (instant) {
            if (secondaryAnimator != null) {
                ValueAnimator running = secondaryAnimator;
                secondaryAnimator = null;
                running.cancel();
            }
            setSecondaryExpandFraction(expand ? 1f : 0f);
            return;
        }
        float target = expand ? 1f : 0f;
        if (Math.abs(secondaryFraction - target) < 0.001f
                && secondaryLayoutVisible == expand
                && secondaryAnimator == null) {
            if (!expand) {
                secondaryAnimatingExit = false;
                if (getVisibility() != View.GONE) {
                    setVisibility(View.GONE);
                }
                setAlpha(0f);
                setTranslationY(computeSecondarySlideMaxY());
            } else {
                if (getVisibility() != View.VISIBLE) {
                    setVisibility(View.VISIBLE);
                }
                setAlpha(LyricsColors.ACTIVE_BG_ALPHA);
                setScaleX(1f);
                setScaleY(1f);
                setTranslationY(0f);
            }
            return;
        }
        if (secondaryAnimator != null && secondaryLayoutVisible == expand) {
            return;
        }
        if (secondaryAnimator != null) {
            ValueAnimator running = secondaryAnimator;
            secondaryAnimator = null;
            running.cancel();
        }

        setPivotX(computeLinePivotX(getWidth()));
        setPivotY(computeSecondaryPivotY());
        float baseDuration = (scrollEngine != null)
                ? Math.max(1f, (float) scrollEngine.getScrollDurationMs())
                : (float) SECONDARY_EXPAND_MILLISECONDS;

        if (expand) {
            secondaryLayoutVisible = true;
            secondaryAnimatingExit = false;
            updateSecondaryLayoutMargins(secondaryFullHeight);
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
            }
            requestLayout();

            LyricsSpring spring = new LyricsSpring(baseDuration);
            long inScaleMs = Math.max(1L, Math.round(spring.totalMs));
            long inAlphaMs = Math.max(300L, Math.min(450L, Math.round(baseDuration * 0.95f)));
            final float startProgress = secondaryFraction;
            final float startAlpha = getAlpha();

            ValueAnimator animator = ValueAnimator.ofFloat(0f, (float) inScaleMs);
            animator.setDuration(inScaleMs);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                float tMs = (Float) animation.getAnimatedValue();
                float scaleProgress = startProgress + (1f - startProgress) * spring.value(tMs);
                secondaryFraction = Math.max(0f, Math.min(1f, scaleProgress));
                float scale = secondaryScale(scaleProgress);
                float slideY = computeSecondarySlideMaxY() * (1f - scaleProgress);
                setPivotX(computeLinePivotX(getWidth()));
                setPivotY(computeSecondaryPivotY());
                setScaleX(scale);
                setScaleY(scale);
                setTranslationY(slideY);

                float alphaFrac = Math.max(0f, Math.min(1f, tMs / (float) inAlphaMs));
                float easedAlpha = LyricsViewSupport.LINEAR_OUT_SLOW_IN.getInterpolation(alphaFrac);
                setAlpha(startAlpha + (LyricsColors.ACTIVE_BG_ALPHA - startAlpha) * easedAlpha);
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (secondaryAnimator == animation) {
                        secondaryAnimator = null;
                        secondaryFraction = 1f;
                        setAlpha(LyricsColors.ACTIVE_BG_ALPHA);
                        setScaleX(1f);
                        setScaleY(1f);
                        setTranslationY(0f);
                        invalidate();
                    }
                }
            });
            secondaryAnimator = animator;
            animator.start();
        } else {
            secondaryLayoutVisible = false;
            secondaryAnimatingExit = true;
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
            }
            updateSecondaryLayoutMargins(secondaryFullHeight);
            requestLayout();

            long outMs = Math.max(260L, Math.min(360L, Math.round(baseDuration * 0.8f)));
            final float startProgress = Math.max(0.05f, secondaryFraction);
            final float startAlpha = Math.max(0.05f, getAlpha());

            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(outMs);
            animator.setInterpolator(LyricsViewSupport.FAST_OUT_SLOW_IN);
            animator.addUpdateListener(animation -> {
                float f = (Float) animation.getAnimatedValue();
                float val = startProgress * (1f - f);
                secondaryFraction = val;
                float scale = secondaryScale(val);
                float slideY = computeSecondarySlideMaxY() * (1f - val);
                setPivotX(computeLinePivotX(getWidth()));
                setPivotY(computeSecondaryPivotY());
                setScaleX(scale);
                setScaleY(scale);
                setTranslationY(slideY);
                setAlpha(startAlpha * (1f - f));
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (secondaryAnimator == animation) {
                        secondaryAnimator = null;
                        secondaryAnimatingExit = false;
                        secondaryFraction = 0f;
                        setTranslationY(computeSecondarySlideMaxY());
                        setVisibility(View.GONE);
                        setAlpha(0f);
                        updateSecondaryLayoutMargins(0);
                        requestLayout();
                        invalidate();
                    }
                }
            });
            secondaryAnimator = animator;
            animator.start();
        }
    }

    /**
     * Moves the line's alpha and scale to those of a line this many lines from the sung one,
     * each line a little later than the one before it.
     *
     * @param isAutoScrollEnabled Off while the lyrics are scrolled by hand, which shows every
     *                            line alike.
     */
    void updateLineLook(
            boolean isActive,
            int distance,
            long staggerDelayMs,
            boolean isAutoScrollEnabled,
            boolean isLrcSynced,
            boolean instant) {
        final boolean focusSpotlightOn = Settings.LYRICS_FOCUS_SPOTLIGHT.get();
        final float targetInactiveAlpha;
        if (!isAutoScrollEnabled) {
            targetInactiveAlpha = LyricsViewSupport.BROWSING_LINE_ALPHA;
        } else if (!isLrcSynced) {
            targetInactiveAlpha = 1.0f;
        } else if (focusSpotlightOn) {
            targetInactiveAlpha = LyricsViewSupport.spotlightAlpha(distance);
        } else {
            targetInactiveAlpha = LyricsViewSupport.INACTIVE_LINE_ALPHA;
        }

        final float targetAlpha = isActive ? 1.0f : targetInactiveAlpha;
        final float targetUnsungAlpha;
        if (!isActive) {
            targetUnsungAlpha = targetInactiveAlpha;
        } else if (!Settings.LYRICS_WORD_SYNC.get() || !hasWordTimings()) {
            targetUnsungAlpha = 1.0f;
        } else if (!focusSpotlightOn) {
            targetUnsungAlpha = LyricsColors.UNSUNG_ALPHA;
        } else {
            targetUnsungAlpha = isAutoScrollEnabled
                    ? LyricsViewSupport.spotlightAlpha(0)
                    : LyricsViewSupport.BROWSING_LINE_ALPHA;
        }
        final boolean secondaryFull = !isLrcSynced || isActive;
        final float targetTransAlpha = secondaryFull
                ? LyricsColors.ACTIVE_TRANSLATION_ALPHA : targetInactiveAlpha;
        final float targetReadingAlpha = secondaryFull
                ? LyricsColors.ACTIVE_READING_ALPHA : targetInactiveAlpha;

        final float targetScale;
        if (isActive || !isAutoScrollEnabled || !isLrcSynced || !Settings.LYRICS_LINE_SCALE.get()) {
            targetScale = 1.0f;
        } else if (focusSpotlightOn) {
            targetScale = LyricsViewSupport.spotlightScale(distance);
        } else {
            targetScale = LyricsViewSupport.INACTIVE_LINE_SCALE;
        }

        this.activeLine = isActive;
        if (instant || !lineLookInitialized) {
            lineLookInitialized = true;
            cancelLineLookAnimator();
            if (fadeAnimator != null) {
                fadeAnimator.cancel();
                fadeAnimator = null;
            }
            lookFromAlpha = lookToAlpha = lookCurAlpha = targetAlpha;
            lookFromUnsungAlpha = lookToUnsungAlpha = lookCurUnsungAlpha = targetUnsungAlpha;
            lookFromTransAlpha = lookToTransAlpha = lookCurTransAlpha = targetTransAlpha;
            lookFromReadingAlpha = lookToReadingAlpha = lookCurReadingAlpha = targetReadingAlpha;
            lookFromScale = lookToScale = lookCurScale = targetScale;
            lineAlpha = lookCurAlpha;
            lineScale = lookCurScale;
            fadeTargetAlpha = targetAlpha;
            applyCurrentLineLookToView();
            return;
        }


        if (Math.abs(lookToAlpha - targetAlpha) < 0.001f
                && Math.abs(lookToUnsungAlpha - targetUnsungAlpha) < 0.001f
                && Math.abs(lookToTransAlpha - targetTransAlpha) < 0.001f
                && Math.abs(lookToReadingAlpha - targetReadingAlpha) < 0.001f
                && Math.abs(lookToScale - targetScale) < 0.001f) {
            return;
        }

        cancelLineLookAnimator();
        if (fadeAnimator != null) {
            fadeAnimator.cancel();
            fadeAnimator = null;
        }

        lookFromAlpha = lookCurAlpha;
        lookFromUnsungAlpha = lookCurUnsungAlpha;
        lookFromTransAlpha = lookCurTransAlpha;
        lookFromReadingAlpha = lookCurReadingAlpha;
        lookFromScale = lookCurScale;

        lookToAlpha = targetAlpha;
        lookToUnsungAlpha = targetUnsungAlpha;
        lookToTransAlpha = targetTransAlpha;
        lookToReadingAlpha = targetReadingAlpha;
        lookToScale = targetScale;
        fadeTargetAlpha = targetAlpha;

        final boolean springScrollOn = Settings.LYRICS_SPRING_SCROLL.get();
        final int baseAlphaMs = springScrollOn ? 550 : 350;
        final int baseScaleMs = springScrollOn ? 700 : 350;
        final int alphaMs = Math.max(1, (int) (baseAlphaMs + staggerDelayMs));
        final int scaleMs = Math.max(1, (int) (baseScaleMs + staggerDelayMs * 2L));
        final int totalMs = Math.max(Math.max(alphaMs, scaleMs), LINE_VISIBILITY_MS);

        lineLookAnimator = ValueAnimator.ofFloat(0f, (float) totalMs);
        lineLookAnimator.setDuration(totalMs);
        lineLookAnimator.setInterpolator(new LinearInterpolator());
        lineLookAnimator.addUpdateListener(anim -> {
            float clockMs = (Float) anim.getAnimatedValue();
            float fAlpha = TRANSITION_INTERPOLATOR.getInterpolation(
                    Math.max(0f, Math.min(1f, clockMs / alphaMs)));
            float fScale = TRANSITION_INTERPOLATOR.getInterpolation(
                    Math.max(0f, Math.min(1f, clockMs / scaleMs)));

            lookCurAlpha = lookFromAlpha + (lookToAlpha - lookFromAlpha) * fAlpha;
            lookCurUnsungAlpha = lookFromUnsungAlpha + (lookToUnsungAlpha - lookFromUnsungAlpha) * fAlpha;
            lookCurTransAlpha = lookFromTransAlpha + (lookToTransAlpha - lookFromTransAlpha) * fAlpha;
            lookCurReadingAlpha = lookFromReadingAlpha + (lookToReadingAlpha - lookFromReadingAlpha) * fAlpha;
            lookCurScale = lookFromScale + (lookToScale - lookFromScale) * fScale;
            lineAlpha = lookCurAlpha;
            lineScale = lookCurScale;

            applyCurrentLineLookToView();
        });
        lineLookAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (lineLookAnimator == animation) {
                    lineLookAnimator = null;
                    lookCurAlpha = lookToAlpha;
                    lookCurUnsungAlpha = lookToUnsungAlpha;
                    lookCurTransAlpha = lookToTransAlpha;
                    lookCurReadingAlpha = lookToReadingAlpha;
                    lookCurScale = lookToScale;
                    lineAlpha = lookCurAlpha;
                    lineScale = lookCurScale;
                    applyCurrentLineLookToView();
                }
            }
        });
        lineLookAnimator.start();
    }

    private void applyCurrentLineLookToView() {
        if (!isSecondary) {
            if (getWidth() > 0 && getHeight() > 0) {
                setPivotX(computeLinePivotX(getWidth()));
                setPivotY(getHeight() * 0.5f);
            }
            setScaleX(lookCurScale);
            setScaleY(lookCurScale);
            if (getAlpha() != 1.0f) {
                setAlpha(1.0f);
            }
        }
        invalidate();
    }

    /**
     * Stops a look change where it is. Cleared first, or the cancelled animator's end listener
     * settles the old target and the next change starts from there.
     */
    private void cancelLineLookAnimator() {
        final ValueAnimator running = lineLookAnimator;
        lineLookAnimator = null;
        if (running != null) {
            running.cancel();
        }
    }

    void cancelScaleAnimation() {
        cancelLineLookAnimator();
        if (fadeAnimator != null) {
            fadeAnimator.cancel();
            fadeAnimator = null;
        }
    }

    void resetInnerFlips() {
        mainVocalFlip.reset(Float.NaN);
        translationFlip.reset(Float.NaN);
        translationFlipOffsetY = 0f;
        if (!isSecondary) {
            setTranslationY(0f);
        }
    }

    private float resolveTranslationBlockTopY() {
        SyllableRomanCache syl = syllableCache;
        if (showSyllableRomanization && syl != null && syl.transLayout != null) {
            return (float) syl.transTopY;
        }
        Layout layout = getLayout();
        if (layout != null && transStart >= 0 && transEnd > transStart && transStart < layout.getText().length()) {
            int transLine = layout.getLineForOffset(transStart);
            return (float) layout.getLineTop(transLine);
        }
        return Float.NaN;
    }

    /** Places the line and its translation for the scroll engine, and glides them if they moved. */
    boolean syncInnerFlip(
            LyricsScrollEngine engine,
            int rowIndex,
            boolean enabled,
            float now) {
        boolean active = false;
        if (!isSecondary) {
            engine.onInnerPlaced(mainVocalFlip, rowIndex, (float) getTop(), enabled);
            float mainTransY = enabled ? mainVocalFlip.translation(now) : 0f;
            setTranslationY(mainTransY);
            if (mainVocalFlip.hasActiveLayers(now)) {
                active = true;
            }
            float transTop = resolveTranslationBlockTopY();
            if (!Float.isNaN(transTop)) {
                engine.onInnerPlaced(translationFlip, rowIndex, transTop, enabled);
                translationFlipOffsetY = enabled ? translationFlip.translation(now) : 0f;
                boolean transActive = translationFlip.hasActiveLayers(now);
                if (transActive || Math.abs(translationFlipOffsetY) > 0.1f) {
                    invalidate();
                }
                active = active || transActive;
            } else {
                translationFlip.reset(Float.NaN);
                translationFlipOffsetY = 0f;
            }
        }
        return active;
    }

    boolean updateInnerFlipTranslation(float now) {
        boolean active = false;
        if (!isSecondary) {
            float mainTransY = mainVocalFlip.translation(now);
            if (Math.abs(mainTransY - getTranslationY()) > 0.1f) {
                setTranslationY(mainTransY);
            }
            if (mainVocalFlip.hasActiveLayers(now)) {
                active = true;
            }
            float newTransOffset = translationFlip.translation(now);
            boolean transActive = translationFlip.hasActiveLayers(now);
            if (Math.abs(newTransOffset - translationFlipOffsetY) > 0.1f || transActive) {
                translationFlipOffsetY = newTransOffset;
                invalidate();
            }
            if (transActive) {
                active = true;
            }
        }
        return active;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelScaleAnimation();
        cancelRevealAnimators();
        if (secondaryAnimator != null) {
            secondaryAnimator.cancel();
            secondaryAnimator = null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (isSecondary) {
            final int pivotH = secondaryFullHeight > 0 ? secondaryFullHeight : h;
            setPivotX(computeLinePivotX(w));
            setPivotY(bgBeforeMain ? pivotH : 0f);
        } else {
            setPivotX(computeLinePivotX(w));
            setPivotY(h * 0.5f);
        }
    }

    @Override
    public void setTextColor(int color) {
        baseTextAlpha = Color.alpha(color);
        super.setTextColor(color);
    }

    void setTranslationBounds(int start, int end) {
        if (transStart != start || transEnd != end) {
            transStart = start;
            transEnd = end;
            syllableCache = null;
        }
    }

    void setRomanizationBounds(int start, int end, boolean perWord) {
        setRomanizationBounds(start, end, perWord, false);
    }

    void setRubyActive(boolean active) {
        if (rubyActive != active) {
            rubyActive = active;
            syllableCache = null;
            requestLayout();
            invalidate();
        }
    }

    void setRomanizationBounds(int start, int end, boolean perWord, boolean syllableInline) {
        if (romaStart != start || romaEnd != end
                || perWordReading != perWord
                || showSyllableRomanization != syllableInline) {
            romaStart = start;
            romaEnd = end;
            perWordReading = perWord;
            showSyllableRomanization = syllableInline;
            syllableCache = null;
            requestLayout();
            invalidate();
        }
    }

    boolean hasRomanizationRegion() {
        return romaStart >= 0 || showSyllableRomanization;
    }

    int syllableRomanizationExtraHeight() {
        if (!showSyllableRomanization || syllableCache == null) {
            return 0;
        }
        return syllableCache.extraRomanHeightPx;
    }

    /**
     * With the swap on and no reading to hand them to, the timings sit on the original, and it
     * has to dim the way the reading does.
     */
    void setSmallRunHoldsWords(boolean holdsWords) {
        smallRunHoldsWords = holdsWords;
    }

    void setActiveLine(boolean active) {
        if (activeLine == active) {
            return;
        }
        activeLine = active;
        invalidate();
    }

    void startContentIn() {
        contentReveal = 0f;
        animateReveal(REVEAL_CONTENT, true);
    }

    void startRegionIn(boolean trans) {
        if (trans) {
            transReveal = 0f;
        } else {
            romaReveal = 0f;
        }
        animateReveal(trans ? REVEAL_TRANS : REVEAL_ROMA, true);
    }

    void animateRegionOut(boolean trans) {
        animateReveal(trans ? REVEAL_TRANS : REVEAL_ROMA, false);
    }

    void animateContentOut() {
        animateReveal(REVEAL_CONTENT, false);
    }

    void cancelRevealAnimators() {
        if (contentRevealAnimator != null) {
            contentRevealAnimator.cancel();
            contentRevealAnimator = null;
        }
        if (transRevealAnimator != null) {
            transRevealAnimator.cancel();
            transRevealAnimator = null;
        }
        if (romaRevealAnimator != null) {
            romaRevealAnimator.cancel();
            romaRevealAnimator = null;
        }
        if (secondaryAnimator != null) {
            ValueAnimator running = secondaryAnimator;
            secondaryAnimator = null;
            running.cancel();
        }
    }

    private void animateReveal(int kind, boolean entering) {
        ValueAnimator running = revealAnimator(kind);
        if (running != null) {
            running.cancel();
        }
        final float target = entering ? 1f : 0f;
        if (Math.abs(reveal(kind) - target) < 0.01f) {
            setReveal(kind, target);
            invalidate();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(reveal(kind), target);
        animator.setDuration(LyricsPanelView.SECONDARY_REVEAL_MILLISECONDS);
        animator.addUpdateListener(animation -> {
            setReveal(kind, (float) animation.getAnimatedValue());
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (revealAnimator(kind) == animation) {
                    setRevealAnimator(kind, null);
                }
            }
        });
        setRevealAnimator(kind, animator);
        animator.start();
    }

    private float reveal(int kind) {
        return kind == REVEAL_CONTENT ? contentReveal
                : kind == REVEAL_TRANS ? transReveal : romaReveal;
    }

    private void setReveal(int kind, float value) {
        if (kind == REVEAL_CONTENT) {
            contentReveal = value;
        } else if (kind == REVEAL_TRANS) {
            transReveal = value;
        } else {
            romaReveal = value;
        }
    }

    private ValueAnimator revealAnimator(int kind) {
        return kind == REVEAL_CONTENT ? contentRevealAnimator
                : kind == REVEAL_TRANS ? transRevealAnimator : romaRevealAnimator;
    }

    private void setRevealAnimator(int kind, ValueAnimator animator) {
        if (kind == REVEAL_CONTENT) {
            contentRevealAnimator = animator;
        } else if (kind == REVEAL_TRANS) {
            transRevealAnimator = animator;
        } else {
            romaRevealAnimator = animator;
        }
    }

    @Nullable
    String getCopyTextForTouch(float touchX, float touchY) {
        SyllableRomanCache syllables = syllableCache;
        if (syllables != null && syllables.hasSyllableRomanization) {
            final float x = touchX - textOriginX();
            final float y = touchY - textOriginY();

            // Translation region: whole translation, unchanged.
            if (syllables.transLayout != null && (syllables.transTopY == 0
                    ? y < syllables.transLayout.getHeight() : y >= syllables.transTopY)) {
                return syllables.transText;
            }

            int sylIdx = findSyllableByX(x, syllables.sylShiftedLeadX, syllables.sylShiftedTrailX);
            if (sylIdx < 0) {
                return syllables.mainText != null ? syllables.mainText.trim() : null;
            }
            AlignedSyllable syl = syllables.alignedSyllables.get(sylIdx);

            // Ruby region: above the main text.
            if (y < syllables.mainTopY) {
                String ruby = syl.syllable().ruby();
                if (ruby != null && !ruby.isEmpty()) {
                    return ruby;
                }
            }

            // Romaji region: below the main text.
            if (y >= syllables.mainTopY + syllables.mainLayoutHeight) {
                String romaji = syl.syllable().romaji();
                if (romaji != null && !romaji.isEmpty()) {
                    return romaji;
                }
            }

            // Main text region: just this syllable's characters.
            int start = syl.startOffset();
            int end = syl.endOffset();
            String mainText = syllables.mainText;
            if (mainText != null && end > start && end <= mainText.length()) {
                return mainText.substring(start, end);
            }
            return mainText != null ? mainText.trim() : null;
        }
        Layout layout = getLayout();
        if (layout == null) {
            return null;
        }
        int lineCount = layout.getLineCount();
        if (lineCount <= 1) {
            return null;
        }
        int touchedLine = layout.getLineForVertical((int) touchY);
        CharSequence text = getText();
        if (text == null) {
            return null;
        }
        for (int i = 0; i < lineCount; i++) {
            if (i != touchedLine) continue;
            int lineStart = layout.getLineStart(i);
            int lineEnd = layout.getLineEnd(i);
            if (transStart >= 0 && lineStart < transEnd && lineEnd > transStart) {
                return text.subSequence(
                        Math.max(lineStart, transStart),
                        Math.min(lineEnd, transEnd)).toString().trim();
            }
            if (romaStart >= 0 && lineStart < romaEnd && lineEnd > romaStart) {
                return text.subSequence(
                        Math.max(lineStart, romaStart),
                        Math.min(lineEnd, romaEnd)).toString().trim();
            }
        }
        return null;
    }

    /** Finds which syllable's horizontal range contains x, or the nearest one. */
    private static int findSyllableByX(float x, float[] leadX, float[] trailX) {
        for (int i = 0; i < leadX.length; i++) {
            if (x >= leadX[i] && x <= trailX[i]) {
                return i;
            }
        }
        // Touch is between syllables; return the nearest by midpoint.
        int nearest = -1;
        float nearestDist = Float.MAX_VALUE;
        for (int i = 0; i < leadX.length; i++) {
            float mid = (leadX[i] + trailX[i]) / 2f;
            float dist = Math.abs(x - mid);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = i;
            }
        }
        return nearest;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            lastTouchY = event.getY();
            lastTouchX = event.getX();
        }
        return super.onTouchEvent(event);
    }

    void setHighlight(List<LyricsSpanBuilder.WordTiming> timings, long posMs, boolean sung,
                      int unsungCol, int origStart) {
        if (this.positionMs == posMs && this.allSung == sung
                && this.unsungColor == unsungCol
                && this.originalTextStart == origStart
                && this.wordTimings == timings) {
            return;
        }
        final boolean structuralChange = this.wordTimings != timings
                || this.originalTextStart != origStart
                || this.unsungColor != unsungCol;
        this.wordTimings = timings != null ? timings : Collections.emptyList();
        this.positionMs = posMs;
        this.allSung = sung;
        boolean hasRealTimings = false;
        for (LyricsSpanBuilder.WordTiming wt : this.wordTimings) {
            if (!wt.averaged()) {
                hasRealTimings = true;
                break;
            }
        }
        this.sweepEnabled = hasRealTimings;

        this.unsungColor = unsungCol;
        this.originalTextStart = origStart;
        if (structuralChange) {
            cachedLayout = null;
            syllableCache = null;
            if (showSyllableRomanization) {
                requestLayout();
            }
        }
        invalidate();
    }

    private void ensureWordCache(Layout layout, CharSequence text, Paint paint,
                                 List<LyricsSpanBuilder.WordTiming> timings, int origStart) {
        if (layout == cachedLayout && timings.size() == cachedWordCount
                && origStart == cachedOrigStart) {
            return;
        }
        cachedLayout = layout;
        cachedWordCount = timings.size();
        cachedOrigStart = origStart;
        cachedWordLeadX = new float[cachedWordCount];
        cachedWordTrailX = new float[cachedWordCount];
        cachedWordLine = new int[cachedWordCount];
        cachedWordWrappedLine = new int[cachedWordCount];
        int[] clusters = clusterBoundaries(text);
        for (int i = 0; i < cachedWordCount; i++) {
            LyricsSpanBuilder.WordTiming timing = timings.get(i);
            final int s = timing.start() + origStart;
            final int e = Math.min(timing.end() + origStart, text.length());
            if (s < 0 || s >= text.length() || s >= e) {
                cachedWordWrappedLine[i] = -1;
                continue;
            }
            final int snappedStart = snapToClusterStart(clusters, s);
            final int snappedEnd = snapToClusterEnd(clusters, e);
            final int line = lineDrawing(layout, s);
            // e lands on the break when the word ends its line; its last character never does.
            final int endLine = lineDrawing(layout, e - 1);
            // Both ends of a word sit on one of its lines, whatever an offset that lands on a
            // break reports for itself.
            final float lineLeft = Math.min(layout.getLineLeft(line),
                    layout.getLineRight(line));
            final float lineRight = Math.max(layout.getLineLeft(line),
                    layout.getLineRight(line));
            final float lead = Math.min(
                    Math.max(layout.getPrimaryHorizontal(snappedStart), lineLeft), lineRight);
            final boolean isRtl = isRightToLeftLine(layout, line) || layout.isRtlCharAt(s);
            final float trail;
            if (endLine != line) {
                // measureText reads the raw text, so a smaller size span is worth nothing to
                // it and the word measures wider than it is drawn.
                final float width = paint.measureText(text, s, e);
                final float measured = isRtl ? lead - width : lead + width;
                trail = Math.min(Math.max(measured, lineLeft), lineRight);
            } else {
                final float end = layout.getPrimaryHorizontal(snappedEnd);
                if (isRtl) {
                    // In RTL, text runs right-to-left, so end < lead.
                    // If end >= lead, the offset wrapped to the start of the next line,
                    // so the word ends at the line's left boundary.
                    trail = end >= lead ? lineLeft : Math.min(Math.max(end, lineLeft), lineRight);
                } else {
                    // That offset reports where the next line starts; a word ending its line ends at its right.
                    trail = end <= lead ? lineRight : Math.min(Math.max(end, lineLeft), lineRight);
                }
            }
            cachedWordLeadX[i] = lead;
            cachedWordTrailX[i] = trail;
            cachedWordLine[i] = line;
            cachedWordWrappedLine[i] = endLine != line ? endLine : -1;
        }
        final int lineCount = layout.getLineCount();
        if (cachedLineCount != lineCount) {
            cachedLineCount = lineCount;
            cachedLineTop = new int[lineCount];
            cachedLineBottom = new int[lineCount];
            cachedLineLeft = new float[lineCount];
            cachedLineRight = new float[lineCount];
        }
        for (int ln = 0; ln < lineCount; ln++) {
            cachedLineTop[ln] = layout.getLineTop(ln);
            cachedLineBottom[ln] = layout.getLineBottom(ln);
            cachedLineLeft[ln] = Math.min(layout.getLineLeft(ln), layout.getLineRight(ln));
            cachedLineRight[ln] = Math.max(layout.getLineLeft(ln), layout.getLineRight(ln));
        }
    }

    private static int[] clusterBoundaries(CharSequence text) {
        final int length = text.length();
        int[] boundaries = new int[length + 1];
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text.toString());
        int clusterStart = iterator.first();
        while (clusterStart != BreakIterator.DONE && clusterStart <= length) {
            final int next = iterator.next();
            final int clusterEnd = next == BreakIterator.DONE ? length : next;
            for (int offset = clusterStart; offset <= clusterEnd && offset <= length; offset++) {
                boundaries[offset] = clusterStart;
            }
            clusterStart = next;
        }
        return boundaries;
    }

    private static int snapToClusterStart(int[] clusters, int offset) {
        return clusters[Math.min(Math.max(offset, 0), clusters.length - 1)];
    }

    private static int snapToClusterEnd(int[] clusters, int offset) {
        final int index = Math.min(Math.max(offset, 0), clusters.length - 1);
        if (clusters[index] == index) {
            return index;
        }
        final int clusterStart = clusters[index];
        int probe = index;
        while (probe < clusters.length && clusters[probe] == clusterStart) {
            probe++;
        }
        return Math.min(probe, clusters.length - 1);
    }

    private int textOriginX() {
        return getCompoundPaddingLeft();
    }

    private int textOriginY() {
        return getExtendedPaddingTop();
    }

    /** End of the lyric line itself, before any translation or romanization below it. */
    private int originalEnd(CharSequence text, int origStart) {
        if (text != cachedText) {
            cachedText = text;
            cachedTextStr = text.toString();
        }
        final int newline = cachedTextStr.indexOf('\n', origStart);
        return newline >= 0 ? newline : cachedTextStr.length();
    }

    private void markSecondaryLines(Layout layout, int start, int end, boolean[] flags) {
        if (start < 0 || end <= start || flags.length == 0) {
            return;
        }
        CharSequence text = layout.getText();
        final int textLength = text.length();
        if (start >= textLength) {
            return;
        }
        final int lastOffset = Math.min(end, textLength) - 1;
        if (lastOffset < start) {
            return;
        }
        final int firstLine = layout.getLineForOffset(start);
        final int lastLine = layout.getLineForOffset(lastOffset);
        for (int i = firstLine; i <= lastLine && i < flags.length; i++) {
            flags[i] = true;
        }
    }

    private void drawTextRun(Canvas canvas, Layout layout, int firstLine, int lastLine,
                             int alpha, int tintColor) {
        if (firstLine > lastLine || alpha <= 0) {
            return;
        }
        final int contentWidth = layout.getWidth();
        if (contentWidth <= 0) {
            return;
        }
        final int top = layout.getLineTop(firstLine);
        final int bottom = layout.getLineBottom(lastLine);
        if (bottom <= top) {
            return;
        }
        final int clipTop = firstLine == 0 ? -textOriginY() : top;

        canvas.save();
        canvas.translate(textOriginX(), textOriginY());
        canvas.clipRect(0, clipTop, contentWidth, bottom);
        Paint textPaint = getPaint();
        textPaint.setColor(getCurrentTextColor() | 0xFF000000);
        ColorFilter previousFilter = textPaint.getColorFilter();
        if (tintColor != 0) {
            textPaint.setColorFilter(tintFilter(tintColor));
        }
        final boolean useLayer = alpha < 255;
        if (useLayer) {
            layerPaint.setColor(Color.WHITE);
            layerPaint.setAlpha(alpha);
            canvas.saveLayer(0, clipTop, contentWidth, bottom, layerPaint);
        }
        layout.draw(canvas);
        if (useLayer) {
            canvas.restore();
        }
        canvas.restore();
        if (tintColor != 0) {
            textPaint.setColorFilter(previousFilter);
        }
    }

    private PorterDuffColorFilter tintFilter(int color) {
        if (tintFilterColor != color) {
            tintFilterColor = color;
            tintFilter = new PorterDuffColorFilter(color | 0xFF000000, PorterDuff.Mode.SRC_IN);
        }
        return tintFilter;
    }

    /**
     * @return False if nothing was drawn and the default text drawing should be used.
     */
    private boolean drawBaseText(Canvas canvas) {
        Layout layout = getLayout();
        if (layout == null) {
            return false;
        }
        if (getWidth() <= 0 || (!isSecondary && getHeight() <= 0)) {
            return false;
        }

        final int lineCount = layout.getLineCount();
        if (lineCount <= 0) {
            return false;
        }

        final float contentNow = contentReveal;
        final float transShift = translationFlipOffsetY;
        final boolean hasTransShift = Math.abs(transShift) > 0.05f;

        final boolean hasWordSweep = hasWordSweep();
        final int mainAlpha = alpha255(unsungMainAlpha(hasWordSweep) * contentNow);
        // The reveals are applied per run below.
        final int transAlpha = alpha255(translationAlpha() * contentNow);
        final int romaAlpha = alpha255(unsungReadingAlpha(hasWordSweep) * contentNow);

        boolean[] secondaryLine = new boolean[lineCount];
        markSecondaryLines(layout, transStart, transEnd, secondaryLine);
        markSecondaryLines(layout, romaStart, romaEnd, secondaryLine);

        boolean hasSecondary = false;
        for (int i = 0; i < lineCount; i++) {
            if (secondaryLine[i]) {
                hasSecondary = true;
                break;
            }
        }

        final int textLength = layout.getText().length();
        final boolean onlySecondaryRegion = transStart == 0 && transEnd == textLength
                || romaStart == 0 && romaEnd == textLength;
        if (!hasSecondary || onlySecondaryRegion) {
            final boolean isTransRun = transStart == 0 && transEnd == textLength;
            if (hasTransShift && isTransRun) {
                canvas.save();
                canvas.translate(0f, transShift);
            }
            drawTextRun(canvas, layout, 0, lineCount - 1, mainAlpha, 0);
            if (hasTransShift && isTransRun) {
                canvas.restore();
            }
            return true;
        }

        final int romaFirst = rangeLine(layout, romaStart, romaEnd, true);
        final int romaLast = rangeLine(layout, romaStart, romaEnd, false);
        final int transFirst = rangeLine(layout, transStart, transEnd, true);
        final int transLast = rangeLine(layout, transStart, transEnd, false);

        int runStart = 0;
        boolean runSecondary = secondaryLine[0];
        for (int i = 1; i <= lineCount; i++) {
            final boolean end = i == lineCount;
            final boolean nextSecondary = !end && secondaryLine[i];
            if (end || nextSecondary != runSecondary) {
                final int runLast = i - 1;
                if (runSecondary) {
                    final boolean isRoma = romaFirst >= 0
                            && runStart >= romaFirst && runLast <= romaLast;
                    // Only one of the two ranges is set on a dual line, so this run is the reading's
                    // exactly when the reading's range is the one present.
                    final boolean runHoldsWords = smallRunHoldsWords ? isRoma == (romaFirst >= 0) : isRoma;
                    final float reveal = isRoma ? romaReveal : transReveal;
                    final boolean isTrans = transFirst >= 0
                            && runStart >= transFirst && runLast <= transLast;
                    if (hasTransShift && isTrans) {
                        canvas.save();
                        canvas.translate(0f, transShift);
                    }
                    drawTextRun(canvas, layout, runStart, runLast,
                            Math.round((runHoldsWords ? romaAlpha : transAlpha) * reveal), 0);
                    if (hasTransShift && isTrans) {
                        canvas.restore();
                    }
                } else {
                    drawTextRun(canvas, layout, runStart, runLast, mainAlpha, 0);
                }
                if (!end) {
                    runStart = i;
                    runSecondary = nextSecondary;
                }
            }
        }
        return true;
    }

    private static int rangeLine(Layout layout, int start, int end, boolean first) {
        if (start < 0 || end <= start || end > layout.getText().length()) {
            return -1;
        }
        return layout.getLineForOffset(first ? start : end - 1);
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int width = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        CharSequence text = getText();
        if (width > 0 && text != null && text.length() > 0) {
            SyllableRomanCache syllables = showSyllableRomanization && !wordTimings.isEmpty()
                    ? syllableCacheFor(width, text) : null;
            if (syllables != null) {
                // Drawn by hand, so the height is the hand-built layout's and not the text's.
                setMeasuredDimension(getMeasuredWidth(), resolveSize(getPaddingTop()
                        + syllables.totalHeightPx + getPaddingBottom(), heightMeasureSpec));
            } else if (secondaryAnimator == null
                    && LyricsFit.applyOrphanFit(this, width, text, fittedState)) {
                // Not refitted while a background vocal opens, or its text jumps mid-animation.
                cachedLayout = null;
                cachedText = null;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }
        secondaryFullHeight = getMeasuredHeight();
        if (isSecondary) {
            reportSecondaryLayoutHeight();
        }
    }

    /** Tells the scroll engine how much room a background vocal now takes, so lines below glide. */
    private void reportSecondaryLayoutHeight() {
        final int fullH = secondaryFullHeight > 0 ? secondaryFullHeight : getMeasuredHeight();
        final int netReportedH = secondaryLayoutVisible && getVisibility() != View.GONE
                ? fullH + SECONDARY_SPACING_PX : 0;
        if (lastSecondaryReportedH >= 0 && lastSecondaryReportedH != netReportedH && scrollEngine != null) {
            scrollEngine.recordLayoutShift(this, netReportedH - lastSecondaryReportedH);
        }
        lastSecondaryReportedH = netReportedH;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (isSecondary) {
            reportSecondaryLayoutHeight();
            final int margins = getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp
                    ? mlp.topMargin + mlp.bottomMargin : 0;
            committedSecondaryLayoutH = Math.max(0, bottom - top + margins);
        }
    }

    @SuppressLint("DrawAllocation")
    @SuppressWarnings("SizeReplaceableByIsEmpty")
    @Override
    protected void onDraw(Canvas canvas) {
        if (showSyllableRomanization && !wordTimings.isEmpty()) {
            final int width = getWidth() - getPaddingLeft() - getPaddingRight();
            CharSequence text = getText();
            if (width > 0 && text != null && text.length() > 0) {
                SyllableRomanCache syllables = syllableCacheFor(width, text);
                if (syllables != null) {
                    drawSyllableRomanizedLine(canvas, syllables);
                    return;
                }
            }
        }

        final boolean baseDrawn = drawBaseText(canvas);
        if (!baseDrawn) {
            super.onDraw(canvas);
        }
        try {
            if (contentReveal <= 0f
                    || unsungColor == 0
                    || !hasWordTimings()
                    || (allSung && baseDrawn)
                    || (!activeLine && lineLookAnimator == null)) {
                return;
            }

            Layout layout = getLayout();
            if (layout == null) {
                return;
            }
            CharSequence text = getText();
            if (text == null || text.length() == 0) {
                return;
            }
            Paint tp = getPaint();
            final int origStart = originalTextStart;

            ensureWordCache(layout, text, tp, wordTimings, origStart);

            final int lineCount = cachedLineCount;
            if (lineMaxSungX == null || lineMaxSungX.length != lineCount) {
                lineMaxSungX = new float[lineCount];
            }
            Arrays.fill(lineMaxSungX, 0, lineCount, Float.NaN);
            int firstSungLine = -1;
            // For the fill below: a line with no words at all is not its case, and a plain line
            // paints its own words on the main run, so only the dual arrangement reaches it.
            final boolean hasWords = cachedWordCount > 0;
            boolean allWordsDone = hasWords;
            boolean wordOnMainRun = false;

            boolean[] secondaryLine = new boolean[lineCount];
            markSecondaryLines(layout, transStart, transEnd, secondaryLine);
            markSecondaryLines(layout, romaStart, romaEnd, secondaryLine);
            boolean anyMainLine = false;
            for (int ln = 0; ln < lineCount; ln++) {
                if (!secondaryLine[ln]) {
                    anyMainLine = true;
                    break;
                }
            }

            // Read on every frame while sweeping, so the sweep moves between position updates.
            final long currentPos = (this.positionMs != Long.MIN_VALUE && sweepEnabled && !allSung && activeLine)
                    ? getLiveClockPositionMs()
                    : this.positionMs;
            for (int i = 0; i < cachedWordCount; i++) {
                LyricsSpanBuilder.WordTiming timing = wordTimings.get(i);
                final int s = timing.start() + origStart;
                final int e = Math.min(timing.end() + origStart, text.length());
                if (s < 0 || s >= text.length() || s >= e) {
                    continue;
                }
                // Before the lit ones: a word not yet reached still counts
                // as being on its run.
                final int lineNum = cachedWordLine[i];
                if (!secondaryLine[lineNum]) {
                    wordOnMainRun = true;
                }
                float progress;
                if (allSung) {
                    progress = 1f;
                } else if (currentPos >= timing.endMs()) {
                    progress = 1f;
                } else if (currentPos <= timing.startMs()) {
                    progress = 0f;
                } else {
                    progress = (float) (currentPos - timing.startMs())
                            / (float) (timing.endMs() - timing.startMs());
                }
                // A word still to come counts as unsung, so this has to sit ahead of the continue
                // below or the line looks finished from its first word.
                allWordsDone = allWordsDone && progress >= 1f;
                if (progress <= 0f) {
                    continue;
                }
                if (firstSungLine < 0) {
                    firstSungLine = lineNum;
                }

                final float lead = cachedWordLeadX[i];
                // A word that runs on below shares its progress with the lines it reaches, so
                // its own line takes only the share that falls here. For a word that fits, this
                // is the progress itself.
                final int charsOnFirstLine = Math.min(e, layout.getLineEnd(lineNum)) - s;
                final float firstLineProgress = Math.min(1f,
                        Math.max(0f, progress * (e - s) / (float) charsOnFirstLine));
                final float edge = lead + (cachedWordTrailX[i] - lead) * firstLineProgress;
                lineMaxSungX[lineNum] = extendSung(lineMaxSungX[lineNum], edge,
                        isRightToLeftLine(layout, lineNum));
                final int wrappedEnd = cachedWordWrappedLine[i];
                for (int ln = lineNum + 1; ln <= wrappedEnd && ln < lineCount; ln++) {
                    final int lineStart = layout.getLineStart(ln);
                    final int charsHere = Math.min(e, layout.getLineEnd(ln)) - lineStart;
                    if (charsHere <= 0) {
                        continue;
                    }
                    final float lineProgress = Math.min(1f, Math.max(0f,
                            (progress * (e - s) - (lineStart - s)) / (float) charsHere));
                    if (lineProgress <= 0f) {
                        continue;
                    }
                    // The word runs on below, so it does not fill this line. Its progress was
                    // scaled against the whole line, which lit the rest of the line with it.
                    final float lineLeft = layout.getLineLeft(ln);
                    final float lineRight = Math.max(lineLeft, layout.getLineRight(ln));
                    final boolean isLnRtl = isRightToLeftLine(layout, ln);
                    final float reach;
                    final float lineEdge;
                    if (isLnRtl) {
                        reach = ln == wrappedEnd
                                ? Math.min(Math.max(layout.getPrimaryHorizontal(e), lineLeft), lineRight)
                                : lineLeft;
                        lineEdge = lineRight + (reach - lineRight) * lineProgress;
                    } else {
                        reach = ln == wrappedEnd
                                ? Math.min(Math.max(layout.getPrimaryHorizontal(e), lineLeft), lineRight)
                                : lineRight;
                        lineEdge = lineLeft + (reach - lineLeft) * lineProgress;
                    }
                    lineMaxSungX[ln] = extendSung(lineMaxSungX[ln], lineEdge, isLnRtl);
                }
            }

            // The line is past its end but has no word timings left to measure it with, so its
            // reach comes from the bounds. The reading counts: it sits outside the lyric's own
            // end, or it stays dark against a lit lyric.
            if (allSung && firstSungLine < 0) {
                int firstOffset = -1;
                int lastOffset = -1;
                if (origStart >= 0 && origStart < text.length()) {
                    firstOffset = origStart;
                    lastOffset = originalEnd(text, origStart);
                }
                // A reading still fading in was never drawn; lighting it would show it.
                if (romaReveal > 0f && romaStart >= 0 && romaEnd > romaStart
                        && romaStart < text.length()) {
                    final int romaLast = Math.min(romaEnd, text.length());
                    if (firstOffset < 0 || romaStart < firstOffset) {
                        firstOffset = romaStart;
                    }
                    if (romaLast > lastOffset) {
                        lastOffset = romaLast;
                    }
                }
                if (firstOffset >= 0) {
                    final int firstLine = layout.getLineForOffset(firstOffset);
                    final int lastLine = layout.getLineForOffset(
                            Math.max(lastOffset - 1, firstOffset));
                    for (int ln = firstLine; ln <= lastLine && ln < lineCount; ln++) {
                        lineMaxSungX[ln] = isRightToLeftLine(layout, ln)
                                ? cachedLineLeft[ln] : cachedLineRight[ln];
                    }
                }
            }

            if (!sweepEnabled && activeLine) {
                for (int ln = 0; ln < lineCount; ln++) {
                    lineMaxSungX[ln] = isRightToLeftLine(layout, ln)
                            ? cachedLineLeft[ln] : cachedLineRight[ln];
                }
            }

            // Smallness follows the run beside it, never the words it holds: a dual line hands those to the
            // reading wherever the swap puts it, so a main run without them is still the main run.
            final float sungMainAlphaFactor = lineLookInitialized ? lookCurAlpha : lineAlpha;
            final float sungReadingAlphaFactor = lineLookInitialized
                    ? lookCurReadingAlpha
                    : (lineAlpha * (activeLine ? LyricsColors.ACTIVE_READING_ALPHA
                       : (Color.alpha(LyricsColors.secondaryTextColor()) / 255f)));
            final int baseRgb = LyricsColors.lineTextColor() & 0x00FFFFFF;
            final int lyricColor = baseRgb | (alpha255(sungMainAlphaFactor) << 24);
            final int readingColor = baseRgb | (alpha255(sungReadingAlphaFactor) << 24);
            // A main run holding none of the line's words has no word to light it, so it would sit in
            // the unsung colour for the whole line and then jump white at the last word. Drawn while the
            // line is active instead, and kept once the words are past so it does not fall back.
            // Whether a run holds a word is a fact about the layout, not about how far the line has
            // been sung: a seek lands before the first word opens, and reading that as an empty run
            // fills it white for a moment.
            final boolean fillMainRun = hasWords && !wordOnMainRun
                    && (activeLine || (allWordsDone && firstSungLine >= 0));
            if (fillMainRun) {
                for (int ln = 0; ln < lineCount; ln++) {
                    if (!Float.isNaN(lineMaxSungX[ln])
                            || secondaryLine[ln] && anyMainLine) {
                        continue;
                    }
                    lineMaxSungX[ln] = isRightToLeftLine(layout, ln)
                            ? cachedLineLeft[ln] : cachedLineRight[ln];
                }
            }
            ColorFilter prevFilter = tp.getColorFilter();
            int appliedColor = -1;

            canvas.save();
            if (contentReveal < 1f) {
                layerPaint.setColor(Color.WHITE);
                layerPaint.setAlpha(Math.round(255f * contentReveal));
                canvas.saveLayer(0f, 0f, getWidth(), getHeight(), layerPaint);
            }
            canvas.translate(textOriginX(), textOriginY());
            for (int ln = 0; ln < lineCount; ln++) {
                final float edge = lineMaxSungX[ln];
                if (Float.isNaN(edge)) {
                    continue;
                }
                // A small run takes the reading's colour so it reads as a line of its own, and a
                // wrapped one would otherwise come out in two colours mid-word. What makes a run small
                // is the main run beside it, not whether it holds the words: a dual line hands those to
                // the reading wherever the swap puts it, so a main run without them still reads as the
                // lyric, as does a line with nothing beside it.
                final int color = secondaryLine[ln] && anyMainLine ? readingColor : lyricColor;
                if (color != appliedColor) {
                    tp.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                    appliedColor = color;
                }
                canvas.save();
                final int clipTop = ln == 0 ? -textOriginY() : cachedLineTop[ln];
                if (isRightToLeftLine(layout, ln)) {
                    final float clipLeft = Math.min(edge, cachedLineRight[ln]);
                    canvas.clipRect(clipLeft, clipTop,
                            cachedLineRight[ln], cachedLineBottom[ln]);
                } else {
                    final float clipRight = Math.max(edge, cachedLineLeft[ln]);
                    canvas.clipRect(cachedLineLeft[ln], clipTop,
                            clipRight, cachedLineBottom[ln]);
                }
                layout.draw(canvas);
                canvas.restore();
            }
            canvas.restore();
            if (contentReveal < 1f) {
                canvas.restore();
            }
            tp.setColorFilter(prevFilter);
        } catch (Exception ex) {
            Logger.printException(() -> "LyricsLineView onDraw failure", ex);
        }
    }

    // Syllable romanization: each syllable's reading is drawn under it, and the karaoke sweep
    // runs over both. The text is laid out by hand, as each reading can be wider than the
    // syllable it reads and pushes the rest of the line along to make room.

    /** A word timing and where it sits in the laid out lyric. */
    private record AlignedSyllable(LyricsSpanBuilder.WordTiming syllable, int startOffset,
                                   int endOffset) {
    }

    /** One syllable's reading, laid out on its own. */
    private record RomanizedWordCluster(int wordIndex, int startIndex, int lineIdx,
                                        StaticLayout subLayout, float romanWidth) {
    }

    /** One character of the lyric as the main layout places it. */
    private record ClusterMeta(int start, int end, int lineIdx, float x, float width) {
    }

    /** A run of the lyric drawn in one piece: a whole syllable, or a character between syllables. */
    private record VisualCluster(int lineIdx, float x, float y, float width, StaticLayout subLayout,
                                 int syllableIndex, int shiftIndex) {
    }

    /** Where each visual cluster is drawn once the readings have pushed it along. */
    private record ClusterStatics(float[] baseX, float[] baseY) {
    }

    private record RomanLineStatics(List<RomanizedWordCluster> clusters, float[] drawX, float y) {
    }

    private static final class SyllableRomanCache {
        final int widthPx;
        final float fontSizePx;
        /** False when the line has no syllable reading, so it is drawn as plain text. */
        final boolean hasSyllableRomanization;
        @Nullable
        final String mainText;
        final int lineCount;
        final List<AlignedSyllable> alignedSyllables;
        final int extraRomanHeightPx;
        final List<VisualCluster> clusters;
        final ClusterStatics statics;
        final RomanLineStatics[] romanLines;
        final int[] sylLine;
        final float[] sylShiftedLeadX;
        final float[] sylShiftedTrailX;
        final float[] sylProgressScratch;
        @Nullable
        final String transText;
        @Nullable
        final StaticLayout transLayout;
        /** The furigana above each line of the lyric. */
        final RomanLineStatics[] rubyLines;
        /** Where the translation and the lyric start; with the swap on the translation goes first. */
        final int transTopY;
        final int mainTopY;
        final int totalHeightPx;
        final int mainLayoutHeight;

        SyllableRomanCache(int widthPx, float fontSizePx, boolean hasSyllableRomanization,
                           @Nullable String mainText, int lineCount, List<AlignedSyllable> alignedSyllables,
                           int extraRomanHeightPx, List<VisualCluster> clusters, ClusterStatics statics,
                           RomanLineStatics[] romanLines, RomanLineStatics[] rubyLines, int[] sylLine,
                           float[] sylShiftedLeadX, float[] sylShiftedTrailX,
                           @Nullable String transText, @Nullable StaticLayout transLayout,
                           int transTopY, int mainTopY, int totalHeightPx, int mainLayoutHeight) {
            this.widthPx = widthPx;
            this.fontSizePx = fontSizePx;
            this.hasSyllableRomanization = hasSyllableRomanization;
            this.mainText = mainText;
            this.lineCount = lineCount;
            this.alignedSyllables = alignedSyllables;
            this.extraRomanHeightPx = extraRomanHeightPx;
            this.clusters = clusters;
            this.statics = statics;
            this.romanLines = romanLines;
            this.rubyLines = rubyLines;
            this.sylLine = sylLine;
            this.sylShiftedLeadX = sylShiftedLeadX;
            this.sylShiftedTrailX = sylShiftedTrailX;
            this.sylProgressScratch = new float[alignedSyllables.size()];
            this.transText = transText;
            this.transLayout = transLayout;
            this.transTopY = transTopY;
            this.mainTopY = mainTopY;
            this.totalHeightPx = totalHeightPx;
            this.mainLayoutHeight = mainLayoutHeight;
        }

        /** Remembers that a line has nothing to draw this way, so it is not worked out every frame. */
        static SyllableRomanCache none(int widthPx, float fontSizePx) {
            return new SyllableRomanCache(widthPx, fontSizePx, false, null, 0,
                    Collections.emptyList(), 0, Collections.emptyList(),
                    new ClusterStatics(new float[0], new float[0]),
                    new RomanLineStatics[0], new RomanLineStatics[0], new int[0], new float[0], new float[0],
                    null, null, 0, 0, 0, 0);
        }
    }

    /** Laid out far wider than it needs, so a syllable or its reading never wraps a character. */
    private static StaticLayout buildSingleLineLayout(String text, TextPaint paint) {
        final float desiredW = Math.max(1f, Math.max(
                paint.measureText(text),
                Layout.getDesiredWidth(text, paint)));
        final int layoutW = Math.max(16384, (int) Math.ceil(desiredW) + 64);
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, layoutW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(1)
                .build();
    }

    /** Shrinks a reading wider than its line so it doesn't run off the edge. */
    private static StaticLayout buildFittedLayout(String text, TextPaint paint, float maxWidthPx) {
        StaticLayout layout = buildSingleLineLayout(text, paint);
        if (maxWidthPx <= 0f) {
            return layout;
        }
        final float width = singleLineWidth(text, paint, layout);
        if (width <= maxWidthPx) {
            return layout;
        }
        final float scale = maxWidthPx / Math.max(1f, width);
        paint.setTextSize(Math.max(1f, paint.getTextSize() * scale));
        return buildSingleLineLayout(text, paint);
    }

    private static float singleLineWidth(String text, TextPaint paint, StaticLayout layout) {
        final float desiredW = Math.max(paint.measureText(text), Layout.getDesiredWidth(text, paint));
        final float lineW = layout.getLineCount() > 0
                ? (layout.getLineRight(0) - layout.getLineLeft(0)) : 0f;
        final float horizW = text.length() > 0 ? layout.getPrimaryHorizontal(text.length()) : 0f;
        return Math.max(1f, Math.max(desiredW, Math.max(lineW, horizW)));
    }

    /**
     * The hand-built layout for a line with syllable readings, or null when it has none and is
     * drawn as plain text.
     */
    @Nullable
    private SyllableRomanCache syllableCacheFor(int widthPx, CharSequence fullText) {
        final float fontSizePx = getTextSize();
        if (syllableCache == null || syllableCache.widthPx != widthPx
                || Math.abs(syllableCache.fontSizePx - fontSizePx) >= 0.5f) {
            syllableCache = buildSyllableCache(widthPx, fontSizePx, fullText.toString());
        }
        return syllableCache.hasSyllableRomanization ? syllableCache : null;
    }

    private SyllableRomanCache buildSyllableCache(int widthPx, float fontSizePx, String fullStr) {
        final int origStart = Math.max(0, Math.min(originalTextStart, fullStr.length()));
        final int newline = fullStr.indexOf('\n', origStart);
        final int origEnd = newline >= 0 ? newline : fullStr.length();
        String rawMainText = fullStr.substring(origStart, origEnd);
        // The readings are placed left to right, which a right-to-left script would scramble.
        if (rawMainText.trim().isEmpty() || ScriptDetector.containsRtl(rawMainText)) {
            return SyllableRomanCache.none(widthPx, fontSizePx);
        }

        final boolean isRightAligned = (getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END;
        Layout.Alignment mainAlign =
                LyricsSpanBuilder.resolveAlignment(false, isRightAligned, isCentered);

        TextPaint mainPaint = new TextPaint(getPaint());
        mainPaint.setTextSize(fontSizePx);
        LyricsViewSupport.applyLyricsBoldPaint(mainPaint);
        mainPaint.setLetterSpacing(LyricsViewSupport.lineLetterSpacing(
                rawMainText, Settings.LYRICS_TEXT_SIZE.get()));

        TextPaint romPaint = new TextPaint(mainPaint);
        romPaint.setTextSize(fontSizePx * LyricsSpanBuilder.TRANSLATION_RELATIVE_SIZE);
        LyricsViewSupport.applyLyricsBoldPaint(romPaint);
        romPaint.setLetterSpacing(0f);
        TextPaint rubyPaint = new TextPaint(mainPaint);
        rubyPaint.setTextSize(fontSizePx * LyricsSpanBuilder.RUBY_RELATIVE_SIZE);
        LyricsViewSupport.applyLyricsBoldPaint(rubyPaint);
        rubyPaint.setLetterSpacing(0f);

        String formattedMainText = buildFormattedMainText(
                rawMainText, wordTimings, mainPaint, romPaint, rubyPaint, widthPx, fontSizePx,
                rubyActive);

        StaticLayout mainLayout = StaticLayout.Builder.obtain(
                        formattedMainText, 0, formattedMainText.length(), mainPaint, widthPx)
                .setAlignment(mainAlign)
                .setIncludePad(false)
                .setLineSpacing(0f, LyricsSpanBuilder.MAIN_LINE_SPACING)
                .build();

        final int lineCount = mainLayout.getLineCount();
        float[] lineLefts = new float[lineCount];
        float[] lineStarts = new float[lineCount];
        float[] lineBaselines = new float[lineCount];
        float[] lineBottoms = new float[lineCount];
        boolean[] lineIsRtl = new boolean[lineCount];

        float totalW = 0f;
        for (int i = 0; i < lineCount; i++) {
            lineLefts[i] = mainLayout.getLineLeft(i);
            lineStarts[i] = totalW;
            totalW += Math.max(1f, mainLayout.getLineRight(i) - lineLefts[i]);
            lineBaselines[i] = mainLayout.getLineBaseline(i);
            lineBottoms[i] = mainLayout.getLineBottom(i);
            lineIsRtl[i] = mainLayout.getParagraphDirection(i) == Layout.DIR_RIGHT_TO_LEFT;
        }

        List<AlignedSyllable> alignedSyllables =
                alignSyllablesForFormatting(wordTimings, rawMainText, formattedMainText);
        List<RomanizedWordCluster> romanClusters =
                buildRomanizedWordClusters(alignedSyllables, formattedMainText, mainLayout, romPaint,
                        widthPx);
        List<RomanizedWordCluster> rubyClusters = rubyActive
                ? buildRubyClusters(alignedSyllables, formattedMainText, mainLayout, rubyPaint,
                widthPx)
                : Collections.emptyList();
        if (romanClusters.isEmpty() && rubyClusters.isEmpty()) {
            return SyllableRomanCache.none(widthPx, fontSizePx);
        }

        final boolean hasRuby = !rubyClusters.isEmpty();
        final float lineRubyExtraHeight = hasRuby ? (fontSizePx * 0.90f + Dim.dp(6)) : 0f;
        final int extraRubyHeightPx = Math.round(lineRubyExtraHeight);

        // Each lyric line makes room under it for its readings and above wrapped lines for furigana.
        final boolean hasRoman = !romanClusters.isEmpty();
        final float lineRomanExtraHeight = hasRoman ? (fontSizePx * 0.70f) : 0f;
        final float lineExtraHeight = lineRomanExtraHeight + (lineCount > 1 ? lineRubyExtraHeight : 0f);
        final int bottomMargin = hasRoman ? Math.round(fontSizePx * 0.08f) : 0;
        final int extraRomanHeightPx = Math.round(
                (hasRoman ? lineCount * lineRomanExtraHeight : 0f)
                        + Math.max(0, lineCount - 1) * lineRubyExtraHeight) + bottomMargin;

        float[] charXMap = buildCharXMap(
                formattedMainText, mainLayout, lineCount, lineIsRtl, lineStarts);
        float[] romanStartShift = buildRomanStartShift(
                formattedMainText, charXMap, mainLayout, lineCount,
                alignedSyllables, romanClusters, rubyClusters, mainAlign);

        List<VisualCluster> clusters = buildVisualClusters(
                formattedMainText, mainLayout, alignedSyllables, lineBaselines, mainPaint);
        ClusterStatics statics = buildClusterStatics(
                clusters, romanStartShift, lineExtraHeight);
        List<RomanizedWordCluster>[] romanLines =
                groupRomanClustersByLine(romanClusters, lineCount);
        RomanLineStatics[] romanStatics = hasRoman
                ? buildRomanStatics(romanLines, charXMap, romanStartShift, lineBaselines, lineBottoms,
                lineLefts, lineStarts, lineCount, fontSizePx, lineExtraHeight, totalW) : new RomanLineStatics[0];
        RomanLineStatics[] rubyStatics = hasRuby
                ? buildRubyStatics(groupRomanClustersByLine(rubyClusters, lineCount), charXMap,
                romanStartShift, lineBaselines, lineLefts, lineStarts, lineCount, fontSizePx,
                lineExtraHeight, totalW)
                : new RomanLineStatics[0];

        // Where each syllable's sweep starts and ends, from the same positions it is drawn at.
        final int sylCount = alignedSyllables.size();
        int[] sylLine = new int[sylCount];
        float[] sylShiftedLeadX = new float[sylCount];
        float[] sylShiftedTrailX = new float[sylCount];
        Arrays.fill(sylShiftedLeadX, Float.NaN);
        Arrays.fill(sylShiftedTrailX, Float.NaN);

        for (int idx = 0; idx < clusters.size(); idx++) {
            VisualCluster vc = clusters.get(idx);
            final int sIdx = vc.syllableIndex();
            if (sIdx >= 0 && sIdx < sylCount) {
                sylLine[sIdx] = vc.lineIdx();
                final float leftX = statics.baseX()[idx];
                final float rightX = leftX + vc.width();
                if (Float.isNaN(sylShiftedLeadX[sIdx]) || leftX < sylShiftedLeadX[sIdx]) {
                    sylShiftedLeadX[sIdx] = leftX;
                }
                if (Float.isNaN(sylShiftedTrailX[sIdx]) || rightX > sylShiftedTrailX[sIdx]) {
                    sylShiftedTrailX[sIdx] = rightX;
                }
            }
        }
        final int maxCharIdx = Math.max(0, formattedMainText.length() - 1);
        for (int sIdx = 0; sIdx < sylCount; sIdx++) {
            if (Float.isNaN(sylShiftedLeadX[sIdx])) {
                AlignedSyllable syl = alignedSyllables.get(sIdx);
                final int li = mainLayout.getLineForOffset(Math.max(0, Math.min(syl.startOffset(), maxCharIdx)));
                sylLine[sIdx] = li;
                final float shift = calculateGapShift(sIdx, romanStartShift);
                final float sx = mainLayout.getPrimaryHorizontal(Math.max(0, Math.min(syl.startOffset(), formattedMainText.length()))) + shift;
                final float ex = mainLayout.getPrimaryHorizontal(Math.max(0, Math.min(syl.endOffset(), formattedMainText.length()))) + shift;
                sylShiftedLeadX[sIdx] = Math.min(sx, ex);
                sylShiftedTrailX[sIdx] = Math.max(sx + 1f, ex);
            }
        }

        String transText = null;
        StaticLayout transLayout = null;
        final int mainSectionH = mainLayout.getHeight() + extraRomanHeightPx + extraRubyHeightPx;
        int transTopY = 0;
        int mainTopY = extraRubyHeightPx;
        int totalH = mainSectionH;

        if (transStart >= 0 && transEnd > transStart && transStart < fullStr.length()) {
            transText = fullStr.substring(transStart, Math.min(transEnd, fullStr.length())).trim();
            if (!transText.isEmpty()) {
                TextPaint transPaint = new TextPaint(getPaint());
                transPaint.setTextSize(fontSizePx * LyricsSpanBuilder.TRANSLATION_RELATIVE_SIZE);
                LyricsViewSupport.applyLyricsBoldPaint(transPaint);
                transPaint.setLetterSpacing(0f);
                final boolean isTransRtl = LyricsSpanBuilder.isRtlText(transText);
                Layout.Alignment transAlign =
                        LyricsSpanBuilder.resolveAlignment(isTransRtl, isRightAligned, isCentered);
                transLayout = StaticLayout.Builder.obtain(
                                transText, 0, transText.length(), transPaint, widthPx)
                        .setAlignment(transAlign)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1.18f)
                        .build();
                final int transH = transLayout.getHeight();
                if (transStart < origStart) {
                    mainTopY = transH + Dim.dp4 + extraRubyHeightPx;
                } else {
                    transTopY = mainSectionH + Dim.dp4;
                }
                totalH = mainSectionH + Dim.dp4 + transH;
            }
        }

        return new SyllableRomanCache(widthPx, fontSizePx, true, rawMainText, lineCount,
                alignedSyllables, extraRomanHeightPx, clusters, statics,
                romanStatics, rubyStatics, sylLine,
                sylShiftedLeadX, sylShiftedTrailX, transText, transLayout, transTopY, mainTopY, totalH,
                mainLayout.getHeight());
    }

    /** Finds each word timing in the laid out text, which may carry bidi marks and added breaks. */
    private static List<AlignedSyllable> alignSyllablesForFormatting(
            List<LyricsSpanBuilder.WordTiming> words,
            String rawMainText,
            String targetMainText) {
        if (words.isEmpty() || targetMainText.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> strippedToOriginal = new ArrayList<>(targetMainText.length() + 1);
        StringBuilder strippedBuilder = new StringBuilder(targetMainText.length());
        for (int i = 0; i < targetMainText.length(); i++) {
            final char c = targetMainText.charAt(i);
            if (!LyricsSpanBuilder.isBidiOrInvisible(c)) {
                strippedBuilder.append(c);
                strippedToOriginal.add(i);
            }
        }
        strippedToOriginal.add(targetMainText.length());
        String strippedMain = strippedBuilder.toString();
        String strippedLower = strippedMain.toLowerCase(Locale.ROOT);

        int totalSylLen = 0;
        String[] sylTexts = new String[words.size()];
        for (int i = 0; i < words.size(); i++) {
            LyricsSpanBuilder.WordTiming wt = words.get(i);
            final int s = Math.max(0, Math.min(wt.start(), rawMainText.length()));
            final int e = Math.max(s, Math.min(wt.end(), rawMainText.length()));
            String st = LyricsSpanBuilder.stripBidiControls(rawMainText.substring(s, e)).trim();
            sylTexts[i] = st;
            totalSylLen += st.length();
        }
        totalSylLen = Math.max(1, totalSylLen);

        int searchIdx = 0;
        List<AlignedSyllable> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            LyricsSpanBuilder.WordTiming syl = words.get(i);
            String sylText = sylTexts[i];
            String sylLower = sylText.toLowerCase(Locale.ROOT);
            while (searchIdx < strippedMain.length()
                    && Character.isWhitespace(strippedMain.charAt(searchIdx))) {
                searchIdx++;
            }
            final int found = sylLower.isEmpty() ? -1 : strippedLower.indexOf(sylLower, searchIdx);
            final int si;
            final int ei;
            if (found != -1) {
                si = strippedToOriginal.get(found);
                final int endIdx = Math.min(found + sylText.length(), strippedToOriginal.size() - 1);
                ei = strippedToOriginal.get(endIdx);
                searchIdx = found + sylText.length();
            } else {
                final int fallbackStripped = Math.min(searchIdx, strippedMain.length());
                final int propLen = Math.max(1,
                        (int) (((double) sylText.length() / totalSylLen) * strippedMain.length()));
                final int endStripped = Math.min(fallbackStripped + propLen, strippedMain.length());
                si = strippedToOriginal.get(Math.min(fallbackStripped, strippedToOriginal.size() - 1));
                ei = strippedToOriginal.get(Math.min(endStripped, strippedToOriginal.size() - 1));
                searchIdx = endStripped;
            }
            out.add(new AlignedSyllable(syl, si, ei));
        }
        return out;
    }

    /**
     * Breaks the lyric where its syllables, each as wide as the wider of itself and its reading,
     * run out of room, so that no syllable is split from its reading by a wrap.
     */
    private static String buildFormattedMainText(
            String mainText,
            List<LyricsSpanBuilder.WordTiming> words,
            TextPaint mainPaint,
            TextPaint romPaint,
            @Nullable TextPaint rubyPaint,
            int widthPx,
            float fontSizePx,
            boolean rubyActive) {
        if (mainText.isEmpty() || widthPx <= 0 || words.isEmpty()) {
            return mainText;
        }
        List<AlignedSyllable> alignedSyllables =
                alignSyllablesForFormatting(words, mainText, mainText);
        if (alignedSyllables.isEmpty()) {
            return mainText;
        }

        final int unconstrainedWidth = Math.max(widthPx * 16, 16384);
        StaticLayout unconstrained = StaticLayout.Builder.obtain(
                        mainText, 0, mainText.length(), mainPaint, unconstrainedWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();

        List<RomanizedWordCluster> romanClusters =
                buildRomanizedWordClusters(alignedSyllables, mainText, unconstrained, romPaint, widthPx);
        List<RomanizedWordCluster> rubyClusters = (rubyActive && rubyPaint != null)
                ? buildRubyClusters(alignedSyllables, mainText, unconstrained, rubyPaint, widthPx)
                : Collections.emptyList();
        if (romanClusters.isEmpty() && rubyClusters.isEmpty()) {
            return mainText;
        }

        Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2);
        for (RomanizedWordCluster rc : romanClusters) {
            clusterMap.put(rc.wordIndex(), rc);
        }
        Map<Integer, RomanizedWordCluster> rubyMap = new HashMap<>(rubyClusters.size() * 2);
        for (RomanizedWordCluster rc : rubyClusters) {
            rubyMap.put(rc.wordIndex(), rc);
        }

        float[] sylSlotWidths = new float[alignedSyllables.size()];
        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            RomanizedWordCluster cluster = clusterMap.get(sylIdx);
            RomanizedWordCluster rubyCluster = rubyMap.get(sylIdx);
            final int startOff = Math.max(0, Math.min(syl.startOffset(), mainText.length()));
            final int nextStartOff = (sylIdx + 1 < alignedSyllables.size())
                    ? Math.max(syl.endOffset(), Math.min(alignedSyllables.get(sylIdx + 1).startOffset(), mainText.length()))
                    : Math.max(startOff, Math.min(syl.endOffset(), mainText.length()));
            final boolean hasNewlineInGap = mainText.substring(
                    Math.max(0, Math.min(syl.endOffset(), mainText.length())), nextStartOff).indexOf('\n') >= 0;
            final int slotEndOff = hasNewlineInGap
                    ? Math.max(startOff, Math.min(syl.endOffset(), mainText.length()))
                    : nextStartOff;
            final float startX = unconstrained.getPrimaryHorizontal(startOff);
            final float endX = unconstrained.getPrimaryHorizontal(slotEndOff);
            final float slotMainWidth = Math.max(1f, endX - startX);
            final float romWidth = cluster != null ? cluster.romanWidth() : 0f;
            final float rubyWidth = rubyCluster != null ? rubyCluster.romanWidth() : 0f;
            sylSlotWidths[sylIdx] = Math.max(Math.max(slotMainWidth, romWidth), rubyWidth);
        }

        List<Integer> breakIndices = new ArrayList<>();
        Set<Integer> spaceReplaced = new HashSet<>();

        float currentLineSlotSum = 0f;
        int syllablesOnCurrentLine = 0;
        int currentLineStartOffset = 0;

        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            final float slotW = sylSlotWidths[sylIdx];
            AlignedSyllable prevSyl = sylIdx > 0 ? alignedSyllables.get(sylIdx - 1) : null;

            if (prevSyl != null) {
                final int gs = Math.max(0, Math.min(prevSyl.endOffset(), mainText.length()));
                final int ge = Math.max(gs, Math.min(syl.startOffset(), mainText.length()));
                if (mainText.substring(gs, ge).indexOf('\n') >= 0) {
                    currentLineSlotSum = 0f;
                    syllablesOnCurrentLine = 0;
                    currentLineStartOffset = Math.max(0, Math.min(syl.startOffset(), mainText.length()));
                }
            }

            final float breakThreshold = widthPx - fontSizePx * 0.1f;
            if (syllablesOnCurrentLine > 0 && currentLineSlotSum + slotW > breakThreshold) {
                final int rawBreakAt = Math.max(0, Math.min(syl.startOffset(), mainText.length()));
                Integer breakAt = findSafeBreakOffset(mainText, rawBreakAt, currentLineStartOffset);
                if (breakAt != null && breakAt > 0) {
                    breakIndices.add(breakAt);
                    if (breakAt - 1 < mainText.length() && mainText.charAt(breakAt - 1) == ' ') {
                        spaceReplaced.add(breakAt - 1);
                    }
                    currentLineStartOffset = breakAt;
                    currentLineSlotSum = slotW;
                    syllablesOnCurrentLine = 1;
                } else {
                    currentLineSlotSum += slotW;
                    syllablesOnCurrentLine++;
                }
            } else {
                currentLineSlotSum += slotW;
                syllablesOnCurrentLine++;
            }
        }

        if (breakIndices.isEmpty()) {
            return mainText;
        }

        StringBuilder sb = new StringBuilder(mainText.length() + breakIndices.size());
        int prev = 0;
        for (int bIdx : breakIndices) {
            final int safeBIdx = Math.max(0, Math.min(bIdx, mainText.length()));
            final int safePrev = Math.max(0, Math.min(prev, mainText.length()));
            if (safeBIdx > safePrev) {
                final int spaceIdx = safeBIdx - 1;
                if (spaceReplaced.contains(spaceIdx)
                        && spaceIdx >= 0 && spaceIdx < mainText.length()
                        && mainText.charAt(spaceIdx) == ' ') {
                    sb.append(mainText, safePrev, spaceIdx);
                    sb.append('\n');
                    prev = safeBIdx;
                } else {
                    sb.append(mainText, safePrev, safeBIdx);
                    sb.append('\n');
                    prev = safeBIdx;
                }
            }
        }
        final int finalPrev = Math.max(0, Math.min(prev, mainText.length()));
        if (finalPrev < mainText.length()) {
            sb.append(mainText.substring(finalPrev));
        }
        return sb.toString();
    }

    /** Punctuation a line must not start with. */
    private static boolean isProhibitedLineStart(char c) {
        return switch (c) {
            case '、', '。', '！', '？', ')', ']', '}', '”', '’', '…', '—',
                 '）', '】', '』', '」', '≫', '》', '〉', '＞', '›', '〕', '〛',
                 ',', '.', '!', '?', ':', ';', ' ' -> true;
            default -> false;
        };
    }

    @Nullable
    private static Integer findSafeBreakOffset(String mainText, int rawBreakAt, int lineStartOffset) {
        if (rawBreakAt <= lineStartOffset || rawBreakAt > mainText.length()) {
            return null;
        }
        Character before = rawBreakAt - 1 >= 0 && rawBreakAt - 1 < mainText.length()
                ? mainText.charAt(rawBreakAt - 1) : null;
        Character at = rawBreakAt >= 0 && rawBreakAt < mainText.length()
                ? mainText.charAt(rawBreakAt) : null;

        // Korean words are spaced, so a break inside one moves back to the last space.
        final int lastSpaceOnLine = mainText.substring(lineStartOffset, rawBreakAt).lastIndexOf(' ');
        if (lastSpaceOnLine >= 0 && (before == null || before != ' ')) {
            final boolean isInsideHangulWord = before != null && at != null
                    && before >= 0xAC00 && before <= 0xD7AF
                    && at >= 0xAC00 && at <= 0xD7AF;
            if (isInsideHangulWord) {
                final int spaceBreak = lineStartOffset + lastSpaceOnLine + 1;
                if (spaceBreak > lineStartOffset && spaceBreak < mainText.length()
                        && !isProhibitedLineStart(mainText.charAt(spaceBreak))) {
                    return spaceBreak;
                }
            }
        }

        final boolean atCjkBoundary =
                (before == null || before == ' ' || LyricsSpanBuilder.isCjkCodePoint(before))
                        && (at == null || at == ' ' || LyricsSpanBuilder.isCjkCodePoint(at) || LyricsSpanBuilder.isCjkCodePoint(before));

        int candidate = rawBreakAt;
        if (!atCjkBoundary) {
            int i = rawBreakAt;
            while (i > lineStartOffset) {
                final char c = mainText.charAt(i - 1);
                Character next = i < mainText.length() ? mainText.charAt(i) : null;
                if (c == ' ' || LyricsSpanBuilder.isCjkCodePoint(c) || (next != null && LyricsSpanBuilder.isCjkCodePoint(next))) {
                    candidate = i;
                    break;
                }
                i--;
            }
            if (i <= lineStartOffset) {
                return null;
            }
        }

        while (candidate > lineStartOffset
                && candidate < mainText.length()
                && isProhibitedLineStart(mainText.charAt(candidate))) {
            candidate--;
            while (candidate > lineStartOffset
                    && !(mainText.charAt(candidate - 1) == ' ' || LyricsSpanBuilder.isCjkCodePoint(mainText.charAt(candidate - 1)))) {
                candidate--;
            }
        }

        return candidate > lineStartOffset ? candidate : null;
    }

    /** Lays out each syllable's reading on its own, empty when the line has no reading of its own. */
    private static List<RomanizedWordCluster> buildRomanizedWordClusters(
            List<AlignedSyllable> alignedSyllables,
            String mainText,
            Layout textLayoutResult,
            TextPaint romPaint,
            float maxWidthPx) {
        if (alignedSyllables.isEmpty() || mainText.isEmpty()) {
            return Collections.emptyList();
        }
        String cleanMainText = LyricsSpanBuilder.stripBidiControls(mainText).trim();
        StringBuilder assembled = new StringBuilder();
        for (AlignedSyllable syl : alignedSyllables) {
            String r = syl.syllable().romaji();
            if (r != null) {
                assembled.append(r);
            }
        }
        String assembledRoman = LyricsSpanBuilder.stripBidiControls(assembled.toString()).trim();
        if (assembledRoman.isEmpty() || assembledRoman.equalsIgnoreCase(cleanMainText)) {
            return Collections.emptyList();
        }

        List<RomanizedWordCluster> result = new ArrayList<>(alignedSyllables.size());
        final int maxCharIdx = Math.max(0, mainText.length() - 1);
        for (int idx = 0; idx < alignedSyllables.size(); idx++) {
            AlignedSyllable syl = alignedSyllables.get(idx);
            String text = syl.syllable().romaji();
            if (text != null && !text.trim().isEmpty()) {
                final int clampedStart = Math.max(0, Math.min(syl.startOffset(), maxCharIdx));
                final int li = textLayoutResult.getLineForOffset(clampedStart);
                TextPaint subPaint = new TextPaint(romPaint);
                final float pad = (idx + 1 < alignedSyllables.size()) ? Dim.dp(2) : 0f;
                StaticLayout subLayout = buildFittedLayout(text, subPaint,
                        maxWidthPx > 0f ? Math.max(1f, maxWidthPx - pad) : 0f);
                final float romW = singleLineWidth(text, subPaint, subLayout) + pad;
                result.add(new RomanizedWordCluster(idx, syl.startOffset(), li, subLayout, romW));
            }
        }
        return result;
    }

    /** How far along the lyric each character starts, counting its lines as one long line. */
    private static float[] buildCharXMap(
            String mainText,
            Layout textLayoutResult,
            int lineCount,
            boolean[] lineIsRtl,
            float[] lineStarts) {
        final int len = mainText.length();
        float[] map = new float[len + 1];
        final int maxIdx = Math.max(0, len - 1);
        for (int i = 0; i <= len; i++) {
            final int idx = Math.min(i, maxIdx);
            final int li = Math.max(0, Math.min(textLayoutResult.getLineForOffset(idx), lineCount - 1));
            final float h = textLayoutResult.getPrimaryHorizontal(Math.min(i, len));
            final float local = lineIsRtl[li]
                    ? textLayoutResult.getLineRight(li) - h
                    : h - textLayoutResult.getLineLeft(li);
            map[i] = lineStarts[li] + local;
        }
        return map;
    }

    /**
     * How far each syllable moves right to make room for the readings before it that are wider
     * than their syllables, centred or right aligned again with the line.
     */
    private static float[] buildRomanStartShift(
            String mainText,
            float[] charXMap,
            Layout textLayoutResult,
            int lineCount,
            List<AlignedSyllable> alignedSyllables,
            List<RomanizedWordCluster> romanClusters,
            List<RomanizedWordCluster> rubyClusters,
            Layout.Alignment alignment) {
        final int totalClusters = romanClusters.size() + rubyClusters.size();
        float[] shiftByWordIndex = new float[totalClusters + alignedSyllables.size() + 1];
        float[] lineTotalExtra = new float[lineCount];
        if (romanClusters.isEmpty() && rubyClusters.isEmpty()) {
            return shiftByWordIndex;
        }

        Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2 + 1);
        for (RomanizedWordCluster rc : romanClusters) {
            clusterMap.put(rc.wordIndex(), rc);
        }
        Map<Integer, RomanizedWordCluster> rubyMap = new HashMap<>(rubyClusters.size() * 2 + 1);
        for (RomanizedWordCluster rc : rubyClusters) {
            rubyMap.put(rc.wordIndex(), rc);
        }

        float runningShift = 0f;
        int currentLine = -1;
        final int maxCharIdx = Math.max(0, mainText.length() - 1);
        final int maxMapIdx = Math.max(0, charXMap.length - 1);

        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            final int li = textLayoutResult.getLineForOffset(
                    Math.max(0, Math.min(syl.startOffset(), maxCharIdx)));
            if (li != currentLine) {
                currentLine = li;
                runningShift = 0f;
            }
            if (sylIdx < shiftByWordIndex.length) {
                shiftByWordIndex[sylIdx] = runningShift;
            }

            RomanizedWordCluster cluster = clusterMap.get(sylIdx);
            RomanizedWordCluster rubyCluster = rubyMap.get(sylIdx);
            if (cluster == null && rubyCluster == null) {
                continue;
            }
            final int sylEndLine = textLayoutResult.getLineForOffset(
                    Math.max(0, Math.min(syl.endOffset(), maxCharIdx)));
            final int lineEndOff = textLayoutResult.getLineVisibleEnd(li);

            // Include any trailing space before the next syllable on the same line so we compare
            // (word + space) in mainLayout against max(romajiWidth, rubyWidth).
            int effectiveEndOff = syl.endOffset();
            if (li == sylEndLine && sylIdx + 1 < alignedSyllables.size()) {
                AlignedSyllable nextSyl = alignedSyllables.get(sylIdx + 1);
                final int nextLi = textLayoutResult.getLineForOffset(
                        Math.max(0, Math.min(nextSyl.startOffset(), maxCharIdx)));
                if (nextLi == li && nextSyl.startOffset() > syl.endOffset()) {
                    effectiveEndOff = nextSyl.startOffset();
                }
            }

            final float startX = charXMap[Math.max(0, Math.min(syl.startOffset(), maxMapIdx))];
            final float endX = (li == sylEndLine)
                    ? charXMap[Math.max(0, Math.min(effectiveEndOff, maxMapIdx))]
                    : charXMap[Math.max(0, Math.min(lineEndOff, maxMapIdx))];
            final float mainWidth = Math.max(1f, endX - startX);
            final float romWidth = cluster != null ? cluster.romanWidth() : 0f;
            final float rubyWidth = rubyCluster != null ? rubyCluster.romanWidth() : 0f;
            final float slotSubWidth = Math.max(romWidth, rubyWidth);

            if (slotSubWidth > mainWidth) {
                final float extra = slotSubWidth - mainWidth;
                runningShift += extra;
                if (li >= 0 && li < lineTotalExtra.length) {
                    lineTotalExtra[li] += extra;
                }
            }
        }

        if (alignment == Layout.Alignment.ALIGN_CENTER || alignment == Layout.Alignment.ALIGN_OPPOSITE) {
            for (int idx = 0; idx < alignedSyllables.size() && idx < shiftByWordIndex.length; idx++) {
                AlignedSyllable syl = alignedSyllables.get(idx);
                final int li = textLayoutResult.getLineForOffset(
                        Math.max(0, Math.min(syl.startOffset(), maxCharIdx)));
                final float totalExtra = (li >= 0 && li < lineTotalExtra.length) ? lineTotalExtra[li] : 0f;
                final float correction = (alignment == Layout.Alignment.ALIGN_CENTER)
                        ? totalExtra / 2f : totalExtra;
                shiftByWordIndex[idx] -= correction;
            }
        }

        return shiftByWordIndex;
    }

    private static float calculateGapShift(int syllableIndex, float[] romanStartShift) {
        if (syllableIndex < 0 || syllableIndex >= romanStartShift.length) {
            return 0f;
        }
        return romanStartShift[syllableIndex];
    }

    private static List<VisualCluster> buildVisualClusters(
            String formattedMainText,
            Layout textLayoutResult,
            List<AlignedSyllable> alignedSyllables,
            float[] lineBaselines,
            TextPaint mainPaint) {
        List<ClusterMeta> metaList = new ArrayList<>(formattedMainText.length());
        final int len = formattedMainText.length();
        final int maxIdx = Math.max(0, len - 1);
        int i = 0;
        while (i < len) {
            final int start = i;
            final int end = (i + 1 < len
                    && Character.isHighSurrogate(formattedMainText.charAt(i))
                    && Character.isLowSurrogate(formattedMainText.charAt(i + 1)))
                    ? i + 2 : i + 1;
            i = end;

            if (formattedMainText.charAt(start) == '\n') {
                continue;
            }
            final int safeStart = Math.max(0, Math.min(start, maxIdx));
            final int safeEnd = Math.min(end, maxIdx);
            final int li = textLayoutResult.getLineForOffset(safeStart);
            final int eli = textLayoutResult.getLineForOffset(safeEnd);
            final float hStart = textLayoutResult.getPrimaryHorizontal(safeStart);
            final float hEnd = (li == eli)
                    ? textLayoutResult.getPrimaryHorizontal(Math.min(end, len))
                    : textLayoutResult.getLineRight(li);
            final float x = Math.min(hStart, hEnd);
            final float w = Math.abs(hEnd - hStart);
            metaList.add(new ClusterMeta(start, end, li, x, w));
        }

        int[] sylIndices = new int[metaList.size()];
        int[] shiftIndices = new int[metaList.size()];
        int sylPointer = 0;
        int lastSylOnLine = -1;
        int curLine = -1;
        for (int mIdx = 0; mIdx < metaList.size(); mIdx++) {
            ClusterMeta m = metaList.get(mIdx);
            if (m.lineIdx() != curLine) {
                curLine = m.lineIdx();
                lastSylOnLine = -1;
            }
            while (sylPointer < alignedSyllables.size()
                    && alignedSyllables.get(sylPointer).endOffset() <= m.start()) {
                sylPointer++;
            }
            if (sylPointer < alignedSyllables.size()) {
                AlignedSyllable s = alignedSyllables.get(sylPointer);
                if (m.start() >= s.startOffset() && m.end() <= s.endOffset()) {
                    sylIndices[mIdx] = sylPointer;
                    shiftIndices[mIdx] = sylPointer;
                    lastSylOnLine = sylPointer;
                } else {
                    sylIndices[mIdx] = -1;
                    shiftIndices[mIdx] = lastSylOnLine >= 0 ? lastSylOnLine : sylPointer;
                }
            } else {
                sylIndices[mIdx] = -1;
                shiftIndices[mIdx] = lastSylOnLine;
            }
        }

        List<VisualCluster> list = new ArrayList<>();
        int idx = 0;
        while (idx < metaList.size()) {
            ClusterMeta m = metaList.get(idx);
            final int runSylIdx = sylIndices[idx];
            final int runShiftIdx = shiftIndices[idx];
            int j = idx;
            // Only merge consecutive characters that belong to the same valid syllable on the same line
            if (runSylIdx >= 0) {
                while (j + 1 < metaList.size()
                        && metaList.get(j + 1).lineIdx() == m.lineIdx()
                        && sylIndices[j + 1] == runSylIdx) {
                    j++;
                }
            }
            float runLeft = Float.MAX_VALUE;
            float runRight = -Float.MAX_VALUE;
            for (int k = idx; k <= j; k++) {
                ClusterMeta cm = metaList.get(k);
                if (cm.x() < runLeft) runLeft = cm.x();
                if (cm.x() + cm.width() > runRight) runRight = cm.x() + cm.width();
            }
            ClusterMeta last = metaList.get(j);
            String runText = formattedMainText.substring(
                    Math.max(0, Math.min(m.start(), len)),
                    Math.max(0, Math.min(last.end(), len)));
            TextPaint subPaint = new TextPaint(mainPaint);
            StaticLayout subLayout = buildSingleLineLayout(runText, subPaint);
            final float subW = Math.max(runRight - runLeft, singleLineWidth(runText, subPaint, subLayout));
            final float clusterY = lineBaselines[m.lineIdx()] - subLayout.getLineBaseline(0);
            list.add(new VisualCluster(m.lineIdx(), runLeft, clusterY, Math.max(1f, subW),
                    subLayout, runSylIdx, runShiftIdx));
            idx = j + 1;
        }
        return list;
    }

    private static ClusterStatics buildClusterStatics(
            List<VisualCluster> clusters,
            float[] romanStartShift,
            float lineExtraHeight) {
        final int n = clusters.size();
        float[] baseX = new float[n];
        float[] baseY = new float[n];
        for (int i = 0; i < n; i++) {
            VisualCluster vc = clusters.get(i);
            final int shiftIdx = vc.syllableIndex() >= 0 ? vc.syllableIndex() : vc.shiftIndex();
            baseX[i] = vc.x() + calculateGapShift(shiftIdx, romanStartShift);
            baseY[i] = vc.y() + vc.lineIdx() * lineExtraHeight;
        }
        return new ClusterStatics(baseX, baseY);
    }

    @SuppressWarnings("unchecked")
    private static List<RomanizedWordCluster>[] groupRomanClustersByLine(
            List<RomanizedWordCluster> romanClusters, int lineCount) {
        if (romanClusters.isEmpty()) {
            return new List[0];
        }
        List<RomanizedWordCluster>[] lines = new List[lineCount];
        for (int li = 0; li < lineCount; li++) {
            lines[li] = new ArrayList<>();
        }
        for (RomanizedWordCluster rc : romanClusters) {
            if (rc.lineIdx() >= 0 && rc.lineIdx() < lineCount) {
                lines[rc.lineIdx()].add(rc);
            }
        }
        return lines;
    }

    private static RomanLineStatics[] buildRomanStatics(
            List<RomanizedWordCluster>[] romanLines,
            float[] charXMap,
            float[] romanStartShift,
            float[] lineBaselines,
            float[] lineBottoms,
            float[] lineLefts,
            float[] lineStarts,
            int lineCount,
            float fontSizePx,
            float lineExtraHeight,
            float totalWidth) {
        final int count = Math.min(lineCount, romanLines.length);
        RomanLineStatics[] lines = new RomanLineStatics[count];

        for (int li = 0; li < count; li++) {
            List<RomanizedWordCluster> cs = romanLines[li];
            if (cs == null || cs.isEmpty()) {
                continue;
            }
            final float lineLeft = li < lineLefts.length ? lineLefts[li] : 0f;
            final float lineStart = li < lineStarts.length ? lineStarts[li] : 0f;
            final int n = cs.size();
            float[] drawX = new float[n];
            for (int i = 0; i < n; i++) {
                RomanizedWordCluster c = cs.get(i);
                final float gx = c.startIndex() < 0 ? 0f
                        : (c.startIndex() >= charXMap.length ? totalWidth : charXMap[c.startIndex()]);
                final float shift = (c.wordIndex() >= 0 && c.wordIndex() < romanStartShift.length)
                        ? romanStartShift[c.wordIndex()] : 0f;
                drawX[i] = gx - lineStart + lineLeft + shift;
            }
            final float baseline = li < lineBaselines.length ? lineBaselines[li] : 0f;
            final float y = baseline + fontSizePx * 0.12f + li * lineExtraHeight;
            lines[li] = new RomanLineStatics(cs, drawX, y);
        }
        return lines;
    }

    private static List<RomanizedWordCluster> buildRubyClusters(
            List<AlignedSyllable> alignedSyllables,
            String mainText,
            Layout textLayoutResult,
            TextPaint rubyPaint,
            float maxWidthPx) {
        if (alignedSyllables.isEmpty() || mainText.isEmpty()) {
            return Collections.emptyList();
        }
        boolean anyRuby = false;
        for (AlignedSyllable syl : alignedSyllables) {
            String r = syl.syllable().ruby();
            if (r != null && !r.trim().isEmpty()) {
                anyRuby = true;
                break;
            }
        }
        if (!anyRuby) {
            return Collections.emptyList();
        }

        List<RomanizedWordCluster> result = new ArrayList<>(alignedSyllables.size());
        final int maxCharIdx = Math.max(0, mainText.length() - 1);
        for (int idx = 0; idx < alignedSyllables.size(); idx++) {
            AlignedSyllable syl = alignedSyllables.get(idx);
            String text = syl.syllable().ruby();
            if (text != null && !text.trim().isEmpty()) {
                final int clampedStart = Math.max(0, Math.min(syl.startOffset(), maxCharIdx));
                final int li = textLayoutResult.getLineForOffset(clampedStart);
                TextPaint subPaint = new TextPaint(rubyPaint);
                StaticLayout subLayout = buildFittedLayout(text, subPaint, maxWidthPx);
                final float romW = singleLineWidth(text, subPaint, subLayout);
                result.add(new RomanizedWordCluster(idx, syl.startOffset(), li, subLayout, romW));
            }
        }
        return result;
    }

    /** Where the furigana of each line is drawn, above the words it reads. */
    private static RomanLineStatics[] buildRubyStatics(
            List<RomanizedWordCluster>[] rubyLines,
            float[] charXMap,
            float[] romanStartShift,
            float[] lineBaselines,
            float[] lineLefts,
            float[] lineStarts,
            int lineCount,
            float fontSizePx,
            float lineExtraHeight,
            float totalWidth) {
        final int count = Math.min(lineCount, rubyLines.length);
        RomanLineStatics[] lines = new RomanLineStatics[count];

        for (int li = 0; li < count; li++) {
            List<RomanizedWordCluster> cs = rubyLines[li];
            if (cs == null || cs.isEmpty()) {
                continue;
            }
            final float lineLeft = li < lineLefts.length ? lineLefts[li] : 0f;
            final float lineStart = li < lineStarts.length ? lineStarts[li] : 0f;
            final int n = cs.size();
            float[] drawX = new float[n];
            for (int i = 0; i < n; i++) {
                RomanizedWordCluster c = cs.get(i);
                final float gx = c.startIndex() < 0 ? 0f
                        : (c.startIndex() >= charXMap.length ? totalWidth : charXMap[c.startIndex()]);
                final float shift = (c.wordIndex() >= 0 && c.wordIndex() < romanStartShift.length)
                        ? romanStartShift[c.wordIndex()] : 0f;
                drawX[i] = gx - lineStart + lineLeft + shift;
            }
            float maxClusterHeight = 0;
            for (RomanizedWordCluster c : cs) {
                maxClusterHeight = Math.max(maxClusterHeight, c.subLayout().getHeight());
            }
            final float baseBaseline = li < lineBaselines.length
                    ? lineBaselines[li] : (fontSizePx * 0.8f);
            final float rubyGap = Dim.dp(4);
            // Furigana rides along with the line it reads
            final float y = baseBaseline - fontSizePx * 0.85f - rubyGap - maxClusterHeight
                    + li * lineExtraHeight;
            lines[li] = new RomanLineStatics(cs, drawX, y);
        }
        return lines;
    }

    /** Draws and sweeps a line the way {@link #onDraw} does, with each syllable's reading under it. */
    private void drawSyllableRomanizedLine(Canvas canvas, SyllableRomanCache cache) {
        if (contentReveal <= 0f) {
            return;
        }
        final float contentNow = contentReveal;
        final float originX = textOriginX();
        final float originY = textOriginY();
        final float mainY = originY + cache.mainTopY;

        // The unsung colours, as drawBaseText picks them. Without a real sweep (synthesized
        // plain-text timings), the main text is not being karaoke-swept, so it stays bright.
        final boolean hasWordSweep = hasWordSweep();
        final int mainUnsungAlpha = alpha255(unsungMainAlpha(hasWordSweep) * contentNow);
        final int transAlpha = alpha255(translationAlpha() * contentNow * transReveal);
        final float readingAlpha = unsungReadingAlpha(hasWordSweep);
        final int romaUnsungAlpha = alpha255(readingAlpha * contentNow * romaReveal);
        // Ruby is no romanization, so the romanization reveal leaves it alone.
        final int rubyUnsungAlpha = alpha255(readingAlpha * contentNow);

        final int baseMainRgb = (getCurrentTextColor() | 0xFF000000) & 0x00FFFFFF;
        final int readingRgb = (activeLine
                ? baseMainRgb
                : (LyricsColors.secondaryTextColor() | 0xFF000000)) & 0x00FFFFFF;

        final int mainUnsungColor = baseMainRgb | (Math.max(0, Math.min(255, mainUnsungAlpha)) << 24);
        final int romaUnsungColor = readingRgb | (Math.max(0, Math.min(255, romaUnsungAlpha)) << 24);
        final int transColor = readingRgb | (Math.max(0, Math.min(255, transAlpha)) << 24);

        List<VisualCluster> clusters = cache.clusters;
        ClusterStatics statics = cache.statics;

        // Everything unsung first, then the sung part on top of it.
        if (mainUnsungAlpha > 0) {
            for (int idx = 0; idx < clusters.size(); idx++) {
                VisualCluster vc = clusters.get(idx);
                TextPaint p = vc.subLayout().getPaint();
                p.setShader(null);
                p.setColor(mainUnsungColor);
                canvas.save();
                canvas.translate(originX + statics.baseX()[idx], mainY + statics.baseY()[idx]);
                vc.subLayout().draw(canvas);
                canvas.restore();
            }
        }

        if (romaReveal > 0f && romaUnsungAlpha > 0) {
            drawUnsungReadings(canvas, cache.romanLines, originX, mainY, romaUnsungColor);
        }
        if (rubyUnsungAlpha > 0) {
            drawUnsungReadings(canvas, cache.rubyLines, originX, mainY,
                    readingRgb | (Math.max(0, Math.min(255, rubyUnsungAlpha)) << 24));
        }

        if (cache.transLayout != null && transReveal > 0f && transAlpha > 0) {
            TextPaint tp = cache.transLayout.getPaint();
            tp.setShader(null);
            tp.setColor(transColor);
            canvas.save();
            canvas.translate(originX, originY + cache.transTopY + translationFlipOffsetY);
            cache.transLayout.draw(canvas);
            canvas.restore();
        }

        // The sweep, as onDraw runs it. When allSung is true or not active, the base pass above already drew
        // the entire line and readings at lookCurAlpha / lookCurReadingAlpha.
        if (!hasWordSweep || (!activeLine && lineLookAnimator == null)) {
            return;
        }

        final int lineCount = cache.lineCount;
        if (lineMaxSungX == null || lineMaxSungX.length < lineCount) {
            lineMaxSungX = new float[lineCount];
        }
        Arrays.fill(lineMaxSungX, 0, lineCount, Float.NaN);

        List<AlignedSyllable> alignedSyllables = cache.alignedSyllables;
        final int sylCount = alignedSyllables.size();
        float[] sylProgress = cache.sylProgressScratch;
        int firstSungLine = -1;

        // Read on every frame while sweeping, so the sweep moves between position updates.
        final long currentSyllablePos = (this.positionMs != Long.MIN_VALUE && hasWordSweep && activeLine)
                ? getLiveClockPositionMs()
                : this.positionMs;
        for (int i = 0; i < sylCount; i++) {
            LyricsSpanBuilder.WordTiming timing = alignedSyllables.get(i).syllable();
            final float progress;
            if (allSung) {
                progress = 1f;
            } else if (currentSyllablePos >= timing.endMs()) {
                progress = 1f;
            } else if (currentSyllablePos <= timing.startMs()) {
                progress = 0f;
            } else {
                progress = (float) (currentSyllablePos - timing.startMs())
                        / (float) (timing.endMs() - timing.startMs());
            }
            sylProgress[i] = progress;
            if (progress <= 0f) {
                continue;
            }
            final int lineNum = cache.sylLine[i];
            if (firstSungLine < 0) {
                firstSungLine = lineNum;
            }
            final float lead = cache.sylShiftedLeadX[i];
            final float trail = cache.sylShiftedTrailX[i];
            final float edge = lead + (trail - lead) * progress;
            if (lineNum >= 0 && lineNum < lineCount) {
                lineMaxSungX[lineNum] = extendSung(lineMaxSungX[lineNum], edge, false);
            }
        }

        if (allSung && firstSungLine < 0) {
            for (int ln = 0; ln < lineCount; ln++) {
                lineMaxSungX[ln] = Float.MAX_VALUE;
            }
        }

        if (!sweepEnabled && activeLine) {
            for (int ln = 0; ln < lineCount; ln++) {
                lineMaxSungX[ln] = Float.MAX_VALUE;
            }
            // Readings light whole too, not per-syllable from the synthesized range.
            for (int i = 0; i < sylCount; i++) {
                sylProgress[i] = 1f;
            }
        }

        final float sungMainFactor = (lineLookInitialized && !isSecondary) ? lookCurAlpha : lineAlpha;
        final int mainSungAlpha = Math.max(0, Math.min(255, Math.round(255f * sungMainFactor * contentNow)));
        final int mainSungColor = (LyricsColors.lineTextColor() & 0x00FFFFFF)
                | (mainSungAlpha << 24);

        // Each lyric line is lit up to its furthest sung edge.
        for (int idx = 0; idx < clusters.size(); idx++) {
            VisualCluster vc = clusters.get(idx);
            final int ln = vc.lineIdx();
            if (ln < 0 || ln >= lineCount) {
                continue;
            }
            final float edge = lineMaxSungX[ln];
            if (Float.isNaN(edge)) {
                continue;
            }
            final float cx = statics.baseX()[idx];
            if (edge <= cx) {
                continue;
            }
            TextPaint p = vc.subLayout().getPaint();
            p.setShader(null);
            p.setColor(mainSungColor);
            canvas.save();
            final float tx = originX + cx;
            final float ty = mainY + statics.baseY()[idx];
            if (edge < cx + vc.width()) {
                canvas.clipRect(tx, ty - Dim.dp(4), originX + edge, ty + vc.subLayout().getHeight() + Dim.dp(4));
            }
            canvas.translate(tx, ty);
            vc.subLayout().draw(canvas);
            canvas.restore();
        }

        // Each reading is lit as far as its own syllable is sung.
        final float sungReadFactor = (lineLookInitialized && !isSecondary)
                ? lookCurReadingAlpha
                : (lineAlpha * LyricsColors.ACTIVE_READING_ALPHA);
        final int sungReadingAlpha = Math.round(sungReadFactor * 255f * contentNow);
        if (romaReveal > 0f) {
            final int romaSungAlpha = Math.round(sungReadingAlpha * romaReveal);
            drawSungReadings(canvas, cache.romanLines, originX, mainY,
                    readingRgb | (Math.max(0, Math.min(255, romaSungAlpha)) << 24),
                    sylProgress, sylCount, allSung);
        }
        drawSungReadings(canvas, cache.rubyLines, originX, mainY,
                readingRgb | (Math.max(0, Math.min(255, sungReadingAlpha)) << 24),
                sylProgress, sylCount, allSung);
    }

    private static int alpha255(float alpha) {
        return Math.max(0, Math.min(255, Math.round(255f * alpha)));
    }

    /** Whether the words are being swept now, or the line is still fading out of it. */
    private boolean hasWordSweep() {
        return unsungColor != 0 && !wordTimings.isEmpty() && sweepEnabled && !allSung
                && (activeLine || lineLookAnimator != null);
    }

    /** {@return the alpha of the main text where it is not sung yet} */
    private float unsungMainAlpha(boolean hasWordSweep) {
        if (lineLookInitialized) {
            return hasWordSweep ? lookCurUnsungAlpha : lookCurAlpha;
        }
        final boolean dimMain = unsungColor != 0 && !wordTimings.isEmpty() && sweepEnabled;
        return (dimMain ? LyricsColors.UNSUNG_ALPHA : baseTextAlpha / 255f) * lineAlpha;
    }

    private float translationAlpha() {
        if (lineLookInitialized) {
            return lookCurTransAlpha;
        }
        return lineAlpha * (activeLine ? LyricsColors.ACTIVE_TRANSLATION_ALPHA : LyricsColors.UNSUNG_ALPHA);
    }

    /** {@return the alpha of the romanization and ruby where they are not sung yet} */
    private float unsungReadingAlpha(boolean hasWordSweep) {
        final boolean readingSwept = hasWordSweep && Settings.LYRICS_WORD_SYNC.get();
        if (lineLookInitialized) {
            return readingSwept ? lookCurUnsungAlpha : lookCurReadingAlpha;
        }
        if (!activeLine) {
            return lineAlpha * LyricsColors.UNSUNG_ALPHA;
        }
        return lineAlpha * (readingSwept ? LyricsColors.UNSUNG_ALPHA : LyricsColors.ACTIVE_READING_ALPHA);
    }

    private static void drawUnsungReadings(Canvas canvas, RomanLineStatics[] lines,
                                           float originX, float mainY, int color) {
        for (RomanLineStatics line : lines) {
            if (line == null) {
                continue;
            }
            List<RomanizedWordCluster> cs = line.clusters();
            final float topY = mainY + line.y();
            for (int i = 0; i < cs.size(); i++) {
                RomanizedWordCluster rc = cs.get(i);
                TextPaint p = rc.subLayout().getPaint();
                p.setShader(null);
                p.setColor(color);
                canvas.save();
                canvas.translate(originX + line.drawX()[i], topY);
                rc.subLayout().draw(canvas);
                canvas.restore();
            }
        }
    }

    private static void drawSungReadings(Canvas canvas, RomanLineStatics[] lines, float originX,
                                         float mainY, int color, float[] sylProgress, int sylCount,
                                         boolean allSung) {
        for (RomanLineStatics line : lines) {
            if (line == null) {
                continue;
            }
            List<RomanizedWordCluster> cs = line.clusters();
            final float topY = mainY + line.y();
            for (int i = 0; i < cs.size(); i++) {
                RomanizedWordCluster rc = cs.get(i);
                final float prog = (rc.wordIndex() >= 0 && rc.wordIndex() < sylCount)
                        ? sylProgress[rc.wordIndex()]
                        : (allSung ? 1f : 0f);
                if (prog <= 0f) {
                    continue;
                }
                TextPaint p = rc.subLayout().getPaint();
                p.setShader(null);
                p.setColor(color);
                final float rx = originX + line.drawX()[i];
                canvas.save();
                if (prog < 1f) {
                    canvas.clipRect(rx, topY - Dim.dp(2),
                            rx + rc.romanWidth() * prog,
                            topY + rc.subLayout().getHeight() + Dim.dp(2));
                }
                canvas.translate(rx, topY);
                rc.subLayout().draw(canvas);
                canvas.restore();
            }
        }
    }

    private static int lineDrawing(Layout layout, int offset) {
        int low = 0;
        int high = layout.getLineCount() - 1;
        while (low < high) {
            final int mid = (low + high) >>> 1;
            if (offset < layout.getLineStart(mid + 1)) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private static boolean isRightToLeftLine(Layout layout, int line) {
        if (layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
            return true;
        }
        if (line >= 0 && line < layout.getLineCount()) {
            final int start = layout.getLineStart(line);
            final int end = layout.getLineEnd(line);
            CharSequence text = layout.getText();
            for (int i = start; i < end && i < text.length(); i++) {
                byte dir = Character.getDirectionality(text.charAt(i));
                if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                    return true;
                } else if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                    return false;
                }
            }
        }
        return false;
    }

    /** Right to left lines fill leftwards, so their furthest point is the smallest one. */
    private static float extendSung(float current, float edge, boolean rightToLeft) {
        if (Float.isNaN(current)) {
            return edge;
        }
        return rightToLeft ? Math.min(current, edge) : Math.max(current, edge);
    }
}
