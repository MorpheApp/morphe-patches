/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.icu.text.BreakIterator;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.ui.SpringEasing.LyricsSpring;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.AlignedSyllable;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.VisualCluster;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WordTimestamp;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

final class LyricsLineView extends TextView {
    private static final int REVEAL_TRANS = 1;
    private static final int REVEAL_ROMA = 2;

    /**
     * A background vocal opens over about this long when its lead line comes up, unless the
     * scroll sets its own time.
     */
    private static final long SECONDARY_EXPAND_MILLISECONDS = 400;
    /** A closed background vocal is drawn this much smaller, so it grows out of its lead line. */
    private static final float SECONDARY_CLOSED_SCALE = 0.80f;
    /** Room between a background vocal and its lead line. */
    private static final int SECONDARY_SPACING_PX = Dim.dp(8);
    /** A line look change runs at least this long. */
    public static final int LINE_VISIBILITY_MS = 250;

    private List<WordTiming> wordTimings = Collections.emptyList();
    long positionMs = Long.MIN_VALUE;
    boolean allSung = false;
    private int unsungColor;
    private boolean sweepEnabled;
    int originalTextStart;
    private float[] lineMaxSungX;
    /** The word currently being sung, so karaoke lights it alone. */
    private float[] lineKaraokeStartX;
    private float[] lineKaraokeEndX;
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

    /** The hand-built layout of a line drawn with WordSweepAnimator or syllable readings, see {@link #syllableCacheFor}. */
    @Nullable
    private SyllableRomanCache syllableCache;
    @Nullable
    private SyllableRomanCache pendingReusableCache;
    private boolean prewarmMasks = false;
    @Nullable
    private Long adjustedEndTimeMs = null;
    private long lastDrawnClockPos = Long.MIN_VALUE;

    private void releaseSyllableCache() {
        discardCache(syllableCache);
        syllableCache = null;
    }

    private boolean preserveReusableCacheOnDetach;
    private boolean deferSyllableBuild;

    void setDeferSyllableBuild(boolean defer) {
        this.deferSyllableBuild = defer;
    }

    private void stashCurrentSyllableCache() {
        if (syllableCache != null) {
            if (pendingReusableCache != syllableCache) {
                discardCache(pendingReusableCache);
            }
            pendingReusableCache = syllableCache;
            syllableCache = null;
        }
    }

    void releasePendingReusableCache() {
        preserveReusableCacheOnDetach = false;
        discardCache(pendingReusableCache);
        pendingReusableCache = null;
    }

    void stashSyllableCacheForReuse() {
        preserveReusableCacheOnDetach = true;
        stashCurrentSyllableCache();
    }

    /** Preserves existing animator/layout caches before an in-place bounds/text update. */
    void prepareForInPlaceUpdate() {
        stashCurrentSyllableCache();
        preserveReusableCacheOnDetach = false;
    }

    void adoptPendingReusableCache(@Nullable LyricsLineView prevView) {
        if (prevView == null) {
            return;
        }
        prevView.preserveReusableCacheOnDetach = false;
        if (prevView.pendingReusableCache == null) {
            return;
        }
        releasePendingReusableCache();
        pendingReusableCache = prevView.pendingReusableCache;
        prevView.pendingReusableCache = null;
    }

    public void setPrewarmMasks(boolean prewarm) {
        this.prewarmMasks = prewarm;
        if (this.syllableCache != null && this.syllableCache.animator != null) {
            this.syllableCache.animator.setPrewarmMasks(prewarm);
        }
    }

    public void setAdjustedEndTimeMs(@Nullable Long adjustedEndMs) {
        this.adjustedEndTimeMs = adjustedEndMs;
    }

    void onWordAnimationSettingsChanged() {
        if (!shouldUseSyllableCache()) {
            releaseSyllableCache();
        } else if (!Settings.isWordAnimSmooth()
                && syllableCache != null && syllableCache.animator != null) {
            syllableCache.animator.evictMasks();
        }
        requestLayout();
        invalidate();
    }

    @SuppressWarnings("ConstantValue")
    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        wordTimings = Collections.emptyList();
        positionMs = Long.MIN_VALUE;
        lastDrawnClockPos = Long.MIN_VALUE;
        allSung = false;
        cachedLayout = null;
        cachedText = null;
        releaseSyllableCache();
        if (fittedState != null && !Objects.equals(fittedState.text, text)) {
            fittedState.width = -1;
        }
        invalidate();
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        cachedLayout = null;
        exitingTransLayout = null;
        exitingRomaLayout = null;
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

    private static final float REGION_CLOSED_SCALE = 0.80f;
    private static final float REGION_SLIDE_FACTOR = 0.35f;

    float contentReveal = 1f;
    float transReveal = 1f;
    float romaReveal = 1f;
    float transScaleProgress = 1f;
    float romaScaleProgress = 1f;
    private ValueAnimator contentRevealAnimator;

    @Nullable
    private String exitingTransText;
    private boolean exitingTransBeforeMain;
    @Nullable
    private StaticLayout exitingTransLayout;
    private int exitingTransWidth = -1;

    @Nullable
    private String exitingRomaText;
    @Nullable
    private StaticLayout exitingRomaLayout;
    private int exitingRomaWidth = -1;
    private List<WordTiming> exitingRomaTimings = Collections.emptyList();
    private boolean exitingShowSyllableRomanization;
    private boolean exitingRubyActive;

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

    /** {@return the playback position now, with monotonic clock guard so words do not glitch backwards} */
    private long getLiveClockPositionMs() {
        long rawPos = LyricsManager.getInstance().getPositionMs();
        boolean isPlaying = LyricsManager.getInstance().isPlaying();
        if (lastDrawnClockPos != Long.MIN_VALUE) {
            if (!isPlaying) {
                if (Math.abs(rawPos - lastDrawnClockPos) <= 300L && (rawPos >= 200L || lastDrawnClockPos < 600L)) {
                    rawPos = lastDrawnClockPos;
                }
            } else if (rawPos >= lastDrawnClockPos - 600L && (rawPos >= 200L || lastDrawnClockPos < 600L)) {
                rawPos = Math.max(lastDrawnClockPos, rawPos);
            }
        }
        lastDrawnClockPos = rawPos;
        return rawPos;
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
            WordTiming wt = wordTimings.get(i);
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

    /** Pre-measures a secondary vocal line at full layout width. */
    void preMeasureSecondaryHeight(int widthPx) {
        if (!isSecondary || widthPx <= 0) return;
        measure(MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    private float computeSecondarySlideMaxY() {
        float h = getHeight() > 0 ? (float) getHeight() : (float) secondaryFullHeight;
        if (h <= 0f) {
            float em = getTextSize() > 0f ? getTextSize() : Dim.dp(20);
            h = em * 1.3f;
        }
        return (bgBeforeMain ? 1f : -1f) * Math.min(Dim.dp(14), Math.max(Dim.dp(6), h * 0.26f));
    }

    private boolean updateSecondaryLayoutMargins(int fullH) {
        if (!isSecondary) return false;
        ViewGroup.LayoutParams rawLp = getLayoutParams();
        if (!(rawLp instanceof ViewGroup.MarginLayoutParams mlp)) return false;
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
            return true;
        }
        return false;
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

    private boolean ownsSecondaryExpansionAlpha() {
        return isSecondary && Settings.LYRICS_SPLIT_BACKGROUND.get();
    }

    private void cancelSecondaryAnimator() {
        if (secondaryAnimator != null) {
            ValueAnimator running = secondaryAnimator;
            secondaryAnimator = null;
            running.cancel();
        }
    }

    private void cancelFadeAnimator() {
        if (fadeAnimator != null) {
            fadeAnimator.cancel();
            fadeAnimator = null;
        }
    }

    void animateSecondaryExpansion(boolean expand, boolean instant) {
        if (!isSecondary) {
            return;
        }
        if (!Settings.LYRICS_SPLIT_BACKGROUND.get()) {
            // Keep background lines statically open in their row; look/blur animators dim them when inactive.
            cancelSecondaryAnimator();
            boolean needsLayoutUpdate = !secondaryLayoutVisible || secondaryAnimatingExit || Math.abs(secondaryFraction - 1f) > 0.001f;
            secondaryFraction = 1f;
            secondaryLayoutVisible = true;
            secondaryAnimatingExit = false;
            setScaleX(1f);
            setScaleY(1f);
            setTranslationY(0f);
            if (getAlpha() != 1.0f) {
                setAlpha(1.0f);
            }
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
            }
            boolean marginsUpdated = updateSecondaryLayoutMargins(secondaryFullHeight);
            if (needsLayoutUpdate || marginsUpdated) {
                requestLayout();
            }
            return;
        }
        if (instant) {
            cancelSecondaryAnimator();
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

        float baseDuration = (scrollEngine != null)
                ? Math.max(300f, Math.min(1100f, (float) scrollEngine.getScrollDurationMs()))
                : (float) SECONDARY_EXPAND_MILLISECONDS;

        if (expand) {
            if ((secondaryFullHeight <= 0 || getWidth() <= 0) && getParent() instanceof View parentView) {
                int pw = parentView.getWidth() - parentView.getPaddingLeft() - parentView.getPaddingRight();
                if (pw > 0) {
                    measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                }
            }
            final int effectiveW = getWidth() > 0 ? getWidth() : getMeasuredWidth();
            final float startProgress = Math.max(0f, Math.min(1f, secondaryFraction));
            final float startAlpha = getVisibility() == View.VISIBLE ? getAlpha() : 0f;
            final float initScale = secondaryScale(startProgress);
            final float initSlideY = computeSecondarySlideMaxY() * (1f - startProgress);

            setPivotX(computeLinePivotX(effectiveW));
            setPivotY(computeSecondaryPivotY());
            setScaleX(initScale);
            setScaleY(initScale);
            setTranslationY(initSlideY);
            setAlpha(startAlpha);

            secondaryLayoutVisible = true;
            secondaryAnimatingExit = false;
            updateSecondaryLayoutMargins(secondaryFullHeight);
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
            }
            reportSecondaryLayoutHeight();
            requestLayout();

            LyricsSpring spring = SpringEasing.springFor(Math.round(baseDuration), 0L);
            long inScaleMs = Math.max(1L, Math.round(spring.totalMs));
            float alphaDelayMs = Math.min(60f, baseDuration * 0.08f);
            float alphaDurMs = Math.max(240f, Math.min(420f, baseDuration * 0.72f));
            long totalMs = Math.max(inScaleMs, Math.round(alphaDelayMs + alphaDurMs));

            ValueAnimator animator = ValueAnimator.ofFloat(0f, (float) totalMs);
            animator.setDuration(totalMs);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                float tMs = (Float) animation.getAnimatedValue();
                float scaleProgress = startProgress + (1f - startProgress) * spring.value(Math.min(tMs, (float) inScaleMs));
                secondaryFraction = Math.max(0f, Math.min(1f, scaleProgress));
                float scale = secondaryScale(scaleProgress);
                float slideY = computeSecondarySlideMaxY() * (1f - scaleProgress);
                int w = getWidth() > 0 ? getWidth() : getMeasuredWidth();
                setPivotX(computeLinePivotX(w));
                setPivotY(computeSecondaryPivotY());
                setScaleX(scale);
                setScaleY(scale);
                setTranslationY(slideY);

                float alphaFrac = Math.max(0f, Math.min(1f, (tMs - alphaDelayMs) / alphaDurMs));
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
            reportSecondaryLayoutHeight();
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
                int w = getWidth() > 0 ? getWidth() : getMeasuredWidth();
                setPivotX(computeLinePivotX(w));
                setPivotY(computeSecondaryPivotY());
                setScaleX(scale);
                setScaleY(scale);
                setTranslationY(slideY);
                float alphaFade = Math.min(1f, f / 0.55f);
                setAlpha(startAlpha * (1f - alphaFade));
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
     * Applies static bright selected or dimmed unselected look during share line selection.
     */
    void updateShareSelectionLook(boolean selected, boolean instant) {
        this.allSung = selected;
        this.activeLine = selected;
        this.lastDrawnClockPos = Long.MIN_VALUE;
        updateLineLook(selected, selected ? 0 : 2, 0L, true, true, true);
        cancelLineLookAnimator();
        lineScale = 1.0f;
        lookFromScale = lookToScale = lookCurScale = 1.0f;
        if (selected) {
            cancelFadeAnimator();
            lookFromUnsungAlpha = lookToUnsungAlpha = lookCurUnsungAlpha = 1.0f;
            lookFromAlpha = lookToAlpha = lookCurAlpha = 1.0f;
            lookFromReadingAlpha = lookToReadingAlpha = lookCurReadingAlpha = LyricsColors.ACTIVE_READING_ALPHA;
            lookFromTransAlpha = lookToTransAlpha = lookCurTransAlpha = LyricsColors.ACTIVE_TRANSLATION_ALPHA;
        }
        applyCurrentLineLookToView();
    }

    /**
     * Clears static share-selection highlights and word-sweep pins before resuming live playback sync.
     */
    void resetShareSelectionWordState() {
        this.allSung = false;
        this.activeLine = false;
        this.lastDrawnClockPos = Long.MIN_VALUE;
        this.positionMs = Long.MIN_VALUE;
        if (syllableCache != null && syllableCache.animator != null) {
            syllableCache.animator.reset();
        }
        invalidate();
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

        final float targetAlpha = isActive
                ? ((isSecondary && !ownsSecondaryExpansionAlpha()) ? LyricsColors.ACTIVE_BG_ALPHA : 1.0f)
                : targetInactiveAlpha;
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
        if (isActive || isSecondary || !isAutoScrollEnabled || !isLrcSynced || LyricsViewSupport.isLineScaleDisabled()) {
            targetScale = 1.0f;
        } else if (focusSpotlightOn) {
            targetScale = LyricsViewSupport.spotlightScale(distance);
        } else {
            targetScale = LyricsViewSupport.INACTIVE_LINE_SCALE;
        }

        final boolean becameActive = !this.activeLine && isActive;
        this.activeLine = isActive;
        if (becameActive) {
            this.allSung = false;
            this.lastDrawnClockPos = Long.MIN_VALUE;
            if (syllableCache != null && syllableCache.animator != null) {
                syllableCache.animator.reset();
            }
        }
        if (instant || !lineLookInitialized) {
            lineLookInitialized = true;
            cancelLineLookAnimator();
            cancelFadeAnimator();
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
        cancelFadeAnimator();

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
        final int baseAlphaMs = springScrollOn ? 750 : 350;
        final int baseScaleMs = springScrollOn ? 800 : 350;
        final int alphaMs = Math.max(1, (int) (baseAlphaMs + staggerDelayMs));
        final int scaleMs = Math.max(1, (int) (baseScaleMs + staggerDelayMs * 2L));
        final int totalMs = Math.max(Math.max(alphaMs, scaleMs), LINE_VISIBILITY_MS);

        lineLookAnimator = ValueAnimator.ofFloat(0f, (float) totalMs);
        lineLookAnimator.setDuration(totalMs);
        lineLookAnimator.setInterpolator(new LinearInterpolator());
        lineLookAnimator.addUpdateListener(anim -> {
            float clockMs = (Float) anim.getAnimatedValue();
            float fAlpha = LyricsViewSupport.FAST_OUT_SLOW_IN.getInterpolation(
                    Math.max(0f, Math.min(1f, clockMs / alphaMs)));
            float fScale = LyricsViewSupport.FAST_OUT_SLOW_IN.getInterpolation(
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
        } else if (!Settings.LYRICS_SPLIT_BACKGROUND.get() && getAlpha() != 1.0f) {
            setAlpha(1.0f);
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

    /** Captures pre-rebuild inner block positions when pre-draw sync was skipped (e.g. spring scroll off). */
    void snapshotInnerFlips() {
        if (isSecondary || getHeight() <= 0) return;
        if (Float.isNaN(mainVocalFlip.lastTop)) {
            mainVocalFlip.lastTop = (float) (getTop() + leadingMainOffsetPx());
        }
        if (Float.isNaN(translationFlip.lastTop)) {
            float transTop = resolveTranslationBlockTopY();
            if (!Float.isNaN(transTop)) {
                translationFlip.lastTop = transTop;
            }
        }
    }

    void copyInnerFlipsFrom(@Nullable LyricsLineView prevView) {
        if (prevView == null) return;
        if (!Float.isNaN(prevView.mainVocalFlip.lastTop)) {
            this.mainVocalFlip.lastTop = prevView.mainVocalFlip.lastTop + prevView.getTranslationY();
            this.mainVocalFlip.seenShiftSeq = prevView.mainVocalFlip.seenShiftSeq;
        }
        if (!Float.isNaN(prevView.translationFlip.lastTop)) {
            this.translationFlip.lastTop = prevView.translationFlip.lastTop + prevView.translationFlipOffsetY;
            this.translationFlip.seenShiftSeq = prevView.translationFlip.seenShiftSeq;
        }
    }

    private float resolveTranslationBlockTopY() {
        SyllableRomanCache syl = syllableCache;
        if (syl != null && syl.active) {
            return syl.transLayout != null ? (float) syl.transTopY : Float.NaN;
        }
        Layout layout = getLayout();
        if (layout != null && transStart >= 0 && transEnd > transStart && transStart < layout.getText().length()) {
            int transLine = layout.getLineForOffset(transStart);
            return (float) layout.getLineTop(transLine);
        }
        return Float.NaN;
    }

    int leadingMainOffsetPx() {
        SyllableRomanCache syl = syllableCache;
        if (syl != null && syl.active) {
            return syl.mainTopY + syl.extraRubyHeightPx;
        }
        Layout layout = getLayout();
        if (layout != null) {
            return LyricsViewSupport.leadingRegionSize(layout, transStart, transEnd)
                    + LyricsViewSupport.leadingRegionSize(layout, romaStart, romaEnd);
        }
        return 0;
    }

    /** Places the line and its translation for the scroll engine, and glides them if they moved. */
    boolean syncInnerFlip(
            LyricsScrollEngine engine,
            int rowIndex,
            boolean enabled,
            float now) {
        boolean active = false;
        if (!isSecondary) {
            engine.onInnerPlaced(mainVocalFlip, rowIndex, (float) (getTop() + leadingMainOffsetPx()), enabled);
            float mainTransY = enabled ? mainVocalFlip.translation(now) : 0f;
            if (Math.abs(mainTransY - getTranslationY()) > 0.1f) {
                setTranslationY(mainTransY);
            }
            if (mainVocalFlip.hasActiveLayers(now)) {
                active = true;
            }
            float transTop = resolveTranslationBlockTopY();
            if (!Float.isNaN(transTop)) {
                engine.onInnerPlaced(translationFlip, rowIndex, transTop, enabled);
                translationFlipOffsetY = enabled ? translationFlip.translation(now) : 0f;
                boolean transActive = translationFlip.hasActiveLayers(now);
                if (Math.abs(translationFlipOffsetY) > 0.1f) {
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
            if (Math.abs(newTransOffset - translationFlipOffsetY) > 0.1f) {
                translationFlipOffsetY = newTransOffset;
                invalidate();
            }
            if (transActive) {
                active = true;
            }
        }
        return active;
    }

    private final LyricsViewSupport.BlurTarget blurTarget = new LyricsViewSupport.BlurTarget(this);

    void animateBlur(float targetRadius, boolean instant) {
        blurTarget.animateTo(targetRadius, instant, hasActiveRegionTransition());
    }

    private boolean hasActiveRegionTransition() {
        return (exitingTransText != null && transReveal > 0f)
                || (exitingRomaText != null && romaReveal > 0f)
                || (exitingShowSyllableRomanization && romaReveal > 0f)
                || (exitingRubyActive && romaReveal > 0f)
                || (transStart >= 0 && transReveal < 0.999f)
                || ((hasRomanizationRegion() || rubyActive) && romaReveal < 0.999f);
    }

    void clearBlur() {
        blurTarget.setRadius(0f, hasActiveRegionTransition());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelScaleAnimation();
        cancelRevealAnimators();
        releaseSyllableCache();
        if (!preserveReusableCacheOnDetach) {
            releasePendingReusableCache();
        }
        blurTarget.cancel();
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
            releaseSyllableCache();
        }
    }

    void setRubyActive(boolean active) {
        if (rubyActive != active) {
            rubyActive = active;
            releaseSyllableCache();
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
            releaseSyllableCache();
            requestLayout();
            invalidate();
        }
    }

    boolean hasRomanizationRegion() {
        return romaStart >= 0 || showSyllableRomanization;
    }

    void setRegionAnimProgress(boolean trans, float reveal, float scaleProg) {
        if (trans) {
            transReveal = reveal;
            transScaleProgress = scaleProg;
            if (syllableCache != null && syllableCache.animator != null) {
                syllableCache.animator.setTransReveal(reveal);
            }
        } else {
            romaReveal = reveal;
            romaScaleProgress = scaleProg;
            if (syllableCache != null && syllableCache.animator != null) {
                syllableCache.animator.setRomaReveal(reveal);
                syllableCache.animator.setRomaScaleProgress(scaleProg);
            }
        }
        invalidate();
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
        if (active) {
            this.allSung = false;
            this.lastDrawnClockPos = Long.MIN_VALUE;
            if (syllableCache != null && syllableCache.animator != null) {
                syllableCache.animator.reset();
            }
        }
        postInvalidateOnAnimation();
    }

    @Nullable
    String extractTranslationText() {
        return extractRegionText(
                syllableCache != null ? syllableCache.transText : null,
                pendingReusableCache != null ? pendingReusableCache.transText : null,
                transStart, transEnd, exitingTransText);
    }

    @Nullable
    String extractRomanizationText() {
        return extractRegionText(
                syllableCache != null ? syllableCache.romaText : null,
                pendingReusableCache != null ? pendingReusableCache.romaText : null,
                romaStart, romaEnd, exitingRomaText);
    }

    @Nullable
    private String extractRegionText(
            @Nullable String activeCachedText,
            @Nullable String reusableCachedText,
            int regionStart,
            int regionEnd,
            @Nullable String exitingText) {
        if (activeCachedText != null && !activeCachedText.isEmpty()) {
            return activeCachedText;
        }
        if (reusableCachedText != null && !reusableCachedText.isEmpty()) {
            return reusableCachedText;
        }
        CharSequence cs = getText();
        if (cs != null && regionStart >= 0 && regionEnd > regionStart && regionStart < cs.length()) {
            String s = cs.subSequence(regionStart, Math.min(regionEnd, cs.length())).toString().trim();
            return s.isEmpty() ? null : s;
        }
        return exitingText;
    }

    private float batchTransFromReveal;
    private float batchTransFromScale;
    private float batchRomaFromReveal;
    private float batchRomaFromScale;

    void prepareBatchRegionIn(boolean trans) {
        final int kind = trans ? REVEAL_TRANS : REVEAL_ROMA;
        clearExitingRegionFields(kind);
        if (trans) {
            batchTransFromReveal = transReveal;
            batchTransFromScale = transScaleProgress;
        } else {
            batchRomaFromReveal = romaReveal;
            batchRomaFromScale = romaScaleProgress;
        }
        blurTarget.clearEffect();
    }

    void applyBatchRegionEnterFrame(boolean trans, float springVal, float easedAlpha) {
        final float fromScale = trans ? batchTransFromScale : batchRomaFromScale;
        final float fromReveal = trans ? batchTransFromReveal : batchRomaFromReveal;
        setRegionAnimProgress(trans,
                fromReveal + (1f - fromReveal) * easedAlpha,
                fromScale + (1f - fromScale) * springVal);
    }

    void applyBatchRegionExitFrame(boolean trans, float easedOut) {
        final float fromScale = trans ? batchTransFromScale : batchRomaFromScale;
        final float fromReveal = trans ? batchTransFromReveal : batchRomaFromReveal;
        setRegionAnimProgress(trans,
                fromReveal * (1f - easedOut),
                fromScale * (1f - easedOut));
    }

    void finishBatchRegionIn(boolean trans) {
        setRegionAnimProgress(trans, 1f, 1f);
        blurTarget.refresh(hasActiveRegionTransition());
    }

    /** @return true if clearing exiting state requires relayout/reflow. */
    boolean finishBatchRegionOutSilent(boolean trans) {
        setRegionAnimProgress(trans, 0f, 0f);
        boolean rebuild = clearExitingRegionState(trans ? REVEAL_TRANS : REVEAL_ROMA);
        if (rebuild) {
            requestLayout();
        }
        return rebuild;
    }

    void captureExitingTranslationInPlace() {
        captureExitingTranslationFrom(this);
    }

    void captureExitingRomanizationInPlace() {
        captureExitingRomanizationFrom(this);
    }

    void captureExitingTranslationFrom(@Nullable LyricsLineView prevView) {
        if (prevView == null) return;
        String prevTrans = prevView.extractTranslationText();
        if (prevTrans == null || prevTrans.isEmpty()) return;
        this.exitingTransText = prevTrans;
        this.exitingTransBeforeMain = prevView.transStart == 0 && prevView.originalTextStart > 0;
        this.exitingTransLayout = null;
        this.exitingTransWidth = -1;
        this.transReveal = Math.max(0.05f, prevView.transReveal);
        this.transScaleProgress = Math.max(0.05f, prevView.transScaleProgress);
        this.batchTransFromReveal = this.transReveal;
        this.batchTransFromScale = this.transScaleProgress;
        blurTarget.clearEffect();
    }

    void captureExitingRomanizationFrom(@Nullable LyricsLineView prevView) {
        if (prevView == null) return;
        String prevRoma = prevView.extractRomanizationText();
        boolean hadSylRoma = prevView.showSyllableRomanization || prevView.exitingShowSyllableRomanization;
        boolean hadRuby = prevView.rubyActive || prevView.exitingRubyActive;
        if ((prevRoma == null || prevRoma.isEmpty()) && !hadSylRoma && !hadRuby) return;
        this.exitingRomaText = prevRoma;
        this.exitingRomaLayout = null;
        this.exitingRomaWidth = -1;
        this.exitingRomaTimings = !prevView.wordTimings.isEmpty()
                ? prevView.wordTimings : prevView.exitingRomaTimings;
        this.exitingShowSyllableRomanization = hadSylRoma;
        this.exitingRubyActive = hadRuby;
        this.romaReveal = Math.max(0.05f, prevView.romaReveal);
        this.romaScaleProgress = Math.max(0.05f, prevView.romaScaleProgress);
        this.batchRomaFromReveal = this.romaReveal;
        this.batchRomaFromScale = this.romaScaleProgress;
        blurTarget.clearEffect();
        releaseSyllableCache();
    }

    private void clearExitingRegionFields(int kind) {
        if (kind == REVEAL_TRANS) {
            exitingTransText = null;
            exitingTransLayout = null;
            exitingTransWidth = -1;
        } else if (kind == REVEAL_ROMA) {
            exitingRomaText = null;
            exitingRomaLayout = null;
            exitingRomaWidth = -1;
            exitingRomaTimings = Collections.emptyList();
            exitingShowSyllableRomanization = false;
            exitingRubyActive = false;
        }
    }

    private boolean clearExitingRegionState(int kind) {
        boolean rebuildCache = false;
        if (kind == REVEAL_ROMA) {
            rebuildCache = (exitingShowSyllableRomanization || exitingRubyActive)
                    && (syllableCache == null
                    || !syllableCache.active
                    || syllableCache.animator == null
                    || syllableCache.animator.needsExitLayoutRebuild());
            if (rebuildCache) {
                stashCurrentSyllableCache();
            }
        }
        clearExitingRegionFields(kind);
        blurTarget.refresh(hasActiveRegionTransition());
        return rebuildCache;
    }

    @Nullable
    private StaticLayout ensureExitingTransLayout(int widthPx) {
        if (exitingTransText == null || exitingTransText.isEmpty() || widthPx <= 0) {
            return null;
        }
        if (exitingTransLayout == null || exitingTransWidth != widthPx) {
            exitingTransWidth = widthPx;
            TextPaint transPaint = new TextPaint(getPaint());
            transPaint.setTextSize(getTextSize() * LyricsSpanBuilder.TRANSLATION_RELATIVE_SIZE);
            LyricsViewSupport.applyLyricsBoldPaint(transPaint);
            transPaint.setLetterSpacing(0f);
            final boolean isTransRtl = LyricsSpanBuilder.isRtlText(exitingTransText);
            Layout.Alignment transAlign =
                    LyricsSpanBuilder.resolveAlignment(isTransRtl, isRightAligned, isCentered);
            exitingTransLayout = StaticLayout.Builder.obtain(
                            exitingTransText, 0, exitingTransText.length(), transPaint, widthPx)
                    .setAlignment(transAlign)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.18f)
                    .build();
        }
        return exitingTransLayout;
    }

    @Nullable
    private StaticLayout ensureExitingRomaLayout(int widthPx) {
        if (exitingRomaText == null || exitingRomaText.isEmpty() || widthPx <= 0) {
            return null;
        }
        if (exitingRomaLayout == null || exitingRomaWidth != widthPx) {
            exitingRomaWidth = widthPx;
            TextPaint romaPaint = new TextPaint(getPaint());
            romaPaint.setTextSize(getTextSize() * LyricsSpanBuilder.TRANSLATION_RELATIVE_SIZE);
            LyricsViewSupport.applyLyricsBoldPaint(romaPaint);
            romaPaint.setLetterSpacing(0f);
            Layout.Alignment romaAlign =
                    LyricsSpanBuilder.resolveAlignment(false, isRightAligned, isCentered);
            exitingRomaLayout = StaticLayout.Builder.obtain(
                            exitingRomaText, 0, exitingRomaText.length(), romaPaint, widthPx)
                    .setAlignment(romaAlign)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.18f)
                    .build();
        }
        return exitingRomaLayout;
    }

    void startContentIn() {
        contentReveal = 0f;
        animateContentReveal(true);
    }

    void animateContentOut() {
        animateContentReveal(false);
    }

    void cancelRevealAnimators() {
        if (contentRevealAnimator != null) {
            contentRevealAnimator.cancel();
            contentRevealAnimator = null;
        }
        if (secondaryAnimator != null) {
            ValueAnimator running = secondaryAnimator;
            secondaryAnimator = null;
            running.cancel();
        }
    }

    private void animateContentReveal(boolean entering) {
        if (contentRevealAnimator != null) {
            ValueAnimator running = contentRevealAnimator;
            contentRevealAnimator = null;
            running.cancel();
        }
        final float target = entering ? 1f : 0f;
        if (Math.abs(contentReveal - target) < 0.01f) {
            contentReveal = target;
            invalidate();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(contentReveal, target);
        animator.setDuration(LyricsPanelView.SECONDARY_REVEAL_MILLISECONDS);
        animator.setInterpolator(entering
                ? LyricsViewSupport.LINEAR_OUT_SLOW_IN
                : LyricsViewSupport.FAST_OUT_SLOW_IN);
        animator.addUpdateListener(animation -> {
            contentReveal = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (contentRevealAnimator == animation) {
                    contentRevealAnimator = null;
                }
            }
        });
        contentRevealAnimator = animator;
        animator.start();
    }

    @Nullable
    String getCopyTextForTouch(float touchX, float touchY) {
        SyllableRomanCache syllables = syllableCache;
        if (syllables != null && syllables.active) {
            final float x = touchX - textOriginX();
            final float y = touchY - textOriginY();

            // Translation region: whole translation, unchanged.
            StaticLayout tLayout = syllables.transLayout;
            if (tLayout != null && (syllables.transTopY == 0
                    ? y < tLayout.getHeight()
                    : (y >= syllables.transTopY && y <= syllables.transTopY + tLayout.getHeight()))) {
                return syllables.transText;
            }

            StaticLayout rLayout = syllables.romaLayout;
            if (!syllables.hasSyllableRomanization && rLayout != null
                    && y >= syllables.romaTopY
                    && y <= syllables.romaTopY + rLayout.getHeight()) {
                return syllables.romaText;
            }

            if (!syllables.hasSyllableRomanization && !syllables.hasRuby) {
                return null;
            }

            final String fallbackText = syllables.mainText != null ? syllables.mainText.trim() : null;
            syllables.ensureSyllableHitBounds();
            if (syllables.sylShiftedLeadX == null || syllables.sylShiftedTrailX == null) {
                return fallbackText;
            }
            int sylIdx = findSyllableByX(x, syllables.sylShiftedLeadX, syllables.sylShiftedTrailX);
            if (sylIdx < 0 || sylIdx >= syllables.alignedSyllables.size()) {
                return fallbackText;
            }
            AlignedSyllable syl = syllables.alignedSyllables.get(sylIdx);

            String[] resolvedRomaji = (syllables.hasSyllableRomanization && syllables.mainText != null)
                    ? WordAnimationUtils.resolvedRomanSyllableTexts(
                    syllables.alignedSyllables, syllables.rawRomWordsKey,
                    syllables.animatorRomArgKey, syllables.mainText)
                    : null;

            // Ruby region: above the main text.
            if (y < syllables.mainTopY + syllables.extraRubyHeightPx) {
                String ruby = syl.syllable().ruby();
                if ((ruby == null || ruby.isEmpty()) && syllables.rawWordsKey != null
                        && sylIdx < syllables.rawWordsKey.size()) {
                    ruby = syllables.rawWordsKey.get(sylIdx).ruby();
                }
                if (ruby != null && !ruby.isEmpty()) {
                    return ruby;
                }
            }

            // Romaji region: below the main text.
            if (y >= syllables.mainTopY + syllables.extraRubyHeightPx + syllables.mainLayoutHeight) {
                String romaji = (resolvedRomaji != null && sylIdx < resolvedRomaji.length)
                        ? resolvedRomaji[sylIdx] : null;
                if (romaji == null || romaji.isEmpty()) {
                    romaji = syl.syllable().romanized();
                }
                if (romaji == null && syllables.rawRomWordsKey != null
                        && sylIdx < syllables.rawRomWordsKey.size()) {
                    romaji = syllables.rawRomWordsKey.get(sylIdx).romanized();
                }
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
            return fallbackText;
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

    /**
     * Subclasses TextView while painting multi-pass word sweeps and {@code saveLayer} gradient
     * masks; declaring overlapping rendering prevents HWUI from pushing view alpha into the
     * nested {@code saveLayer} and double-dimming active words on semi-transparent lines.
     */
    @Override
    public boolean hasOverlappingRendering() {
        return true;
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

    void setHighlight(List<WordTiming> timings, long posMs, boolean sung,
                      int unsungCol, int origStart) {
        if (this.positionMs == posMs && this.allSung == sung
                && this.unsungColor == unsungCol
                && this.originalTextStart == origStart
                && this.wordTimings == timings) {
            return;
        }
        final boolean isPlaying = LyricsManager.getInstance().isPlaying();
        final boolean backwardSeek = (!sung && this.positionMs != Long.MIN_VALUE && posMs != Long.MIN_VALUE
                && ((isPlaying ? posMs < this.positionMs - 600L : posMs < this.positionMs - 400L)
                || (posMs < 200L && this.positionMs >= 600L)));
        List<WordTiming> nextTimings = timings != null ? timings : Collections.emptyList();
        boolean hasRealTimings = false;
        for (WordTiming wt : nextTimings) {
            if (!wt.averaged()) {
                hasRealTimings = true;
                break;
            }
        }
        final boolean structuralChange = this.wordTimings != nextTimings
                || this.originalTextStart != origStart
                || this.unsungColor != unsungCol
                || this.sweepEnabled != hasRealTimings;
        this.wordTimings = nextTimings;
        this.positionMs = posMs;
        if (posMs == Long.MIN_VALUE || backwardSeek) {
            this.lastDrawnClockPos = Long.MIN_VALUE;
            if (syllableCache != null && syllableCache.animator != null) {
                if (backwardSeek) {
                    syllableCache.animator.reset();
                } else {
                    syllableCache.animator.clearSeekProgress();
                }
            }
        }
        this.allSung = sung;
        this.sweepEnabled = hasRealTimings;
        this.unsungColor = unsungCol;
        this.originalTextStart = origStart;
        if (structuralChange) {
            cachedLayout = null;
            releaseSyllableCache();
            requestLayout();
        }
        invalidate();
    }

    private void ensureWordCache(Layout layout, CharSequence text, Paint paint,
                                 List<WordTiming> timings, int origStart) {
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
            WordTiming timing = timings.get(i);
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
            final boolean isSmallRegion = (romaStart >= 0 && s >= romaStart && s < romaEnd)
                    || (transStart >= 0 && s >= transStart && s < transEnd);
            final float sizeScale = isSmallRegion ? LyricsSpanBuilder.TRANSLATION_RELATIVE_SIZE : 1.0f;
            final boolean containsRtl = ScriptDetector.containsRtl(text.subSequence(s, e));
            final boolean isRtl = (!isSmallRegion || containsRtl) && (isRightToLeftLine(layout, line) || containsRtl);
            final int lineVisibleEnd = layout.getLineVisibleEnd(line);
            final float trail;
            if (endLine != line || snappedEnd >= lineVisibleEnd) {
                final float width = paint.measureText(text, s, e) * sizeScale;
                final float measured = isRtl ? lead - width : lead + width;
                if (snappedEnd >= lineVisibleEnd && endLine == line) {
                    trail = isRtl
                            ? Math.max(lineLeft, Math.min(lead, measured))
                            : Math.min(lineRight, Math.max(lead, measured));
                } else {
                    trail = isRtl ? lineLeft : lineRight;
                }
            } else {
                final float end = layout.getPrimaryHorizontal(snappedEnd);
                final float clampedEnd = Math.min(Math.max(end, lineLeft), lineRight);
                if (isRtl) {
                    if (end >= lead) {
                        final float width = paint.measureText(text, s, e) * sizeScale;
                        trail = Math.max(lineLeft, lead - width);
                    } else {
                        trail = clampedEnd;
                    }
                } else {
                    if (end <= lead) {
                        final float width = paint.measureText(text, s, e) * sizeScale;
                        trail = Math.min(lineRight, lead + width);
                    } else {
                        trail = clampedEnd;
                    }
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
        while (clusterStart != BreakIterator.DONE && clusterStart < length) {
            final int next = iterator.next();
            final int clusterEnd = next == BreakIterator.DONE ? length : next;
            for (int offset = clusterStart; offset < clusterEnd && offset <= length; offset++) {
                boundaries[offset] = clusterStart;
            }
            clusterStart = next;
        }
        boundaries[length] = length;
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
                             int alpha) {
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
        final boolean rtlRomaFullBrightness = !hasWordSweep && perWordReading && !smallRunHoldsWords
                && (activeLine || lineLookAnimator != null) && isMainRunRtl(layout);
        final int romaAlpha = alpha255(
                (rtlRomaFullBrightness ? unsungMainAlpha(false) : unsungReadingAlpha(hasWordSweep))
                        * contentNow);

        boolean[] transLine = new boolean[lineCount];
        boolean[] romaLine = new boolean[lineCount];
        markSecondaryLines(layout, transStart, transEnd, transLine);
        markSecondaryLines(layout, romaStart, romaEnd, romaLine);

        boolean hasSecondary = false;
        for (int i = 0; i < lineCount; i++) {
            if (transLine[i] || romaLine[i]) {
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
            drawTextRun(canvas, layout, 0, lineCount - 1, mainAlpha);
            if (hasTransShift && isTransRun) {
                canvas.restore();
            }
            drawExitingOverlaysForBaseLayout(canvas, layout, contentNow, hasWordSweep);
            return true;
        }

        final int romaFirst = rangeLine(layout, romaStart, romaEnd, true);

        int runStart = 0;
        int runKind = romaLine[0] ? 1 : (transLine[0] ? 2 : 0);
        for (int i = 1; i <= lineCount; i++) {
            final boolean end = i == lineCount;
            final int nextKind = end ? -1 : (romaLine[i] ? 1 : (transLine[i] ? 2 : 0));
            if (end || nextKind != runKind) {
                final int runLast = i - 1;
                if (runKind != 0) {
                    final boolean isRoma = runKind == 1;
                    // Only one of the two ranges is set on a dual line, so this run is the reading's
                    // exactly when the reading's range is the one present.
                    final boolean runHoldsWords = smallRunHoldsWords ? isRoma == (romaFirst >= 0) : isRoma;
                    final float reveal = isRoma ? romaReveal : transReveal;
                    final float scaleProg = isRoma ? romaScaleProgress : transScaleProgress;
                    final boolean isTrans = runKind == 2;
                    final int runTop = layout.getLineTop(runStart);
                    final int runBottom = layout.getLineBottom(runLast);
                    final int runH = Math.max(1, runBottom - runTop);
                    final boolean runAboveMain = runStart == 0 && originalTextStart > 0;
                    final boolean transformed = Math.abs(scaleProg - 1f) > 0.002f;
                    if (transformed) {
                        float scale = REGION_CLOSED_SCALE + (1f - REGION_CLOSED_SCALE) * scaleProg;
                        float slideY = (runAboveMain ? 1f : -1f) * REGION_SLIDE_FACTOR * runH * (1f - scaleProg);
                        float pivotX = isRightAligned ? layout.getWidth() : (isCentered() ? layout.getWidth() * 0.5f : 0f);
                        float pivotY = runAboveMain ? runBottom : runTop;
                        canvas.save();
                        canvas.translate(0f, slideY);
                        canvas.scale(scale, scale, pivotX, pivotY);
                    }
                    if (hasTransShift && isTrans) {
                        canvas.save();
                        canvas.translate(0f, transShift);
                    }
                    drawTextRun(canvas, layout, runStart, runLast,
                            Math.round((runHoldsWords ? romaAlpha : transAlpha) * reveal));
                    if (hasTransShift && isTrans) {
                        canvas.restore();
                    }
                    if (transformed) {
                        canvas.restore();
                    }
                } else {
                    drawTextRun(canvas, layout, runStart, runLast, mainAlpha);
                }
                if (!end) {
                    runStart = i;
                    runKind = nextKind;
                }
            }
        }
        drawExitingOverlaysForBaseLayout(canvas, layout, contentNow, hasWordSweep);
        return true;
    }

    private void drawExitingOverlaysForBaseLayout(Canvas canvas, Layout layout, float contentNow, boolean hasWordSweep) {
        final int widthPx = layout.getWidth();
        if (widthPx <= 0) return;
        final int originX = textOriginX();
        final int originY = textOriginY();
        final int baseMainRgb = (LyricsColors.lineTextColor() | 0xFF000000);
        final int readingRgb = (activeLine
                ? baseMainRgb
                : (LyricsColors.secondaryTextColor() | 0xFF000000)) & 0x00FFFFFF;

        if (romaStart < 0 && exitingRomaText != null && romaReveal > 0f) {
            StaticLayout exRoma = ensureExitingRomaLayout(widthPx);
            if (exRoma != null) {
                final float readingAlpha = (activeLine || allSung)
                        ? (lineLookInitialized ? lookCurReadingAlpha : (lineAlpha * LyricsColors.ACTIVE_READING_ALPHA))
                        : unsungReadingAlpha(hasWordSweep);
                final int romaAlpha = alpha255(readingAlpha * contentNow * romaReveal);
                if (romaAlpha > 0) {
                    TextPaint rp = exRoma.getPaint();
                    rp.setShader(null);
                    rp.setColor(readingRgb | (romaAlpha << 24));
                    int origEndOff = originalEnd(layout.getText(), originalTextStart);
                    int mainLastLn = layout.getLineForOffset(Math.max(0, Math.min(layout.getText().length() - 1, origEndOff - 1)));
                    int topY = layout.getLineBottom(mainLastLn) + Dim.dp(2f);
                    float scale = REGION_CLOSED_SCALE + (1f - REGION_CLOSED_SCALE) * romaScaleProgress;
                    float slideY = -REGION_SLIDE_FACTOR * exRoma.getHeight() * (1f - romaScaleProgress);
                    float pivotX = isRightAligned ? exRoma.getWidth() : (isCentered ? exRoma.getWidth() * 0.5f : 0f);
                    canvas.save();
                    canvas.translate(originX, originY + topY + slideY);
                    canvas.scale(scale, scale, pivotX, 0f);
                    exRoma.draw(canvas);
                    canvas.restore();
                }
            }
        }

        if (transStart < 0 && exitingTransText != null && transReveal > 0f) {
            StaticLayout exTrans = ensureExitingTransLayout(widthPx);
            if (exTrans != null) {
                final int transAlpha = alpha255(translationAlpha() * contentNow * transReveal);
                if (transAlpha > 0) {
                    TextPaint tp = exTrans.getPaint();
                    tp.setShader(null);
                    tp.setColor(readingRgb | (transAlpha << 24));
                    int topY = exitingTransBeforeMain
                            ? (-exTrans.getHeight() - Dim.dp4)
                            : (layout.getHeight() + Dim.dp4);
                    float scale = REGION_CLOSED_SCALE + (1f - REGION_CLOSED_SCALE) * transScaleProgress;
                    float slideY = (exitingTransBeforeMain ? 1f : -1f)
                            * REGION_SLIDE_FACTOR * exTrans.getHeight() * (1f - transScaleProgress);
                    float pivotX = isRightAligned ? exTrans.getWidth() : (isCentered ? exTrans.getWidth() * 0.5f : 0f);
                    float pivotY = exitingTransBeforeMain ? exTrans.getHeight() : 0f;
                    canvas.save();
                    canvas.translate(originX, originY + topY + translationFlipOffsetY + slideY);
                    canvas.scale(scale, scale, pivotX, pivotY);
                    exTrans.draw(canvas);
                    canvas.restore();
                }
            }
        }
    }

    private static int rangeLine(Layout layout, int start, int end, boolean first) {
        if (start < 0 || end <= start || end > layout.getText().length()) {
            return -1;
        }
        return layout.getLineForOffset(first ? start : end - 1);
    }

    private boolean shouldUseSyllableCache() {
        if (wordTimings == null || wordTimings.isEmpty()) {
            return false;
        }
        if (showSyllableRomanization || rubyActive || exitingShowSyllableRomanization || exitingRubyActive) {
            return true;
        }
        return unsungColor != 0
                && sweepEnabled
                && !smallRunHoldsWords
                && Settings.LYRICS_WORD_SYNC.get()
                && Settings.isWordAnimSmooth();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int width = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        CharSequence text = getText();
        if (width > 0 && Utils.isNotEmpty(text)) {
            if (!showSyllableRomanization && !rubyActive && secondaryAnimator == null
                    && LyricsFit.applyOrphanFit(this, width, text, fittedState)) {
                // Not refitted while a background vocal opens, or its text jumps mid-animation.
                cachedLayout = null;
                cachedText = null;
                releaseSyllableCache();
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
            SyllableRomanCache syllables = shouldUseSyllableCache()
                    ? syllableCacheFor(width, text) : null;
            if (syllables != null) {
                // Drawn by hand, so the height is the hand-built layout's and not the text's.
                setMeasuredDimension(getMeasuredWidth(), resolveSize(getPaddingTop()
                        + syllables.totalHeightPx + getPaddingBottom(), heightMeasureSpec));
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
    @Override
    protected void onDraw(Canvas canvas) {
        if (deferSyllableBuild) {
            deferSyllableBuild = false;
        }
        if (shouldUseSyllableCache()) {
            final int width = getWidth() - getPaddingLeft() - getPaddingRight();
            CharSequence text = getText();
            if (width > 0 && Utils.isNotEmpty(text)) {
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
                    || !sweepEnabled
                    || (allSung && baseDrawn)
                    || (!activeLine && lineLookAnimator == null)) {
                return;
            }

            Layout layout = getLayout();
            if (layout == null) {
                return;
            }
            CharSequence text = getText();
            if (Utils.isEmpty(text)) {
                return;
            }
            Paint tp = getPaint();
            final int origStart = originalTextStart;

            ensureWordCache(layout, text, tp, wordTimings, origStart);

            final int lineCount = cachedLineCount;
            if (lineMaxSungX == null || lineMaxSungX.length != lineCount) {
                lineMaxSungX = new float[lineCount];
                lineKaraokeStartX = new float[lineCount];
                lineKaraokeEndX = new float[lineCount];
            }
            Arrays.fill(lineMaxSungX, 0, lineCount, Float.NaN);
            Arrays.fill(lineKaraokeStartX, 0, lineCount, Float.NaN);
            Arrays.fill(lineKaraokeEndX, 0, lineCount, Float.NaN);
            final boolean karaokeMode = Settings.isWordAnimKaraoke();
            final boolean characterMode = Settings.isWordAnimCharacter();
            int firstSungLine = -1;
            // For the fill below: a line with no words at all is not its case, and a plain line
            // paints its own words on the main run, so only the dual arrangement reaches it.
            final boolean hasWords = cachedWordCount > 0;
            boolean allWordsDone = hasWords;
            boolean wordOnMainRun = false;

            boolean[] secondaryLine = new boolean[lineCount];
            boolean[] romaLine = new boolean[lineCount];
            markSecondaryLines(layout, transStart, transEnd, secondaryLine);
            markSecondaryLines(layout, romaStart, romaEnd, secondaryLine);
            markSecondaryLines(layout, romaStart, romaEnd, romaLine);
            final int romaFirst = rangeLine(layout, romaStart, romaEnd, true);
            final int romaLast = rangeLine(layout, romaStart, romaEnd, false);
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
                WordTiming timing = wordTimings.get(i);
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
                if (allSung || currentPos >= timing.endMs()) {
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
                final float trail = cachedWordTrailX[i];
                // Character and karaoke both light whole words at once: no in-word sweep.
                final float effectiveProgress = (characterMode || karaokeMode)
                        ? (progress > 0f ? 1f : 0f)
                        : progress;
                // A word that runs on below shares its progress with the lines it reaches, so
                // its own line takes only the share that falls here. For a word that fits, this
                // is the progress itself.
                final int charsOnFirstLine = Math.min(e, layout.getLineEnd(lineNum)) - s;
                final float firstLineProgress = Math.min(1f,
                        Math.max(0f, effectiveProgress * (e - s) / (float) charsOnFirstLine));
                final float edge = lead + (trail - lead) * firstLineProgress;
                lineMaxSungX[lineNum] = extendSung(lineMaxSungX[lineNum], edge,
                        isRightToLeftLine(layout, lineNum));
                // Karaoke lights only the word being sung; earlier words fall back to the
                // unsung span underneath. The snapped edge above removes the in-word sweep.
                if (karaokeMode && progress > 0f && progress < 1f) {
                    final boolean lnRtl = isRightToLeftLine(layout, lineNum);
                    lineKaraokeStartX[lineNum] = lnRtl ? trail : lead;
                    lineKaraokeEndX[lineNum] = lnRtl ? lead : trail;
                }
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

            // Fallback for Romaji lines (such as Arabic transliteration) when no word timings landed
            // directly on romaFirst..romaLast: map the main line's sung progress across the Romaji tokens.
            if (romaFirst >= 0 && romaLast >= romaFirst && !allSung && activeLine && romaReveal > 0f) {
                boolean anyRomaEdge = false;
                for (int rln = romaFirst; rln <= romaLast && rln < lineCount; rln++) {
                    if (!Float.isNaN(lineMaxSungX[rln])) {
                        anyRomaEdge = true;
                        break;
                    }
                }
                if (!anyRomaEdge && cachedWordCount > 0) {
                    long minStart = Long.MAX_VALUE;
                    long maxEnd = Long.MIN_VALUE;
                    int mainWordCount = 0;
                    int sungMainWords = 0;
                    float partialMainProg = 0f;
                    for (int i = 0; i < cachedWordCount; i++) {
                        WordTiming wt = wordTimings.get(i);
                        int s = wt.start() + origStart;
                        if (s >= romaStart && s < romaEnd) continue;
                        if (wt.startMs() == LyricsLine.NO_TIME || wt.endMs() <= wt.startMs())
                            continue;
                        mainWordCount++;
                        if (wt.startMs() < minStart) minStart = wt.startMs();
                        if (wt.endMs() > maxEnd) maxEnd = wt.endMs();
                        if (currentPos >= wt.endMs()) {
                            sungMainWords++;
                        } else if (currentPos > wt.startMs()) {
                            partialMainProg = (float) (currentPos - wt.startMs()) / (float) (wt.endMs() - wt.startMs());
                        }
                    }
                    if (mainWordCount > 0 && currentPos > minStart) {
                        float totalFrac = Math.min(1f, (sungMainWords + partialMainProg) / (float) mainWordCount);
                        if (totalFrac > 0f) {
                            int totalRomaLines = romaLast - romaFirst + 1;
                            float scaledLine = totalFrac * totalRomaLines;
                            for (int rln = romaFirst; rln <= romaLast && rln < lineCount; rln++) {
                                int idxInRoma = rln - romaFirst;
                                float lnFrac = Math.max(0f, Math.min(1f, scaledLine - idxInRoma));
                                if (lnFrac > 0f) {
                                    float lLeft = cachedLineLeft[rln];
                                    float lRight = cachedLineRight[rln];
                                    boolean rRtl = isRightToLeftLine(layout, rln);
                                    lineMaxSungX[rln] = rRtl
                                            ? (lRight - (lRight - lLeft) * lnFrac)
                                            : (lLeft + (lRight - lLeft) * lnFrac);
                                    if (firstSungLine < 0) firstSungLine = rln;
                                }
                            }
                        }
                    }
                }
            }

            // The line is past its end but has no word timings left to measure it with, so its
            // reach comes from the bounds. The reading counts: it sits outside the lyric's own
            // end, or it stays dark against a lit lyric.
            if (allSung) {
                for (int ln = 0; ln < lineCount; ln++) {
                    if (secondaryLine[ln] && !romaLine[ln]) {
                        continue;
                    }
                    if (romaLine[ln] && romaReveal <= 0.001f) {
                        continue;
                    }
                    lineMaxSungX[ln] = isRightToLeftLine(layout, ln)
                            ? cachedLineLeft[ln] : cachedLineRight[ln];
                }
                firstSungLine = 0;
            } else if (!sweepEnabled && activeLine) {
                for (int ln = 0; ln < lineCount; ln++) {
                    if (secondaryLine[ln] && anyMainLine && !smallRunHoldsWords && !romaLine[ln]) {
                        continue;
                    }
                    if (romaLine[ln] && romaReveal <= 0.001f) {
                        continue;
                    }
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
            final boolean rtlRomaFullBrightness = perWordReading && !smallRunHoldsWords
                    && isMainRunRtl(layout);
            final int romaReadingColor = baseRgb | (alpha255(
                    (rtlRomaFullBrightness ? sungMainAlphaFactor : sungReadingAlphaFactor)
                            * romaReveal) << 24);
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
            Shader prevShader = tp.getShader();
            int appliedColor = -1;
            final boolean smoothSweep = Settings.isWordAnimSmooth();
            final float featherHalf = Math.max(Dim.dp(6), getTextSize() * 0.30f);

            canvas.save();
            if (contentReveal < 1f) {
                layerPaint.setColor(Color.WHITE);
                layerPaint.setAlpha(Math.round(255f * contentReveal));
                canvas.saveLayer(0f, 0f, getWidth(), getHeight(), layerPaint);
            }
            canvas.translate(textOriginX(), textOriginY());
            for (int ln = 0; ln < lineCount; ln++) {
                final float edge = lineMaxSungX[ln];
                final boolean isSweptRomaLine = romaLine[ln] && (perWordReading || !Float.isNaN(edge));
                if (secondaryLine[ln] && anyMainLine && !smallRunHoldsWords && !isSweptRomaLine) {
                    continue;
                }
                if (Float.isNaN(edge)) {
                    continue;
                }
                // With no word being sung there is nothing to light; the base text already
                // carries the unsung colour for everything already played.
                if (karaokeMode && Float.isNaN(lineKaraokeStartX[ln])) {
                    continue;
                }
                if (romaLine[ln] && romaReveal <= 0.001f) {
                    continue;
                }
                // A small run takes the reading's colour so it reads as a line of its own, and a
                // wrapped one would otherwise come out in two colours mid-word. What makes a run small
                // is the main run beside it, not whether it holds the words: a dual line hands those to
                // the reading wherever the swap puts it, so a main run without them still reads as the
                // lyric, as does a line with nothing beside it.
                final int color = secondaryLine[ln] && anyMainLine
                        ? (romaLine[ln] ? romaReadingColor : readingColor)
                        : lyricColor;
                final boolean isLnRtl = isRightToLeftLine(layout, ln);
                final float lineL = cachedLineLeft[ln];
                final float lineR = cachedLineRight[ln];
                final boolean useFeather = smoothSweep && !allSung
                        && (isLnRtl ? (edge > lineL + 0.5f && edge < lineR) : (edge > lineL && edge < lineR - 0.5f));
                if (useFeather) {
                    tp.setColorFilter(null);
                    appliedColor = -1;
                    final float gStart = isLnRtl ? Math.min(lineR, edge + featherHalf) : Math.max(lineL, edge - featherHalf);
                    final float gEnd = isLnRtl ? Math.max(lineL, edge - featherHalf) : Math.min(lineR, edge + featherHalf);
                    if (Math.abs(gEnd - gStart) > 0.5f) {
                        tp.setShader(new LinearGradient(
                                gStart, 0f, gEnd, 0f,
                                new int[]{color, color, color & 0x00FFFFFF, color & 0x00FFFFFF},
                                new float[]{0f, 0.1f, 0.9f, 1f},
                                Shader.TileMode.CLAMP));
                    } else {
                        tp.setShader(null);
                        tp.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                    }
                } else {
                    tp.setShader(null);
                    if (color != appliedColor) {
                        tp.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                        appliedColor = color;
                    }
                }
                canvas.save();
                if (romaLine[ln] && romaFirst >= 0 && romaLast >= romaFirst
                        && Math.abs(romaScaleProgress - 1f) > 0.002f) {
                    int rTop = layout.getLineTop(romaFirst);
                    int rBot = layout.getLineBottom(romaLast);
                    int rH = Math.max(1, rBot - rTop);
                    boolean rAbove = romaFirst == 0 && originalTextStart > 0;
                    float scale = REGION_CLOSED_SCALE + (1f - REGION_CLOSED_SCALE) * romaScaleProgress;
                    float slideY = (rAbove ? 1f : -1f) * REGION_SLIDE_FACTOR * rH * (1f - romaScaleProgress);
                    float pivotX = isRightAligned ? layout.getWidth() : (isCentered() ? layout.getWidth() * 0.5f : 0f);
                    float pivotY = rAbove ? rBot : rTop;
                    canvas.translate(0f, slideY);
                    canvas.scale(scale, scale, pivotX, pivotY);
                }
                final int clipTop = ln == 0 ? -textOriginY() : cachedLineTop[ln];
                final boolean karaokeSpan = karaokeMode
                        && !Float.isNaN(lineKaraokeStartX[ln])
                        && !Float.isNaN(lineKaraokeEndX[ln]);
                final float litLeft = karaokeSpan ? lineKaraokeStartX[ln] : lineL;
                final float litRight = karaokeSpan ? lineKaraokeEndX[ln] : lineR;
                if (isLnRtl) {
                    final float clipLeft = Math.min(useFeather ? Math.max(litLeft, edge - featherHalf) : edge, litRight);
                    canvas.clipRect(clipLeft, clipTop, litRight, cachedLineBottom[ln]);
                } else {
                    final float clipRight = Math.max(useFeather ? Math.min(litRight, edge + featherHalf) : edge, litLeft);
                    canvas.clipRect(litLeft, clipTop, clipRight, cachedLineBottom[ln]);
                }
                layout.draw(canvas);
                canvas.restore();
            }
            canvas.restore();
            if (contentReveal < 1f) {
                canvas.restore();
            }
            tp.setShader(prevShader);
            tp.setColorFilter(prevFilter);
            if (firstSungLine >= 0 && !allSung && activeLine && LyricsManager.getInstance().isPlaying()) {
                postInvalidateOnAnimation();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "LyricsLineView onDraw failure", ex);
        }
    }

    // Word animation & syllable romanization cache backed by WordSweepAnimator and WordAnimationUtils.

    private static final class SyllableRomanCache {
        final int widthPx;
        final float fontSizePx;
        final boolean active;
        final boolean hasSyllableRomanization;
        final boolean hasRuby;
        @Nullable
        final WordSweepAnimator animator;
        @Nullable
        final String mainText;
        final List<AlignedSyllable> alignedSyllables;
        final int extraRomanHeightPx;
        final int extraRubyHeightPx;
        @Nullable
        float[] sylShiftedLeadX;
        @Nullable
        float[] sylShiftedTrailX;
        @Nullable
        final String romaText;
        @Nullable
        final StaticLayout romaLayout;
        final int romaTopY;
        @Nullable
        final String transText;
        @Nullable
        final StaticLayout transLayout;
        final int transTopY;
        final int mainTopY;
        final int totalHeightPx;
        final int mainLayoutHeight;
        @Nullable
        final String rawMainTextKey;
        @Nullable
        final String animatorRomArgKey;
        final boolean showSyllableRomanizationKey;
        final boolean rubyActiveKey;
        final boolean exitSylRomaKey;
        final boolean exitRubyKey;
        final boolean isSecondaryKey;
        final boolean isRightAlignedKey;
        final boolean isCenteredKey;
        @Nullable
        final Long adjEndKey;
        final int accentColorKey;
        final float textSizeSpKey;
        final List<WordTimestamp> rawWordsKey;
        @Nullable
        final List<WordTimestamp> rawRomWordsKey;
        final boolean pendingDeferredAnimator;
        @Nullable
        final Layout deferredMainLayout;

        SyllableRomanCache(
                int widthPx,
                float fontSizePx,
                boolean active,
                boolean hasSyllableRomanization,
                boolean hasRuby,
                @Nullable WordSweepAnimator animator,
                @Nullable String mainText,
                List<AlignedSyllable> alignedSyllables,
                int extraRomanHeightPx,
                int extraRubyHeightPx,
                @Nullable String romaText,
                @Nullable StaticLayout romaLayout,
                int romaTopY,
                @Nullable String transText,
                @Nullable StaticLayout transLayout,
                int transTopY,
                int mainTopY,
                int totalHeightPx,
                int mainLayoutHeight,
                @Nullable String rawMainTextKey,
                @Nullable String animatorRomArgKey,
                boolean showSyllableRomanizationKey,
                boolean rubyActiveKey,
                boolean exitSylRomaKey,
                boolean exitRubyKey,
                boolean isSecondaryKey,
                boolean isRightAlignedKey,
                boolean isCenteredKey,
                @Nullable Long adjEndKey,
                int accentColorKey,
                float textSizeSpKey,
                List<WordTimestamp> rawWordsKey,
                @Nullable List<WordTimestamp> rawRomWordsKey) {
            this(widthPx, fontSizePx, active, hasSyllableRomanization, hasRuby, animator, mainText,
                    alignedSyllables, extraRomanHeightPx, extraRubyHeightPx, romaText, romaLayout,
                    romaTopY, transText, transLayout, transTopY, mainTopY, totalHeightPx,
                    mainLayoutHeight, rawMainTextKey, animatorRomArgKey,
                    showSyllableRomanizationKey, rubyActiveKey, exitSylRomaKey, exitRubyKey,
                    isSecondaryKey, isRightAlignedKey, isCenteredKey, adjEndKey, accentColorKey,
                    textSizeSpKey, rawWordsKey, rawRomWordsKey, false, null);
        }

        SyllableRomanCache(
                int widthPx,
                float fontSizePx,
                boolean active,
                boolean hasSyllableRomanization,
                boolean hasRuby,
                @Nullable WordSweepAnimator animator,
                @Nullable String mainText,
                List<AlignedSyllable> alignedSyllables,
                int extraRomanHeightPx,
                int extraRubyHeightPx,
                @Nullable String romaText,
                @Nullable StaticLayout romaLayout,
                int romaTopY,
                @Nullable String transText,
                @Nullable StaticLayout transLayout,
                int transTopY,
                int mainTopY,
                int totalHeightPx,
                int mainLayoutHeight,
                @Nullable String rawMainTextKey,
                @Nullable String animatorRomArgKey,
                boolean showSyllableRomanizationKey,
                boolean rubyActiveKey,
                boolean exitSylRomaKey,
                boolean exitRubyKey,
                boolean isSecondaryKey,
                boolean isRightAlignedKey,
                boolean isCenteredKey,
                @Nullable Long adjEndKey,
                int accentColorKey,
                float textSizeSpKey,
                List<WordTimestamp> rawWordsKey,
                @Nullable List<WordTimestamp> rawRomWordsKey,
                boolean pendingDeferredAnimator,
                @Nullable Layout deferredMainLayout) {
            this.widthPx = widthPx;
            this.fontSizePx = fontSizePx;
            this.active = active;
            this.hasSyllableRomanization = hasSyllableRomanization;
            this.hasRuby = hasRuby;
            this.animator = animator;
            this.mainText = mainText;
            this.alignedSyllables = alignedSyllables;
            this.extraRomanHeightPx = extraRomanHeightPx;
            this.extraRubyHeightPx = extraRubyHeightPx;
            this.romaText = romaText;
            this.romaLayout = romaLayout;
            this.romaTopY = romaTopY;
            this.transText = transText;
            this.transLayout = transLayout;
            this.transTopY = transTopY;
            this.mainTopY = mainTopY;
            this.totalHeightPx = totalHeightPx;
            this.mainLayoutHeight = mainLayoutHeight;
            this.rawMainTextKey = rawMainTextKey;
            this.animatorRomArgKey = animatorRomArgKey;
            this.showSyllableRomanizationKey = showSyllableRomanizationKey;
            this.rubyActiveKey = rubyActiveKey;
            this.exitSylRomaKey = exitSylRomaKey;
            this.exitRubyKey = exitRubyKey;
            this.isSecondaryKey = isSecondaryKey;
            this.isRightAlignedKey = isRightAlignedKey;
            this.isCenteredKey = isCenteredKey;
            this.adjEndKey = adjEndKey;
            this.accentColorKey = accentColorKey;
            this.textSizeSpKey = textSizeSpKey;
            this.rawWordsKey = rawWordsKey;
            this.rawRomWordsKey = rawRomWordsKey;
            this.pendingDeferredAnimator = pendingDeferredAnimator;
            this.deferredMainLayout = deferredMainLayout;
        }

        private boolean matchesInvariantKeys(
                int widthPx,
                float fontSizePx,
                float textSizeSp,
                String rawMainText,
                @Nullable String animatorRomArg,
                boolean isSecondary,
                boolean isRightAligned,
                boolean isCentered,
                @Nullable Long adjEnd,
                int accentColor,
                List<WordTimestamp> rawWords,
                @Nullable List<WordTimestamp> rawRomWords) {
            return animator != null
                    && this.widthPx == widthPx
                    && Math.abs(this.fontSizePx - fontSizePx) < 0.5f
                    && Math.abs(this.textSizeSpKey - textSizeSp) < 0.01f
                    && this.isSecondaryKey == isSecondary
                    && this.isRightAlignedKey == isRightAligned
                    && this.isCenteredKey == isCentered
                    && this.accentColorKey == accentColor
                    && Objects.equals(this.adjEndKey, adjEnd)
                    && Objects.equals(this.rawMainTextKey, rawMainText)
                    && Objects.equals(this.animatorRomArgKey, animatorRomArg)
                    && wordListsEqual(this.rawWordsKey, rawWords)
                    && wordListsEqual(this.rawRomWordsKey, rawRomWords);
        }

        boolean canReuseAnimator(
                int widthPx,
                float fontSizePx,
                float textSizeSp,
                String rawMainText,
                @Nullable String animatorRomArg,
                boolean showSyllableRomanization,
                boolean rubyActive,
                boolean exitSylRoma,
                boolean exitRuby,
                boolean isSecondary,
                boolean isRightAligned,
                boolean isCentered,
                @Nullable Long adjEnd,
                int accentColor,
                List<WordTimestamp> rawWords,
                @Nullable List<WordTimestamp> rawRomWords) {
            return this.showSyllableRomanizationKey == showSyllableRomanization
                    && this.rubyActiveKey == rubyActive
                    && this.exitSylRomaKey == exitSylRoma
                    && this.exitRubyKey == exitRuby
                    && matchesInvariantKeys(
                    widthPx, fontSizePx, textSizeSp, rawMainText, animatorRomArg,
                    isSecondary, isRightAligned, isCentered, adjEnd, accentColor,
                    rawWords, rawRomWords);
        }

        boolean canTransitionInPlace(
                int widthPx,
                float fontSizePx,
                float textSizeSp,
                String rawMainText,
                @Nullable String animatorRomArg,
                boolean showSyllableRomanization,
                boolean rubyActive,
                boolean exitSylRoma,
                boolean exitRuby,
                boolean isSecondary,
                boolean isRightAligned,
                boolean isCentered,
                @Nullable Long adjEnd,
                int accentColor,
                List<WordTimestamp> rawWords,
                @Nullable List<WordTimestamp> rawRomWords) {
            return animator != null
                    && (this.showSyllableRomanizationKey || this.exitSylRomaKey) == (showSyllableRomanization || exitSylRoma)
                    && (this.rubyActiveKey || this.exitRubyKey) == (rubyActive || exitRuby)
                    && matchesInvariantKeys(
                    widthPx, fontSizePx, textSizeSp, rawMainText, animatorRomArg,
                    isSecondary, isRightAligned, isCentered, adjEnd, accentColor,
                    rawWords, rawRomWords)
                    && animator.canTransitionInPlace(
                    showSyllableRomanization, rubyActive);
        }

        private static boolean wordListsEqual(@Nullable List<WordTimestamp> a, @Nullable List<WordTimestamp> b) {
            if (a == b) return true;
            if (a == null || b == null || a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                WordTimestamp wa = a.get(i);
                WordTimestamp wb = b.get(i);
                if (Double.compare(wa.startTime(), wb.startTime()) != 0
                        || Double.compare(wa.endTime(), wb.endTime()) != 0
                        || wa.hasTrailingSpace() != wb.hasTrailingSpace()
                        || wa.isBackground() != wb.isBackground()
                        || !Objects.equals(wa.text(), wb.text())
                        || !Objects.equals(wa.romanized(), wb.romanized())
                        || !Objects.equals(wa.ruby(), wb.ruby())) {
                    return false;
                }
            }
            return true;
        }

        void ensureSyllableHitBounds() {
            if (sylShiftedLeadX != null && sylShiftedTrailX != null) {
                return;
            }
            if (animator == null) {
                sylShiftedLeadX = new float[0];
                sylShiftedTrailX = new float[0];
                return;
            }
            animator.ensureRenderState();
            final int sylCount = alignedSyllables.size();
            float[] leads = new float[sylCount];
            float[] trails = new float[sylCount];
            Arrays.fill(leads, Float.NaN);
            Arrays.fill(trails, Float.NaN);

            List<VisualCluster> clusters = animator.clusters;
            for (int idx = 0; idx < clusters.size(); idx++) {
                VisualCluster vc = clusters.get(idx);
                final int sIdx = vc.syllableIndex();
                if (sIdx >= 0 && sIdx < sylCount) {
                    final float leftX = animator.statics.baseX()[idx];
                    final float rightX = leftX + vc.width();
                    if (Float.isNaN(leads[sIdx]) || leftX < leads[sIdx]) {
                        leads[sIdx] = leftX;
                    }
                    if (Float.isNaN(trails[sIdx]) || rightX > trails[sIdx]) {
                        trails[sIdx] = rightX;
                    }
                }
            }
            for (int sIdx = 0; sIdx < sylCount; sIdx++) {
                if (Float.isNaN(leads[sIdx])) {
                    AlignedSyllable syl = alignedSyllables.get(sIdx);
                    final float shift = WordAnimationUtils.calculateGapShift(sIdx, animator.romanStartShift);
                    final Layout mainLayout = animator.textLayoutResult;
                    final float sx = (mainLayout != null)
                            ? mainLayout.getPrimaryHorizontal(
                            Math.max(0, Math.min(syl.startOffset(), animator.formattedMainText.length()))) + shift
                            : shift;
                    final float ex = (mainLayout != null)
                            ? mainLayout.getPrimaryHorizontal(
                            Math.max(0, Math.min(syl.endOffset(), animator.formattedMainText.length()))) + shift
                            : shift;
                    leads[sIdx] = Math.min(sx, ex);
                    trails[sIdx] = Math.max(sx + 1f, ex);
                }
            }
            sylShiftedLeadX = leads;
            sylShiftedTrailX = trails;
        }

        static SyllableRomanCache none(int widthPx, float fontSizePx) {
            return new SyllableRomanCache(
                    widthPx, fontSizePx, false, false, false, null, null,
                    Collections.emptyList(), 0, 0,
                    null, null, 0, null, null, 0, 0, 0, 0,
                    null, null, false, false, false, false, false, false, false,
                    null, 0, 0f, Collections.emptyList(), null);
        }
    }

    @Nullable
    private SyllableRomanCache syllableCacheFor(int widthPx, CharSequence fullText) {
        final float fontSizePx = getTextSize();
        if (syllableCache == null || syllableCache.widthPx != widthPx
                || Math.abs(syllableCache.fontSizePx - fontSizePx) >= 0.5f
                || (syllableCache.pendingDeferredAnimator && !deferSyllableBuild)) {
            if (syllableCache != null) {
                if (syllableCache.pendingDeferredAnimator) {
                    stashCurrentSyllableCache();
                } else {
                    releaseSyllableCache();
                }
            }
            syllableCache = buildSyllableCache(widthPx, fontSizePx, fullText.toString());
        }
        return syllableCache.active ? syllableCache : null;
    }

    private static void discardCache(@Nullable SyllableRomanCache cache) {
        if (cache != null && cache.animator != null) {
            cache.animator.release();
        }
    }

    private TextPaint createMainSweepPaint(float fontSizePx, float textSizeSp, String rawMainText) {
        TextPaint mainPaint = new TextPaint(getPaint());
        mainPaint.setTextSize(fontSizePx);
        LyricsViewSupport.applyLyricsBoldPaint(mainPaint);
        mainPaint.setLetterSpacing(LyricsViewSupport.lineLetterSpacing(rawMainText, textSizeSp));
        WordAnimationUtils.applyClusterFontFeatures(mainPaint, LyricsTextUtils.containsJoiningScript(rawMainText));
        return mainPaint;
    }

    @Nullable
    private StaticLayout resolveSecondaryTextLayout(
            @Nullable String text,
            boolean isTrans,
            int widthPx,
            float fontSizePx,
            boolean isRight,
            boolean isCentered,
            boolean compatibleSubLayout,
            @Nullable SyllableRomanCache reusable) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (reusable != null && compatibleSubLayout) {
            StaticLayout prev = isTrans ? reusable.transLayout : reusable.romaLayout;
            String prevText = isTrans ? reusable.transText : reusable.romaText;
            if (prev != null && Objects.equals(text, prevText)) {
                return prev;
            }
        }
        TextPaint subPaint = new TextPaint(getPaint());
        subPaint.setTextSize(fontSizePx * LyricsSpanBuilder.TRANSLATION_RELATIVE_SIZE);
        LyricsViewSupport.applyLyricsBoldPaint(subPaint);
        subPaint.setLetterSpacing(0f);
        boolean isRtl = isTrans && LyricsSpanBuilder.isRtlText(text);
        Layout.Alignment align = LyricsSpanBuilder.resolveAlignment(isRtl, isRight, isCentered);
        return StaticLayout.Builder.obtain(text, 0, text.length(), subPaint, widthPx)
                .setAlignment(align)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.18f)
                .build();
    }

    private SyllableRomanCache buildSyllableCache(int widthPx, float fontSizePx, String fullStr) {
        final SyllableRomanCache reusable = pendingReusableCache;
        pendingReusableCache = null;

        final int origStart = Math.max(0, Math.min(originalTextStart, fullStr.length()));
        final int newline = fullStr.indexOf('\n', origStart);
        final int origEnd = newline >= 0 ? newline : fullStr.length();
        String rawMainText = fullStr.substring(origStart, origEnd);
        if (rawMainText.trim().isEmpty()
                || ScriptDetector.containsRtl(rawMainText)
                || (transStart == 0 && transEnd == fullStr.length())) {
            discardCache(reusable);
            return SyllableRomanCache.none(widthPx, fontSizePx);
        }

        String romLineText = null;
        if (romaStart >= 0 && romaEnd > romaStart && romaStart < fullStr.length()) {
            String r = fullStr.substring(romaStart, Math.min(romaEnd, fullStr.length())).trim();
            if (!r.isEmpty()) {
                romLineText = r;
            }
        }

        final boolean exitSylRoma = !showSyllableRomanization && exitingShowSyllableRomanization && romaReveal > 0f;
        final boolean exitRuby = !rubyActive && exitingRubyActive && romaReveal > 0f;
        final boolean hasAnimatedWordSweep = unsungColor != 0
                && sweepEnabled
                && !smallRunHoldsWords
                && Settings.LYRICS_WORD_SYNC.get();
        if (!hasAnimatedWordSweep && !showSyllableRomanization && !rubyActive && !exitSylRoma && !exitRuby) {
            discardCache(reusable);
            return SyllableRomanCache.none(widthPx, fontSizePx);
        }

        final boolean isRight = this.isRightAligned;
        final float textSizeSp = Settings.LYRICS_TEXT_SIZE.get();
        final Layout.Alignment mainAlign = LyricsSpanBuilder.resolveAlignment(false, isRight, isCentered);
        final boolean compatibleSubLayout = reusable != null
                && reusable.widthPx == widthPx
                && Math.abs(reusable.fontSizePx - fontSizePx) < 0.5f
                && reusable.isRightAlignedKey == isRight
                && reusable.isCenteredKey == isCentered;

        String transText = null;
        if (transStart >= 0 && transEnd > transStart && transStart < fullStr.length()) {
            String t = fullStr.substring(transStart, Math.min(transEnd, fullStr.length())).trim();
            if (!t.isEmpty()) {
                transText = t;
            }
        }

        // Off-screen non-romanized rows only need exact height on frame 1; defer animator
        // construction until draw so toggling romanization off does not build off-screen animators.
        if (deferSyllableBuild && hasAnimatedWordSweep
                && !showSyllableRomanization && !rubyActive && !exitSylRoma && !exitRuby) {
            Layout deferredMain = null;
            if (compatibleSubLayout
                    && Math.abs(reusable.textSizeSpKey - textSizeSp) < 0.01f
                    && Objects.equals(rawMainText, reusable.rawMainTextKey)) {
                if (reusable.deferredMainLayout != null) {
                    deferredMain = reusable.deferredMainLayout;
                } else if (reusable.animator != null
                        && reusable.animator.formattedMainText.equals(rawMainText)) {
                    deferredMain = reusable.animator.textLayoutResult;
                }
            }
            if (deferredMain == null) {
                TextPaint mainPaint = createMainSweepPaint(fontSizePx, textSizeSp, rawMainText);
                boolean isMainRtl = LyricsSpanBuilder.isRtlText(rawMainText);
                deferredMain = WordAnimationUtils.createStaticLayout(
                        rawMainText, mainPaint, widthPx, mainAlign, isMainRtl);
            }
            StaticLayout romaLayout = resolveSecondaryTextLayout(
                    romLineText, false, widthPx, fontSizePx, isRight, isCentered, compatibleSubLayout, reusable);
            StaticLayout transLayout = resolveSecondaryTextLayout(
                    transText, true, widthPx, fontSizePx, isRight, isCentered, compatibleSubLayout, reusable);
            discardCache(reusable);
            return assembleSyllableCache(
                    widthPx, fontSizePx, textSizeSp, origStart, rawMainText, null,
                    false, false, false, false, isRight, isCentered, null, 0,
                    Collections.emptyList(), null, null, Collections.emptyList(),
                    deferredMain.getHeight(), 0, 0,
                    romLineText, romaLayout, transText, transLayout, true, deferredMain);
        }

        final String animatorRomArg = (showSyllableRomanization || exitSylRoma)
                ? (romLineText != null ? romLineText : exitingRomaText) : null;
        final int accentColor = LyricsColors.lineTextColor() | 0xFF000000;
        Long adjEnd = adjustedEndTimeMs;
        if (adjEnd == null) {
            long lastEnd = getLastWordEndMs();
            if (lastEnd != LyricsLine.NO_TIME) {
                adjEnd = lastEnd;
            }
        }

        List<WordTiming> mainSourceTimings = ((!showSyllableRomanization && exitSylRoma) || (!rubyActive && exitRuby))
                && !exitingRomaTimings.isEmpty()
                ? exitingRomaTimings : wordTimings;
        List<WordTimestamp> rawWords = WordTimestamp.fromSpanTimings(rawMainText, mainSourceTimings, isSecondary);
        if (rawWords.isEmpty()) {
            discardCache(reusable);
            return SyllableRomanCache.none(widthPx, fontSizePx);
        }

        if (!hasAnimatedWordSweep && !showSyllableRomanization && !exitSylRoma && !exitRuby) {
            boolean anyRawRuby = false;
            for (int i = 0; i < rawWords.size(); i++) {
                String r = rawWords.get(i).ruby();
                if (r != null && !r.trim().isEmpty()) {
                    anyRawRuby = true;
                    break;
                }
            }
            if (!anyRawRuby) {
                discardCache(reusable);
                return SyllableRomanCache.none(widthPx, fontSizePx);
            }
        }

        List<WordTiming> romSourceTimings = showSyllableRomanization
                ? wordTimings
                : (exitSylRoma && !exitingRomaTimings.isEmpty() ? exitingRomaTimings : wordTimings);
        List<WordTimestamp> rawRomWords = (showSyllableRomanization || exitSylRoma)
                ? WordTimestamp.buildRomanizedWordsFromSpanTimings(rawMainText, romSourceTimings, isSecondary)
                : null;
        if (exitRuby && !exitingRomaTimings.isEmpty() && mainSourceTimings != exitingRomaTimings) {
            List<WordTimestamp> rubySourceWords = WordTimestamp.fromSpanTimings(rawMainText, exitingRomaTimings, isSecondary);
            if (rubySourceWords.size() == rawWords.size()) {
                rawWords = rubySourceWords;
            }
        }

        WordSweepAnimator animator;
        final boolean reusedAnimator = reusable != null && reusable.canReuseAnimator(
                widthPx, fontSizePx, textSizeSp, rawMainText, animatorRomArg,
                showSyllableRomanization, rubyActive, exitSylRoma, exitRuby,
                isSecondary, isRight, isCentered, adjEnd, accentColor, rawWords, rawRomWords);
        if (reusedAnimator && reusable.animator != null) {
            animator = reusable.animator;
            animator.setPrewarmMasks(prewarmMasks);
        } else if (reusable != null && reusable.animator != null && reusable.canTransitionInPlace(
                widthPx, fontSizePx, textSizeSp, rawMainText, animatorRomArg,
                showSyllableRomanization, rubyActive, exitSylRoma, exitRuby,
                isSecondary, isRight, isCentered, adjEnd, accentColor, rawWords, rawRomWords)) {
            animator = reusable.animator;
            animator.updateTransitionModes(showSyllableRomanization, rubyActive, exitSylRoma, exitRuby);
            animator.setPrewarmMasks(prewarmMasks);
        } else {
            WordSweepAnimator.ReuseHint reuseHint = (reusable != null && reusable.animator != null)
                    ? reusable.animator.extractReuseHint() : null;
            if (reuseHint == null && compatibleSubLayout && reusable != null
                    && reusable.deferredMainLayout != null
                    && Math.abs(reusable.textSizeSpKey - textSizeSp) < 0.01f
                    && Objects.equals(rawMainText, reusable.rawMainTextKey)) {
                reuseHint = new WordSweepAnimator.ReuseHint(
                        widthPx, fontSizePx, accentColor,
                        LyricsTextUtils.containsJoiningScript(rawMainText), mainAlign,
                        reusable.deferredMainLayout, null);
            }
            discardCache(reusable);
            TextPaint mainPaint = createMainSweepPaint(fontSizePx, textSizeSp, rawMainText);

            animator = new WordSweepAnimator(
                    rawMainText,
                    rawWords,
                    rawRomWords,
                    animatorRomArg,
                    showSyllableRomanization,
                    rubyActive,
                    exitSylRoma,
                    exitRuby,
                    isSecondary,
                    adjEnd,
                    mainPaint,
                    mainAlign,
                    widthPx,
                    accentColor,
                    prewarmMasks,
                    reuseHint);
        }
        animator.setTransReveal(transReveal);
        animator.setRomaReveal(romaReveal);
        animator.setRomaScaleProgress(romaScaleProgress);

        if (!hasAnimatedWordSweep && !animator.hasSyllableRomanization && !animator.hasRuby
                && !exitSylRoma && !exitRuby) {
            animator.release();
            return SyllableRomanCache.none(widthPx, fontSizePx);
        }

        StaticLayout romaLayout = !animator.hasSyllableRomanization
                ? resolveSecondaryTextLayout(romLineText, false, widthPx, fontSizePx, isRight, isCentered, compatibleSubLayout, reusable)
                : null;
        StaticLayout transLayout = resolveSecondaryTextLayout(
                transText, true, widthPx, fontSizePx, isRight, isCentered, compatibleSubLayout, reusable);

        return assembleSyllableCache(
                widthPx, fontSizePx, textSizeSp, origStart, rawMainText, animatorRomArg,
                showSyllableRomanization, rubyActive, exitSylRoma, exitRuby,
                isRight, isCentered, adjEnd, accentColor, rawWords, rawRomWords,
                animator, animator.alignedSyllables,
                animator.measuredHeight, animator.extraRomanHeightPx, animator.extraRubyHeightPx,
                romLineText, romaLayout, transText, transLayout, false, null);
    }

    private SyllableRomanCache assembleSyllableCache(
            int widthPx,
            float fontSizePx,
            float textSizeSp,
            int origStart,
            String rawMainText,
            @Nullable String animatorRomArg,
            boolean showSylRomaKey,
            boolean rubyActiveKey,
            boolean exitSylRomaKey,
            boolean exitRubyKey,
            boolean isRight,
            boolean isCentered,
            @Nullable Long adjEnd,
            int accentColor,
            List<WordTimestamp> rawWords,
            @Nullable List<WordTimestamp> rawRomWords,
            @Nullable WordSweepAnimator animator,
            List<AlignedSyllable> alignedSyllables,
            int mainSectionH,
            int extraRomanHeightPx,
            int extraRubyHeightPx,
            @Nullable String romLineText,
            @Nullable StaticLayout romaLayout,
            @Nullable String transText,
            @Nullable StaticLayout transLayout,
            boolean pendingDeferred,
            @Nullable Layout deferredMainLayout) {
        final boolean hasSylRoma = animator != null && animator.hasSyllableRomanization;
        final boolean hasRubyVal = animator != null && animator.hasRuby;
        final String mainDisplayText = animator != null ? animator.formattedMainText : rawMainText;
        final int mainTextLayoutH = (animator != null && animator.textLayoutResult != null)
                ? animator.textLayoutResult.getHeight() : mainSectionH;
        int curY = 0;
        int transTopY = 0;
        int romaTopY = 0;
        final boolean transBeforeMain = transLayout != null && transStart >= 0 && transStart < origStart;
        if (transBeforeMain) {
            curY += transLayout.getHeight() + Dim.dp4;
        }
        final int mainTopY = curY;
        curY += mainSectionH;
        if (romaLayout != null) {
            curY += Dim.dp(2f);
            romaTopY = curY;
            curY += romaLayout.getHeight();
        }
        if (transLayout != null && !transBeforeMain) {
            final int transGap = (romaLayout == null && !hasSylRoma) ? Dim.dp(8f) : Dim.dp4;
            curY += transGap;
            transTopY = curY;
            curY += transLayout.getHeight();
        }
        final int totalH = curY;

        return new SyllableRomanCache(
                widthPx,
                fontSizePx,
                true,
                hasSylRoma,
                hasRubyVal,
                animator,
                mainDisplayText,
                alignedSyllables,
                extraRomanHeightPx,
                extraRubyHeightPx,
                romLineText,
                romaLayout,
                romaTopY,
                transText,
                transLayout,
                transTopY,
                mainTopY,
                totalH,
                mainTextLayoutH,
                rawMainText,
                animatorRomArg,
                showSylRomaKey,
                rubyActiveKey,
                exitSylRomaKey,
                exitRubyKey,
                isSecondary,
                isRight,
                isCentered,
                adjEnd,
                accentColor,
                textSizeSp,
                rawWords,
                rawRomWords,
                pendingDeferred,
                deferredMainLayout);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255f * alpha)));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Draws and animates a line via {@link WordSweepAnimator}, plus any fallback romanization and translation. */
    private void drawSyllableRomanizedLine(Canvas canvas, SyllableRomanCache cache) {
        if (contentReveal <= 0f || cache.animator == null) {
            return;
        }
        final float contentNow = contentReveal;
        final float originX = textOriginX();
        final float originY = textOriginY();
        final float mainY = originY + cache.mainTopY;

        final boolean hasAnimatedWordSweep = unsungColor != 0
                && !wordTimings.isEmpty()
                && sweepEnabled
                && !smallRunHoldsWords
                && Settings.LYRICS_WORD_SYNC.get();
        final boolean hasWordSweep = hasWordSweep();

        final int baseMainRgb = (LyricsColors.lineTextColor() | 0xFF000000);
        final int baseSung = withAlpha(baseMainRgb, contentNow);
        final float activeMainAlpha = lineLookInitialized ? lookCurAlpha : lineAlpha;
        final int dynamicLineColor = withAlpha(baseMainRgb, activeMainAlpha * contentNow);
        final float unsungWordAlpha = lineLookInitialized
                ? lookCurUnsungAlpha
                : (LyricsColors.UNSUNG_ALPHA * lineAlpha);
        final float dynamicFocusedAlpha = unsungWordAlpha * contentNow;

        cache.animator.setRomaReveal(romaReveal);
        cache.animator.setRomaScaleProgress(romaScaleProgress);
        long livePos = hasAnimatedWordSweep ? getLiveClockPositionMs() : 0L;
        float speed = LyricsManager.getInstance().getPlaybackSpeed();
        boolean playing = LyricsManager.getInstance().isPlaying();

        final boolean effectiveActive = hasAnimatedWordSweep && activeLine;

        canvas.save();
        canvas.translate(originX, mainY);
        cache.animator.draw(
                canvas,
                livePos,
                effectiveActive,
                allSung,
                baseSung,
                dynamicLineColor,
                dynamicFocusedAlpha,
                speed,
                playing);
        canvas.restore();

        final int readingRgb = (activeLine
                ? baseMainRgb
                : (LyricsColors.secondaryTextColor() | 0xFF000000)) & 0x00FFFFFF;

        StaticLayout romaToDraw = cache.romaLayout != null
                ? cache.romaLayout
                : (!cache.hasSyllableRomanization && !exitingShowSyllableRomanization && romaReveal > 0f
                   ? ensureExitingRomaLayout(cache.widthPx) : null);
        if (romaToDraw != null && romaReveal > 0f) {
            final float sungReadAlpha = lineLookInitialized
                    ? lookCurReadingAlpha
                    : (lineAlpha * LyricsColors.ACTIVE_READING_ALPHA);
            final boolean sweepRomaLayout = cache.romaLayout != null
                    && perWordReading
                    && hasAnimatedWordSweep
                    && !allSung
                    && romaStart >= 0;
            final float baseReadAlpha = sweepRomaLayout
                    ? unsungReadingAlpha(true)
                    : ((activeLine || allSung) ? sungReadAlpha : unsungReadingAlpha(hasWordSweep));
            final int romaBaseAlpha = alpha255(baseReadAlpha * contentNow * romaReveal);
            final int rTopY = cache.romaLayout != null
                    ? cache.romaTopY
                    : (cache.mainTopY + cache.animator.measuredHeight + Dim.dp(2f));
            float scale = REGION_CLOSED_SCALE + (1f - REGION_CLOSED_SCALE) * romaScaleProgress;
            float slideY = -REGION_SLIDE_FACTOR * romaToDraw.getHeight() * (1f - romaScaleProgress);
            float pivotX = isRightAligned ? romaToDraw.getWidth() : (isCentered ? romaToDraw.getWidth() * 0.5f : 0f);

            if (romaBaseAlpha > 0) {
                final int romaColor = readingRgb | (romaBaseAlpha << 24);
                TextPaint rp = romaToDraw.getPaint();
                rp.setShader(null);
                rp.setColor(romaColor);
                canvas.save();
                canvas.translate(originX, originY + rTopY + slideY);
                if (Math.abs(scale - 1f) > 0.002f) {
                    canvas.scale(scale, scale, pivotX, 0f);
                }
                romaToDraw.draw(canvas);
                if (sweepRomaLayout && effectiveActive) {
                    drawRomaStaticLayoutSweep(canvas, romaToDraw, livePos,
                            readingRgb | (alpha255(sungReadAlpha * contentNow * romaReveal) << 24));
                }
                canvas.restore();
            }
        }

        StaticLayout transToDraw = cache.transLayout != null
                ? cache.transLayout
                : (transReveal > 0f ? ensureExitingTransLayout(cache.widthPx) : null);
        if (transToDraw != null && transReveal > 0f) {
            final int transAlpha = alpha255(translationAlpha() * contentNow * transReveal);
            if (transAlpha > 0) {
                final int transColor = readingRgb | (transAlpha << 24);
                TextPaint tp = transToDraw.getPaint();
                tp.setShader(null);
                tp.setColor(transColor);
                final boolean transAbove = cache.transLayout != null
                        ? (cache.transTopY < cache.mainTopY)
                        : exitingTransBeforeMain;
                final int tTopY = cache.transLayout != null
                        ? cache.transTopY
                        : (transAbove
                           ? (-transToDraw.getHeight() - Dim.dp4)
                           : (cache.totalHeightPx + ((cache.romaLayout == null && !cache.hasSyllableRomanization) ? Dim.dp(8f) : Dim.dp4)));
                float scale = REGION_CLOSED_SCALE + (1f - REGION_CLOSED_SCALE) * transScaleProgress;
                float slideY = (transAbove ? 1f : -1f) * REGION_SLIDE_FACTOR * transToDraw.getHeight() * (1f - transScaleProgress);
                float pivotX = isRightAligned ? transToDraw.getWidth() : (isCentered ? transToDraw.getWidth() * 0.5f : 0f);
                float pivotY = transAbove ? transToDraw.getHeight() : 0f;
                canvas.save();
                canvas.translate(originX, originY + tTopY + translationFlipOffsetY + slideY);
                if (Math.abs(scale - 1f) > 0.002f) {
                    canvas.scale(scale, scale, pivotX, pivotY);
                }
                transToDraw.draw(canvas);
                canvas.restore();
            }
        }

        if ((hasAnimatedWordSweep
                && playing
                && (effectiveActive || activeLine || cache.animator.isStillAnimating()))
                || cache.animator.isTransitionAnimating()) {
            postInvalidateOnAnimation();
        }
    }

    private void drawRomaStaticLayoutSweep(Canvas canvas, StaticLayout romaLayout, long currentPos, int sungColor) {
        if (romaLayout == null) {
            return;
        }
        CharSequence rText = romaLayout.getText();
        final int rLen = rText.length();
        final int rLineCount = romaLayout.getLineCount();
        if (rLen <= 0 || rLineCount <= 0 || wordTimings.isEmpty()) {
            return;
        }
        float[] rMaxX = new float[rLineCount];
        Arrays.fill(rMaxX, Float.NaN);
        final int shift = romaStart - originalTextStart;
        for (int i = 0; i < wordTimings.size(); i++) {
            WordTiming wt = wordTimings.get(i);
            int ls = wt.start() - shift;
            int le = Math.min(rLen, wt.end() - shift);
            if (ls < 0 || ls >= rLen || ls >= le) {
                continue;
            }
            float prog;
            if (currentPos >= wt.endMs()) {
                prog = 1f;
            } else if (currentPos <= wt.startMs()) {
                prog = 0f;
            } else {
                prog = (float) (currentPos - wt.startMs()) / (float) Math.max(1L, wt.endMs() - wt.startMs());
            }
            if (prog <= 0f) {
                continue;
            }
            int ln = romaLayout.getLineForOffset(ls);
            int endLn = romaLayout.getLineForOffset(Math.max(ls, le - 1));
            float lineL = Math.min(romaLayout.getLineLeft(ln), romaLayout.getLineRight(ln));
            float lineR = Math.max(romaLayout.getLineLeft(ln), romaLayout.getLineRight(ln));
            float lead = Math.min(Math.max(romaLayout.getPrimaryHorizontal(ls), lineL), lineR);
            float rawEnd = romaLayout.getPrimaryHorizontal(Math.min(le, rLen));
            float trail = (endLn != ln || rawEnd <= lead) ? lineR : Math.min(Math.max(rawEnd, lineL), lineR);
            float edge = lead + (trail - lead) * prog;
            rMaxX[ln] = Float.isNaN(rMaxX[ln]) ? edge : Math.max(rMaxX[ln], edge);
        }
        TextPaint rp = romaLayout.getPaint();
        final boolean smooth = Settings.isWordAnimSmooth();
        final float featherHalf = Math.max(Dim.dp(5), rp.getTextSize() * 0.30f);
        for (int ln = 0; ln < rLineCount; ln++) {
            float edge = rMaxX[ln];
            if (Float.isNaN(edge)) continue;
            float lineL = Math.min(romaLayout.getLineLeft(ln), romaLayout.getLineRight(ln));
            float lineR = Math.max(romaLayout.getLineLeft(ln), romaLayout.getLineRight(ln));
            int top = romaLayout.getLineTop(ln);
            int bot = romaLayout.getLineBottom(ln);
            boolean useFeather = smooth && edge > lineL && edge < lineR - 0.5f;
            float gStart = useFeather ? Math.max(lineL, edge - featherHalf) : 0f;
            float gEnd = useFeather ? Math.min(lineR, edge + featherHalf) : 0f;
            boolean hasShader = useFeather && (gEnd - gStart > 0.5f);
            // Skia multiplies Paint alpha with Shader alpha, so keep Paint opaque while a shader supplies alpha.
            rp.setColor(hasShader ? (sungColor | 0xFF000000) : sungColor);
            if (hasShader) {
                rp.setShader(new LinearGradient(
                        gStart, 0f, gEnd, 0f,
                        new int[]{sungColor, sungColor, sungColor & 0x00FFFFFF, sungColor & 0x00FFFFFF},
                        new float[]{0f, 0.1f, 0.9f, 1f},
                        Shader.TileMode.CLAMP));
            } else {
                rp.setShader(null);
            }
            canvas.save();
            canvas.clipRect(lineL, top, useFeather ? Math.min(lineR, edge + featherHalf) : Math.max(lineL, edge), bot);
            romaLayout.draw(canvas);
            canvas.restore();
        }
        rp.setShader(null);
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
        final boolean dimMain = unsungColor != 0 && !wordTimings.isEmpty() && sweepEnabled && !allSung;
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
        if (!activeLine && !allSung) {
            return lineAlpha * LyricsColors.UNSUNG_ALPHA;
        }
        return lineAlpha * (readingSwept ? LyricsColors.UNSUNG_ALPHA : LyricsColors.ACTIVE_READING_ALPHA);
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

    /** {@return true when the original-language main run contains RTL script (e.g. Arabic)} */
    private boolean isMainRunRtl(Layout layout) {
        final CharSequence text = layout.getText();
        final int origEnd = originalEnd(text, originalTextStart);
        return origEnd > originalTextStart
                && ScriptDetector.containsRtl(text.subSequence(originalTextStart, origEnd));
    }

    private static boolean isRightToLeftLine(Layout layout, int line) {
        if (line >= 0 && line < layout.getLineCount()) {
            final int start = layout.getLineStart(line);
            final int end = layout.getLineEnd(line);
            CharSequence text = layout.getText();
            for (int i = start; i < end && i < text.length(); i++) {
                byte dir = Character.getDirectionality(text.charAt(i));
                if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
                        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
                    return true;
                } else if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT
                        || dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
                        || dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
                    return false;
                }
            }
        }
        return layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT;
    }

    /** Right to left lines fill leftwards, so their furthest point is the smallest one. */
    private static float extendSung(float current, float edge, boolean rightToLeft) {
        if (Float.isNaN(current)) {
            return edge;
        }
        return rightToLeft ? Math.min(current, edge) : Math.max(current, edge);
    }
}
