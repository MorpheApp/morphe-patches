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

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

final class LyricsLineView extends TextView {
    private static final int REVEAL_CONTENT = 0;
    private static final int REVEAL_TRANS = 1;
    private static final int REVEAL_ROMA = 2;

    private List<LyricsSpanBuilder.WordTiming> wordTimings = Collections.emptyList();
    long positionMs = Long.MIN_VALUE;
    boolean allSung = false;
    private int unsungColor;
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

    // Cache for syllable romanization layout and bounds
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
    boolean smallRunHoldsWords;
    float lastTouchY;
    private final Paint layerPaint = new Paint();
    private PorterDuffColorFilter tintFilter;
    private int tintFilterColor;
    private int baseTextAlpha = 255;
    float lineAlpha = 1f;
    float lineScale = 1f;
    float fadeTargetAlpha = 1f;
    boolean activeLine;
    private int secondaryActiveColor;
    ValueAnimator fadeAnimator;

    float contentReveal = 1f;
    float transReveal = 1f;
    float romaReveal = 1f;
    private ValueAnimator contentRevealAnimator;
    private ValueAnimator transRevealAnimator;
    private ValueAnimator romaRevealAnimator;

    private boolean isSecondary = false;
    private int parentLineIndex = -1;
    private boolean bgBeforeMain = false;
    private boolean isRightAligned = false;
    private float secondaryExpandFraction = 1f;
    private float secondaryLayoutFraction = 1f;
    private boolean secondaryLayoutVisible = true;
    private boolean secondaryTargetExpanded = true;
    private int secondaryFullHeight = 0;
    @Nullable
    private ValueAnimator secondaryExpandAnimator;

    LyricsLineView(Context context) {
        super(context);
    }

    public void setSecondary(boolean secondary) {
        this.isSecondary = secondary;
    }

    public boolean isSecondary() {
        return isSecondary;
    }

    public void setParentLineIndex(int idx) {
        this.parentLineIndex = idx;
    }

    public int getParentLineIndex() {
        return parentLineIndex;
    }

    public void setBgBeforeMain(boolean beforeMain) {
        this.bgBeforeMain = beforeMain;
    }

    public boolean isBgBeforeMain() {
        return bgBeforeMain;
    }

    public void setIsRightAligned(boolean rightAligned) {
        this.isRightAligned = rightAligned;
    }

    public boolean isRightAligned() {
        return isRightAligned;
    }

    private void updateSecondaryMargin(int marginPx) {
        if (!isSecondary) {
            return;
        }
        final ViewGroup.LayoutParams rawLp = getLayoutParams();
        if (rawLp instanceof ViewGroup.MarginLayoutParams lp) {
            final int wantTop = bgBeforeMain ? 0 : marginPx;
            final int wantBottom = bgBeforeMain ? marginPx : 0;
            if (lp.topMargin != wantTop || lp.bottomMargin != wantBottom) {
                lp.topMargin = wantTop;
                lp.bottomMargin = wantBottom;
            }
        }
    }

    public void setSecondaryExpandFraction(float fraction) {
        final float clamped = Math.max(0f, Math.min(1f, fraction));
        this.secondaryExpandFraction = clamped;
        this.secondaryLayoutFraction = clamped;
        final boolean shouldShowLayout = clamped > 0.001f;
        this.secondaryTargetExpanded = shouldShowLayout;
        final float revealScale = 0.8f + 0.2f * clamped;
        final int pivotH = secondaryFullHeight > 0 ? secondaryFullHeight : getHeight();
        setPivotX(isRightAligned ? getWidth() : 0f);
        setPivotY(bgBeforeMain ? pivotH : 0f);
        setScaleX(revealScale);
        setScaleY(revealScale);
        updateSecondaryMargin(Math.round(Dim.dp4 * clamped));
        if (this.secondaryLayoutVisible != shouldShowLayout) {
            this.secondaryLayoutVisible = shouldShowLayout;
        }
        requestLayout();
        invalidate();
    }

    public float getSecondaryExpandFraction() {
        return secondaryExpandFraction;
    }

    public void animateSecondaryExpansion(boolean expand) {
        animateSecondaryExpansion(expand, false);
    }

    // Animate expand and collapse for background vocal line smoothly so view dont get culled
    public void animateSecondaryExpansion(boolean expand, boolean instant) {
        final float target = expand ? 1f : 0f;
        if (instant) {
            if (secondaryExpandAnimator != null) {
                secondaryExpandAnimator.cancel();
                secondaryExpandAnimator = null;
            }
            this.secondaryTargetExpanded = expand;
            this.secondaryLayoutVisible = expand;
            this.secondaryExpandFraction = target;
            this.secondaryLayoutFraction = target;
            setTranslationY(0f);
            updateSecondaryMargin(expand ? Dim.dp4 : 0);
            if (expand) {
                if (getVisibility() != View.VISIBLE) {
                    setVisibility(View.VISIBLE);
                }
                setAlpha(LyricsColors.ACTIVE_BG_ALPHA);
                setScaleX(1f);
                setScaleY(1f);
            } else {
                if (getVisibility() != View.GONE) {
                    setVisibility(View.GONE);
                }
                setAlpha(0f);
                setScaleX(0.8f);
                setScaleY(0.8f);
            }
            requestLayout();
            invalidate();
            return;
        }

        // Do not cancel or restart if already animating toward or resting at the exact same target!
        if (secondaryTargetExpanded == expand) {
            if (secondaryExpandAnimator != null) {
                return;
            }
            if (Math.abs(secondaryLayoutFraction - target) < 0.001f
                    && secondaryLayoutVisible == expand) {
                if (!expand) {
                    if (getVisibility() != View.GONE) {
                        setVisibility(View.GONE);
                    }
                    setAlpha(0f);
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
        }

        if (secondaryExpandAnimator != null) {
            secondaryExpandAnimator.cancel();
            secondaryExpandAnimator = null;
        }

        secondaryTargetExpanded = expand;
        secondaryLayoutVisible = true;
        if (getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
        }

        final float baseDuration = 400f;

        if (expand) {
            final LyricsViewSupport.LyPlusSpring spring =
                    LyricsViewSupport.lyPlusSpringFor((long) baseDuration);
            final long inScaleMs = Math.max(1L, Math.round(spring.totalMs));
            final long inLayoutMs = Math.round(baseDuration);
            final long inAlphaMs = Math.max(300L, Math.min(450L, Math.round(baseDuration * 0.95f)));
            final float startScaleProgress = secondaryExpandFraction;
            final float startLayoutProgress = secondaryLayoutFraction;
            final float startAlpha = getAlpha();

            secondaryExpandAnimator = ValueAnimator.ofFloat(0f, (float) inScaleMs);
            secondaryExpandAnimator.setDuration(inScaleMs);
            secondaryExpandAnimator.setInterpolator(new LinearInterpolator());
            secondaryExpandAnimator.addUpdateListener(animation -> {
                final float tMs = (Float) animation.getAnimatedValue();
                final float scaleProgress = startScaleProgress
                        + (1f - startScaleProgress) * spring.value(tMs);
                secondaryExpandFraction = Math.max(0f, Math.min(1f, scaleProgress));

                final float layoutRaw = Math.max(0f, Math.min(1f, tMs / (float) inLayoutMs));
                final float easedLayout = LyricsViewSupport.LYPLUS_SCROLL_EASING.getInterpolation(layoutRaw);
                secondaryLayoutFraction = Math.max(0f, Math.min(1f,
                        startLayoutProgress + (1f - startLayoutProgress) * easedLayout));

                final int pivotH = secondaryFullHeight > 0 ? secondaryFullHeight : getHeight();
                final float scale = Math.max(0f, 0.8f + 0.2f * scaleProgress);
                setPivotX(isRightAligned ? getWidth() : 0f);
                setPivotY(bgBeforeMain ? pivotH : 0f);
                setScaleX(scale);
                setScaleY(scale);

                final float slideMax = Math.min(Dim.dp(18), Math.max(Dim.dp(8), pivotH * 0.35f));
                setTranslationY((bgBeforeMain ? 1f : -1f) * (1f - secondaryLayoutFraction) * slideMax);

                final float alphaFrac = Math.max(0f, Math.min(1f, tMs / (float) inAlphaMs));
                final float easedAlpha = LyricsViewSupport.LINEAR_OUT_SLOW_IN.getInterpolation(alphaFrac);
                setAlpha(startAlpha + (LyricsColors.ACTIVE_BG_ALPHA - startAlpha) * easedAlpha);

                updateSecondaryMargin(Math.round(Dim.dp4 * secondaryLayoutFraction));
                requestLayout();
                invalidate();
            });
            secondaryExpandAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (secondaryExpandAnimator == animation) {
                        secondaryExpandAnimator = null;
                        secondaryExpandFraction = 1f;
                        secondaryLayoutFraction = 1f;
                        secondaryLayoutVisible = true;
                        setAlpha(LyricsColors.ACTIVE_BG_ALPHA);
                        setScaleX(1f);
                        setScaleY(1f);
                        setTranslationY(0f);
                        updateSecondaryMargin(Dim.dp4);
                        requestLayout();
                        invalidate();
                    }
                }
            });
            secondaryExpandAnimator.start();
        } else {
            final long outMs = Math.max(260L, Math.min(360L, Math.round(baseDuration * 0.8f)));
            final float startScaleProgress = secondaryExpandFraction;
            final float startLayoutProgress = secondaryLayoutFraction;
            final float startAlpha = getAlpha();

            secondaryExpandAnimator = ValueAnimator.ofFloat(0f, 1f);
            secondaryExpandAnimator.setDuration(outMs);
            secondaryExpandAnimator.setInterpolator(LyricsViewSupport.FAST_OUT_SLOW_IN);
            secondaryExpandAnimator.addUpdateListener(animation -> {
                final float f = (Float) animation.getAnimatedValue();
                final float val = startScaleProgress * (1f - f);
                final float layoutVal = startLayoutProgress * (1f - f);
                secondaryExpandFraction = val;
                secondaryLayoutFraction = layoutVal;

                final int pivotH = secondaryFullHeight > 0 ? secondaryFullHeight : getHeight();
                final float scale = Math.max(0f, 0.8f + 0.2f * val);
                setPivotX(isRightAligned ? getWidth() : 0f);
                setPivotY(bgBeforeMain ? pivotH : 0f);
                setScaleX(scale);
                setScaleY(scale);
                setAlpha(startAlpha * (1f - f));

                final float slideMax = Math.min(Dim.dp(18), Math.max(Dim.dp(8), pivotH * 0.35f));
                setTranslationY((bgBeforeMain ? 1f : -1f) * (1f - secondaryLayoutFraction) * slideMax);

                updateSecondaryMargin(Math.round(Dim.dp4 * secondaryLayoutFraction));
                requestLayout();
                invalidate();
            });
            secondaryExpandAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (secondaryExpandAnimator == animation) {
                        secondaryExpandAnimator = null;
                        secondaryExpandFraction = 0f;
                        secondaryLayoutFraction = 0f;
                        secondaryLayoutVisible = false;
                        setTranslationY(0f);
                        setAlpha(0f);
                        updateSecondaryMargin(0);
                        setVisibility(View.GONE);
                        requestLayout();
                    }
                }
            });
            secondaryExpandAnimator.start();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (isSecondary) {
            final int pivotH = secondaryFullHeight > 0 ? secondaryFullHeight : h;
            setPivotX(isRightAligned ? w : 0f);
            setPivotY(bgBeforeMain ? pivotH : 0f);
        } else {
            setPivotX(isRightAligned ? w - getPaddingRight() : getPaddingLeft());
            setPivotY(h * 0.5f);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelRevealAnimators();
        if (fadeAnimator != null) {
            fadeAnimator.removeAllListeners();
            fadeAnimator.cancel();
            fadeAnimator = null;
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
        if (!showSyllableRomanization || syllableCache == null || !syllableCache.hasSyllableRomanization) {
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

    void setActiveLine(boolean active, int color) {
        if (activeLine == active && secondaryActiveColor == color) {
            return;
        }
        activeLine = active;
        secondaryActiveColor = color;
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
            contentRevealAnimator.removeAllListeners();
            contentRevealAnimator.cancel();
            contentRevealAnimator = null;
        }
        if (transRevealAnimator != null) {
            transRevealAnimator.removeAllListeners();
            transRevealAnimator.cancel();
            transRevealAnimator = null;
        }
        if (romaRevealAnimator != null) {
            romaRevealAnimator.removeAllListeners();
            romaRevealAnimator.cancel();
            romaRevealAnimator = null;
        }
        if (secondaryExpandAnimator != null) {
            secondaryExpandAnimator.removeAllListeners();
            secondaryExpandAnimator.cancel();
            secondaryExpandAnimator = null;
        }
    }

    private void animateReveal(int kind, boolean entering) {
        final ValueAnimator running = revealAnimator(kind);
        if (running != null) {
            running.cancel();
        }
        final float target = entering ? 1f : 0f;
        if (Math.abs(reveal(kind) - target) < 0.01f) {
            setReveal(kind, target);
            invalidate();
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(reveal(kind), target);
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
    String getCopyTextForTouch(float touchY) {
        if (syllableCache != null && syllableCache.hasSyllableRomanization) {
            if (syllableCache.transLayout != null && touchY >= textOriginY() + syllableCache.transTopY) {
                return syllableCache.transText != null ? syllableCache.transText.trim() : null;
            }
            return syllableCache.rawMainText != null ? syllableCache.rawMainText.trim() : null;
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

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            lastTouchY = event.getY();
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
        final int[] clusters = clusterBoundaries(text);
        for (int i = 0; i < cachedWordCount; i++) {
            final LyricsSpanBuilder.WordTiming timing = timings.get(i);
            final int s = timing.start() + origStart;
            final int e = Math.min(timing.end() + origStart, text.length());
            if (s < 0 || s >= text.length() || s >= e) {
                cachedWordWrappedLine[i] = -1;
                continue;
            }
            final int snappedStart = snapToClusterStart(clusters, s);
            final int snappedEnd = snapToClusterEnd(clusters, e);
            final int line = lineDrawing(layout, s);
            final int endLine = lineDrawing(layout, e - 1);
            final float lineLeft = Math.min(layout.getLineLeft(line),
                    layout.getLineRight(line));
            final float lineRight = Math.max(layout.getLineLeft(line),
                    layout.getLineRight(line));
            final float lead = Math.min(
                    Math.max(layout.getPrimaryHorizontal(snappedStart), lineLeft), lineRight);
            final boolean isRtl = isRightToLeftLine(layout, line) || layout.isRtlCharAt(s);
            final float trail;
            if (endLine != line) {
                final float width = paint.measureText(text, s, e);
                final float measured = isRtl ? lead - width : lead + width;
                trail = Math.min(Math.max(measured, lineLeft), lineRight);
            } else {
                final float end = layout.getPrimaryHorizontal(snappedEnd);
                if (isRtl) {
                    trail = end >= lead ? lineLeft : Math.min(Math.max(end, lineLeft), lineRight);
                } else {
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
        final int[] boundaries = new int[length + 1];
        final BreakIterator iterator = BreakIterator.getCharacterInstance();
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
        final CharSequence text = layout.getText();
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
        final Paint textPaint = getPaint();
        textPaint.setColor(getCurrentTextColor() | 0xFF000000);
        final ColorFilter previousFilter = textPaint.getColorFilter();
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
        final Layout layout = getLayout();
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
        final int mainAlpha = Math.round(((unsungColor != 0 && !wordTimings.isEmpty())
                ? LyricsColors.UNSUNG_ALPHA * 255f : baseTextAlpha) * lineAlpha * contentNow);
        final float secondaryBaseAlpha = activeLine
                ? LyricsColors.ACTIVE_TRANSLATION_ALPHA * 255f
                : Color.alpha(LyricsColors.secondaryTextColor());
        final int secondaryAlpha = Math.round(lineAlpha * secondaryBaseAlpha * contentNow);
        final float readingBaseAlpha = activeLine
                ? (perWordReading && Settings.LYRICS_WORD_SYNC.get()
                        ? LyricsColors.UNSUNG_ALPHA * 255f
                        : LyricsColors.ACTIVE_READING_ALPHA * 255f)
                : Color.alpha(LyricsColors.secondaryTextColor());
        final int romaAlpha = Math.round(lineAlpha * readingBaseAlpha * contentNow);

        final boolean[] secondaryLine = new boolean[lineCount];
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
            drawTextRun(canvas, layout, 0, lineCount - 1, mainAlpha, 0);
            return true;
        }

        final int romaFirst = rangeLine(layout, romaStart, romaEnd, true);
        final int romaLast = rangeLine(layout, romaStart, romaEnd, false);

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
                    final boolean runHoldsWords = smallRunHoldsWords ? isRoma == (romaFirst >= 0) : isRoma;
                    final float reveal = isRoma ? romaReveal : transReveal;
                    drawTextRun(canvas, layout, runStart, runLast,
                            Math.round((runHoldsWords ? romaAlpha : secondaryAlpha) * reveal),
                            activeLine ? secondaryActiveColor : 0);
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
        if (width > 0) {
            final CharSequence text = getText();
            if (text != null && text.length() > 0) {
                if (showSyllableRomanization && !wordTimings.isEmpty()) {
                    ensureSyllableRomanCache(width, text);
                    if (syllableCache != null && syllableCache.hasSyllableRomanization) {
                        final int desiredH = getPaddingTop() + syllableCache.totalHeightPx + getPaddingBottom();
                        final int resolvedH = resolveSize(desiredH, heightMeasureSpec);
                        secondaryFullHeight = resolvedH;
                        if (isSecondary) {
                            final int reportedH = !secondaryLayoutVisible
                                    ? 0
                                    : (secondaryLayoutFraction >= 0.999f
                                            ? resolvedH
                                            : Math.max(1, Math.round(resolvedH * secondaryLayoutFraction)));
                            setMeasuredDimension(getMeasuredWidth(), reportedH);
                        } else {
                            setMeasuredDimension(getMeasuredWidth(), resolvedH);
                        }
                        return;
                    }
                }
                if ((!isSecondary || secondaryExpandAnimator == null)
                        && LyricsFit.applyOrphanFit(this, width, text, fittedState)) {
                    cachedLayout = null;
                    cachedText = null;
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }
            }
        }
        final int fullH = getMeasuredHeight();
        secondaryFullHeight = fullH;
        if (isSecondary) {
            if (!secondaryLayoutVisible) {
                setMeasuredDimension(getMeasuredWidth(), 0);
            } else if (secondaryLayoutFraction < 0.999f) {
                setMeasuredDimension(getMeasuredWidth(),
                        Math.max(1, Math.round(fullH * secondaryLayoutFraction)));
            }
        }
    }

    @SuppressLint("DrawAllocation")
    @SuppressWarnings("SizeReplaceableByIsEmpty")
    @Override
    protected void onDraw(Canvas canvas) {
        if (isSecondary && !secondaryLayoutVisible && secondaryExpandFraction <= 0.001f) {
            return;
        }
        if (showSyllableRomanization && !wordTimings.isEmpty()) {
            final int width = getWidth() - getPaddingLeft() - getPaddingRight();
            final CharSequence text = getText();
            if (width > 0 && text != null && text.length() > 0) {
                ensureSyllableRomanCache(width, text);
                if (syllableCache != null && syllableCache.hasSyllableRomanization) {
                    drawSyllableRomanizedLine(canvas, syllableCache);
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
                    || unsungColor == 0 || (wordTimings.isEmpty() && !allSung)) {
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
            final boolean hasWords = cachedWordCount > 0;
            boolean allWordsDone = hasWords;
            boolean wordOnMainRun = false;

            final boolean[] secondaryLine = new boolean[lineCount];
            markSecondaryLines(layout, transStart, transEnd, secondaryLine);
            markSecondaryLines(layout, romaStart, romaEnd, secondaryLine);
            boolean anyMainLine = false;
            for (int ln = 0; ln < lineCount; ln++) {
                if (!secondaryLine[ln]) {
                    anyMainLine = true;
                    break;
                }
            }

            for (int i = 0; i < cachedWordCount; i++) {
                final LyricsSpanBuilder.WordTiming timing = wordTimings.get(i);
                final int s = timing.start() + origStart;
                final int e = Math.min(timing.end() + origStart, text.length());
                if (s < 0 || s >= text.length() || s >= e) {
                    continue;
                }
                final int lineNum = cachedWordLine[i];
                if (!secondaryLine[lineNum]) {
                    wordOnMainRun = true;
                }
                float progress;
                if (allSung) {
                    progress = 1f;
                } else if (positionMs >= timing.endMs()) {
                    progress = 1f;
                } else if (positionMs <= timing.startMs()) {
                    progress = 0f;
                } else {
                    progress = (float) (positionMs - timing.startMs())
                            / (float) (timing.endMs() - timing.startMs());
                }
                allWordsDone = allWordsDone && progress >= 1f;
                if (progress <= 0f) {
                    continue;
                }
                if (firstSungLine < 0) {
                    firstSungLine = lineNum;
                }

                final float lead = cachedWordLeadX[i];
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

            if (allSung && firstSungLine < 0) {
                int firstOffset = -1;
                int lastOffset = -1;
                if (origStart >= 0 && origStart < text.length()) {
                    firstOffset = origStart;
                    lastOffset = originalEnd(text, origStart);
                }
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

            final int readingColor = (activeLine && secondaryActiveColor != 0
                    ? secondaryActiveColor : LyricsColors.secondaryTextColor()) | 0xFF000000;
            final int lyricColor = LyricsColors.lineTextColor() | 0xFF000000;
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
            final ColorFilter prevFilter = tp.getColorFilter();
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

    // =========================================================================================
    // 1:1 Per-Syllable Romanization Layout + Identical clipRect Word-Sync Wipe
    // =========================================================================================

    private static final class AlignedSyllable {
        final LyricsSpanBuilder.WordTiming syllable;
        final String text;
        final int startOffset;
        final int endOffset;

        AlignedSyllable(LyricsSpanBuilder.WordTiming syllable, String text,
                        int startOffset, int endOffset) {
            this.syllable = syllable;
            this.text = text;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    private static final class RomanizedWordCluster {
        final int wordIndex;
        final int startIndex;
        final int endIndex;
        final String romanText;
        final int lineIdx;
        final long wordStartMs;
        final long wordEndMs;
        final float originalCanvasX;
        final StaticLayout subLayout;
        final float romanWidth;

        RomanizedWordCluster(int wordIndex, int startIndex, int endIndex, String romanText,
                             int lineIdx, long wordStartMs, long wordEndMs,
                             float originalCanvasX, StaticLayout subLayout, float romanWidth) {
            this.wordIndex = wordIndex;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.romanText = romanText;
            this.lineIdx = lineIdx;
            this.wordStartMs = wordStartMs;
            this.wordEndMs = wordEndMs;
            this.originalCanvasX = originalCanvasX;
            this.subLayout = subLayout;
            this.romanWidth = romanWidth;
        }
    }

    private static final class ClusterMeta {
        final int start;
        final int end;
        final int lineIdx;
        final float x;
        final float y;
        final float width;
        final boolean isRtl;

        ClusterMeta(int start, int end, int lineIdx, float x, float y, float width, boolean isRtl) {
            this.start = start;
            this.end = end;
            this.lineIdx = lineIdx;
            this.x = x;
            this.y = y;
            this.width = width;
            this.isRtl = isRtl;
        }
    }

    private static final class VisualCluster {
        final int start;
        final int end;
        final int lineIdx;
        final float x;
        final float y;
        final float width;
        final boolean isRtl;
        final StaticLayout subLayout;
        final int syllableIndex;
        final int shiftIndex;

        VisualCluster(int start, int end, int lineIdx, float x, float y, float width,
                      boolean isRtl, StaticLayout subLayout, int syllableIndex, int shiftIndex) {
            this.start = start;
            this.end = end;
            this.lineIdx = lineIdx;
            this.x = x;
            this.y = y;
            this.width = width;
            this.isRtl = isRtl;
            this.subLayout = subLayout;
            this.syllableIndex = syllableIndex;
            this.shiftIndex = shiftIndex;
        }
    }

    private static final class ClusterStatics {
        final float[] baseX;
        final float[] baseY;
        final boolean[] isRtlLine;

        ClusterStatics(float[] baseX, float[] baseY, boolean[] isRtlLine) {
            this.baseX = baseX;
            this.baseY = baseY;
            this.isRtlLine = isRtlLine;
        }
    }

    private static final class RomanLineStatics {
        final List<RomanizedWordCluster> clusters;
        final float[] drawX;
        final float[] romWidth;
        final float y;

        RomanLineStatics(List<RomanizedWordCluster> clusters, float[] drawX,
                         float[] romWidth, float y) {
            this.clusters = clusters;
            this.drawX = drawX;
            this.romWidth = romWidth;
            this.y = y;
        }
    }

    private static final class RomanStatics {
        final RomanLineStatics[] lines;

        RomanStatics(RomanLineStatics[] lines) {
            this.lines = lines;
        }
    }

    private static final class SyllableRomanCache {
        final int widthPx;
        final float fontSizePx;
        final String rawMainText;
        final String formattedMainText;
        final StaticLayout mainLayout;
        final int lineCount;
        final float totalWidth;
        final List<AlignedSyllable> alignedSyllables;
        final List<RomanizedWordCluster> romanClusters;
        final boolean hasSyllableRomanization;
        final float lineExtraHeight;
        final int extraRomanHeightPx;
        final List<VisualCluster> clusters;
        final ClusterStatics statics;
        final RomanStatics romanStatics;
        final int[] sylLine;
        final float[] sylShiftedLeadX;
        final float[] sylShiftedTrailX;
        final float[] sylProgressScratch;
        @Nullable
        final String transText;
        @Nullable
        final StaticLayout transLayout;
        final int transTopY;
        final int totalHeightPx;

        SyllableRomanCache(int widthPx, float fontSizePx, String rawMainText,
                           String formattedMainText, StaticLayout mainLayout, int lineCount,
                           float totalWidth, List<AlignedSyllable> alignedSyllables,
                           List<RomanizedWordCluster> romanClusters, boolean hasSyllableRomanization,
                           float lineExtraHeight, int extraRomanHeightPx,
                           List<VisualCluster> clusters, ClusterStatics statics,
                           RomanStatics romanStatics, int[] sylLine,
                           float[] sylShiftedLeadX, float[] sylShiftedTrailX,
                           @Nullable String transText, @Nullable StaticLayout transLayout,
                           int transTopY, int totalHeightPx) {
            this.widthPx = widthPx;
            this.fontSizePx = fontSizePx;
            this.rawMainText = rawMainText;
            this.formattedMainText = formattedMainText;
            this.mainLayout = mainLayout;
            this.lineCount = lineCount;
            this.totalWidth = totalWidth;
            this.alignedSyllables = alignedSyllables;
            this.romanClusters = romanClusters;
            this.hasSyllableRomanization = hasSyllableRomanization;
            this.lineExtraHeight = lineExtraHeight;
            this.extraRomanHeightPx = extraRomanHeightPx;
            this.clusters = clusters;
            this.statics = statics;
            this.romanStatics = romanStatics;
            this.sylLine = sylLine;
            this.sylShiftedLeadX = sylShiftedLeadX;
            this.sylShiftedTrailX = sylShiftedTrailX;
            this.sylProgressScratch = new float[alignedSyllables.size()];
            this.transText = transText;
            this.transLayout = transLayout;
            this.transTopY = transTopY;
            this.totalHeightPx = totalHeightPx;
        }
    }

    /**
     * Builds a single-line StaticLayout with unconstrained width so a word or romaji syllable
     * can NEVER wrap its last character onto a second line.
     */
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

    private static float singleLineWidth(String text, TextPaint paint, StaticLayout layout) {
        final float desiredW = Math.max(paint.measureText(text), Layout.getDesiredWidth(text, paint));
        final float lineW = layout.getLineCount() > 0
                ? (layout.getLineRight(0) - layout.getLineLeft(0)) : 0f;
        final float horizW = text.length() > 0 ? layout.getPrimaryHorizontal(text.length()) : 0f;
        return Math.max(1f, Math.max(desiredW, Math.max(lineW, horizW)));
    }

    private void ensureSyllableRomanCache(int widthPx, CharSequence fullText) {
        final float fontSizePx = getTextSize();
        if (syllableCache != null
                && syllableCache.widthPx == widthPx
                && Math.abs(syllableCache.fontSizePx - fontSizePx) < 0.5f) {
            return;
        }
        final String fullStr = fullText.toString();
        final int origStart = Math.max(0, Math.min(originalTextStart, fullStr.length()));
        final int newline = fullStr.indexOf('\n', origStart);
        final int origEnd = newline >= 0 ? newline : fullStr.length();
        final String rawMainText = fullStr.substring(origStart, origEnd);
        if (rawMainText.trim().isEmpty() || LyricsSpanBuilder.isRtlText(rawMainText)) {
            syllableCache = null;
            return;
        }

        final boolean isRightAligned = (getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END;
        final Layout.Alignment mainAlign = LyricsSpanBuilder.resolveAlignment(false, isRightAligned);

        final TextPaint mainPaint = new TextPaint(getPaint());
        mainPaint.setTextSize(fontSizePx);
        LyricsViewSupport.applyLyricsBoldPaint(mainPaint);
        final float mainLetterSpacing = LyricsSpanBuilder.containsJoiningScript(rawMainText)
                ? 0f : (-0.5f / Math.max(12f, Settings.LYRICS_TEXT_SIZE.get()));
        mainPaint.setLetterSpacing(mainLetterSpacing);

        final TextPaint romPaint = new TextPaint(mainPaint);
        romPaint.setTextSize(fontSizePx * 0.7f);
        LyricsViewSupport.applyLyricsBoldPaint(romPaint);
        romPaint.setLetterSpacing(0f);

        final String formattedMainText = buildFormattedMainText(
                rawMainText, wordTimings, mainPaint, romPaint, widthPx, fontSizePx);

        final StaticLayout mainLayout = StaticLayout.Builder.obtain(
                        formattedMainText, 0, formattedMainText.length(), mainPaint, widthPx)
                .setAlignment(mainAlign)
                .setIncludePad(false)
                .setLineSpacing(0f, LyricsSpanBuilder.MAIN_LINE_SPACING)
                .build();

        final int lineCount = mainLayout.getLineCount();
        final float[] lineLefts = new float[lineCount];
        final float[] lineRights = new float[lineCount];
        final float[] lineStarts = new float[lineCount];
        final float[] lineWidths = new float[lineCount];
        final float[] lineBaselines = new float[lineCount];
        final float[] lineBottoms = new float[lineCount];
        final boolean[] lineIsRtl = new boolean[lineCount];

        float cum = 0f;
        float totalW = 0f;
        for (int i = 0; i < lineCount; i++) {
            lineLefts[i] = mainLayout.getLineLeft(i);
            lineRights[i] = mainLayout.getLineRight(i);
            final float w = Math.max(1f, lineRights[i] - lineLefts[i]);
            lineStarts[i] = cum;
            lineWidths[i] = w;
            cum += w;
            totalW += w;
            lineBaselines[i] = mainLayout.getLineBaseline(i);
            lineBottoms[i] = mainLayout.getLineBottom(i);
            lineIsRtl[i] = mainLayout.getParagraphDirection(i) == Layout.DIR_RIGHT_TO_LEFT;
        }

        final List<AlignedSyllable> alignedSyllables =
                alignSyllablesForFormatting(wordTimings, rawMainText, formattedMainText);
        final List<RomanizedWordCluster> romanClusters =
                buildRomanizedWordClusters(alignedSyllables, formattedMainText, mainLayout, romPaint);

        final boolean hasSyllableRoma = !romanClusters.isEmpty()
                && !LyricsSpanBuilder.isRtlText(formattedMainText);
        final float lineExtraHeight = hasSyllableRoma ? (fontSizePx * 1.05f) : 0f;
        final int bottomMargin = hasSyllableRoma ? Math.round(fontSizePx * 0.25f) : 0;
        final int extraRomanHeightPx = Math.round(lineCount * lineExtraHeight) + bottomMargin;

        final float[] charXMap = buildCharXMap(
                formattedMainText, mainLayout, lineCount, lineIsRtl, lineStarts);
        final float[] romanStartShift = buildRomanStartShift(
                formattedMainText, charXMap, mainLayout, lineCount,
                alignedSyllables, romanClusters, mainAlign);

        final List<VisualCluster> clusters = buildVisualClusters(
                formattedMainText, mainLayout, alignedSyllables, lineIsRtl, lineBaselines, mainPaint);
        final ClusterStatics statics = buildClusterStatics(
                clusters, romanStartShift, lineExtraHeight, lineIsRtl);
        final List<RomanizedWordCluster>[] romanLines =
                groupRomanClustersByLine(romanClusters, lineCount);
        final RomanStatics romanStatics = buildRomanStatics(
                romanLines, charXMap, romanStartShift, lineBaselines, lineBottoms,
                lineLefts, lineStarts, lineCount, fontSizePx, lineExtraHeight, totalW);

        // Precompute shifted lead/trail X for each syllable so clipRect word sweep matches onDraw 1:1
        final int sylCount = alignedSyllables.size();
        final int[] sylLine = new int[sylCount];
        final float[] sylShiftedLeadX = new float[sylCount];
        final float[] sylShiftedTrailX = new float[sylCount];
        Arrays.fill(sylShiftedLeadX, Float.NaN);
        Arrays.fill(sylShiftedTrailX, Float.NaN);

        for (int idx = 0; idx < clusters.size(); idx++) {
            final VisualCluster vc = clusters.get(idx);
            final int sIdx = vc.syllableIndex;
            if (sIdx >= 0 && sIdx < sylCount) {
                sylLine[sIdx] = vc.lineIdx;
                final float leftX = statics.baseX[idx];
                final float rightX = leftX + vc.width;
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
                final AlignedSyllable syl = alignedSyllables.get(sIdx);
                final int li = mainLayout.getLineForOffset(Math.max(0, Math.min(syl.startOffset, maxCharIdx)));
                sylLine[sIdx] = li;
                final float shift = calculateGapShift(sIdx, romanStartShift);
                final float sx = mainLayout.getPrimaryHorizontal(Math.max(0, Math.min(syl.startOffset, formattedMainText.length()))) + shift;
                final float ex = mainLayout.getPrimaryHorizontal(Math.max(0, Math.min(syl.endOffset, formattedMainText.length()))) + shift;
                sylShiftedLeadX[sIdx] = Math.min(sx, ex);
                sylShiftedTrailX[sIdx] = Math.max(sx + 1f, ex);
            }
        }

        String transText = null;
        StaticLayout transLayout = null;
        final int mainSectionH = mainLayout.getHeight() + extraRomanHeightPx;
        int transTopY = mainSectionH;
        int totalH = mainSectionH;

        if (transStart >= 0 && transEnd > transStart && transStart < fullStr.length()) {
            transText = fullStr.substring(transStart, Math.min(transEnd, fullStr.length())).trim();
            if (!transText.isEmpty()) {
                final TextPaint transPaint = new TextPaint(getPaint());
                transPaint.setTextSize(fontSizePx * LyricsSpanBuilder.TRANSLATION_RELATIVE_SIZE);
                LyricsViewSupport.applyLyricsBoldPaint(transPaint);
                transPaint.setLetterSpacing(0f);
                final boolean isTransRtl = LyricsSpanBuilder.isRtlText(transText);
                final Layout.Alignment transAlign =
                        LyricsSpanBuilder.resolveAlignment(isTransRtl, isRightAligned);
                transLayout = StaticLayout.Builder.obtain(
                                transText, 0, transText.length(), transPaint, widthPx)
                        .setAlignment(transAlign)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1.18f)
                        .build();
                transTopY = mainSectionH + Dim.dp4;
                totalH = transTopY + transLayout.getHeight();
            }
        }

        syllableCache = new SyllableRomanCache(
                widthPx, fontSizePx, rawMainText, formattedMainText, mainLayout, lineCount,
                totalW, alignedSyllables, romanClusters, hasSyllableRoma, lineExtraHeight,
                extraRomanHeightPx, clusters, statics, romanStatics,
                sylLine, sylShiftedLeadX, sylShiftedTrailX,
                transText, transLayout, transTopY, totalH);
    }

    // Align syllable to formatted text with bidi offset
    private static List<AlignedSyllable> alignSyllablesForFormatting(
            List<LyricsSpanBuilder.WordTiming> words,
            String rawMainText,
            String targetMainText) {
        if (words.isEmpty() || targetMainText.isEmpty()) {
            return Collections.emptyList();
        }
        final List<Integer> strippedToOriginal = new ArrayList<>(targetMainText.length() + 1);
        final StringBuilder strippedBuilder = new StringBuilder(targetMainText.length());
        for (int i = 0; i < targetMainText.length(); i++) {
            final char c = targetMainText.charAt(i);
            if (!LyricsSpanBuilder.isBidiOrInvisible(c)) {
                strippedBuilder.append(c);
                strippedToOriginal.add(i);
            }
        }
        strippedToOriginal.add(targetMainText.length());
        final String strippedMain = strippedBuilder.toString();
        final String strippedLower = strippedMain.toLowerCase(Locale.ROOT);

        int totalSylLen = 0;
        final String[] sylTexts = new String[words.size()];
        for (int i = 0; i < words.size(); i++) {
            final LyricsSpanBuilder.WordTiming wt = words.get(i);
            final int s = Math.max(0, Math.min(wt.start(), rawMainText.length()));
            final int e = Math.max(s, Math.min(wt.end(), rawMainText.length()));
            final String st = LyricsSpanBuilder.stripBidiControls(rawMainText.substring(s, e)).trim();
            sylTexts[i] = st;
            totalSylLen += st.length();
        }
        totalSylLen = Math.max(1, totalSylLen);

        int searchIdx = 0;
        final List<AlignedSyllable> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            final LyricsSpanBuilder.WordTiming syl = words.get(i);
            final String sylText = sylTexts[i];
            final String sylLower = sylText.toLowerCase(Locale.ROOT);
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
            out.add(new AlignedSyllable(syl, sylText, si, ei));
        }
        return out;
    }

    // Format main text and add line breaks so words dont get split in middle
    private static String buildFormattedMainText(
            String mainText,
            List<LyricsSpanBuilder.WordTiming> words,
            TextPaint mainPaint,
            TextPaint romPaint,
            int widthPx,
            float fontSizePx) {
        if (mainText.isEmpty() || widthPx <= 0 || words.isEmpty()) {
            return mainText;
        }
        final List<AlignedSyllable> alignedSyllables =
                alignSyllablesForFormatting(words, mainText, mainText);
        if (alignedSyllables.isEmpty()) {
            return mainText;
        }

        final int unconstrainedWidth = Math.max(widthPx * 16, 16384);
        final StaticLayout unconstrained = StaticLayout.Builder.obtain(
                        mainText, 0, mainText.length(), mainPaint, unconstrainedWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();

        final List<RomanizedWordCluster> romanClusters =
                buildRomanizedWordClusters(alignedSyllables, mainText, unconstrained, romPaint);
        if (romanClusters.isEmpty()) {
            return mainText;
        }

        final Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2);
        for (RomanizedWordCluster rc : romanClusters) {
            clusterMap.put(rc.wordIndex, rc);
        }

        final float[] sylSlotWidths = new float[alignedSyllables.size()];
        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            final AlignedSyllable syl = alignedSyllables.get(sylIdx);
            final RomanizedWordCluster cluster = clusterMap.get(sylIdx);
            final int startOff = Math.max(0, Math.min(syl.startOffset, mainText.length()));
            final int nextStartOff = (sylIdx + 1 < alignedSyllables.size())
                    ? Math.max(syl.endOffset, Math.min(alignedSyllables.get(sylIdx + 1).startOffset, mainText.length()))
                    : Math.max(startOff, Math.min(syl.endOffset, mainText.length()));
            final boolean hasNewlineInGap = mainText.substring(
                    Math.max(0, Math.min(syl.endOffset, mainText.length())), nextStartOff).indexOf('\n') >= 0;
            final int slotEndOff = hasNewlineInGap
                    ? Math.max(startOff, Math.min(syl.endOffset, mainText.length()))
                    : nextStartOff;
            final float startX = unconstrained.getPrimaryHorizontal(startOff);
            final float endX = unconstrained.getPrimaryHorizontal(slotEndOff);
            final float slotMainWidth = Math.max(1f, endX - startX);
            final float romWidth = cluster != null ? cluster.romanWidth : 0f;
            sylSlotWidths[sylIdx] = Math.max(slotMainWidth, romWidth);
        }

        final List<Integer> breakIndices = new ArrayList<>();
        final Set<Integer> spaceReplaced = new HashSet<>();

        float currentLineSlotSum = 0f;
        int syllablesOnCurrentLine = 0;
        int currentLineStartOffset = 0;

        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            final AlignedSyllable syl = alignedSyllables.get(sylIdx);
            final float slotW = sylSlotWidths[sylIdx];
            final AlignedSyllable prevSyl = sylIdx > 0 ? alignedSyllables.get(sylIdx - 1) : null;

            if (prevSyl != null) {
                final int gs = Math.max(0, Math.min(prevSyl.endOffset, mainText.length()));
                final int ge = Math.max(gs, Math.min(syl.startOffset, mainText.length()));
                if (mainText.substring(gs, ge).indexOf('\n') >= 0) {
                    currentLineSlotSum = 0f;
                    syllablesOnCurrentLine = 0;
                    currentLineStartOffset = Math.max(0, Math.min(syl.startOffset, mainText.length()));
                }
            }

            final float breakThreshold = widthPx - fontSizePx * 0.1f;
            if (syllablesOnCurrentLine > 0 && currentLineSlotSum + slotW > breakThreshold) {
                final int rawBreakAt = Math.max(0, Math.min(syl.startOffset, mainText.length()));
                final Integer breakAt = findSafeBreakOffset(mainText, rawBreakAt, currentLineStartOffset);
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

        final StringBuilder sb = new StringBuilder(mainText.length() + breakIndices.size());
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

    /**
     * Checks if a character is Chinese/Japanese ideograph or kana (where words have no spaces).
     * Hangul words ARE space-separated, so Hangul prefers breaking at spaces when available.
     */
    private static boolean isHanOrKanaChar(char c) {
        final int cp = (int) c;
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x3040 && cp <= 0x309F)
                || (cp >= 0x30A0 && cp <= 0x30FF);
    }

    private static boolean isCJKChar(char c) {
        return isHanOrKanaChar(c) || (c >= 0xAC00 && c <= 0xD7AF);
    }

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
        final Character before = rawBreakAt - 1 >= 0 && rawBreakAt - 1 < mainText.length()
                ? mainText.charAt(rawBreakAt - 1) : null;
        final Character at = rawBreakAt >= 0 && rawBreakAt < mainText.length()
                ? mainText.charAt(rawBreakAt) : null;

        // If the current line has whitespace (like Korean or mixed Korean/English), prefer breaking
        // at a space rather than splitting a Korean word in the middle.
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
                (before == null || before == ' ' || isCJKChar(before))
                        && (at == null || at == ' ' || isCJKChar(at) || isCJKChar(before));

        int candidate = rawBreakAt;
        if (!atCjkBoundary) {
            int i = rawBreakAt;
            while (i > lineStartOffset) {
                final char c = mainText.charAt(i - 1);
                final Character next = i < mainText.length() ? mainText.charAt(i) : null;
                if (c == ' ' || isCJKChar(c) || (next != null && isCJKChar(next))) {
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
                    && !(mainText.charAt(candidate - 1) == ' ' || isCJKChar(mainText.charAt(candidate - 1)))) {
                candidate--;
            }
        }

        return candidate > lineStartOffset ? candidate : null;
    }

    // Build romaji cluster layout using single line layout so it dont wrap to next line
    private static List<RomanizedWordCluster> buildRomanizedWordClusters(
            List<AlignedSyllable> alignedSyllables,
            String mainText,
            Layout textLayoutResult,
            TextPaint romPaint) {
        if (alignedSyllables.isEmpty() || mainText.isEmpty()) {
            return Collections.emptyList();
        }
        final String cleanMainText = LyricsSpanBuilder.stripBidiControls(mainText).trim();
        final StringBuilder assembled = new StringBuilder();
        for (AlignedSyllable syl : alignedSyllables) {
            final String r = syl.syllable.romaji();
            if (r != null) {
                assembled.append(r);
            }
        }
        final String assembledRoman = LyricsSpanBuilder.stripBidiControls(assembled.toString()).trim();
        if (assembledRoman.isEmpty() || assembledRoman.equalsIgnoreCase(cleanMainText)) {
            return Collections.emptyList();
        }

        final List<RomanizedWordCluster> result = new ArrayList<>(alignedSyllables.size());
        final int maxCharIdx = Math.max(0, mainText.length() - 1);
        for (int idx = 0; idx < alignedSyllables.size(); idx++) {
            final AlignedSyllable syl = alignedSyllables.get(idx);
            final String text = syl.syllable.romaji();
            if (text != null && !text.trim().isEmpty()) {
                final int clampedStart = Math.max(0, Math.min(syl.startOffset, maxCharIdx));
                final int li = textLayoutResult.getLineForOffset(clampedStart);
                final TextPaint subPaint = new TextPaint(romPaint);
                final StaticLayout subLayout = buildSingleLineLayout(text, subPaint);
                final float romW = singleLineWidth(text, subPaint, subLayout);
                result.add(new RomanizedWordCluster(
                        idx,
                        syl.startOffset,
                        syl.endOffset,
                        text,
                        li,
                        syl.syllable.startMs(),
                        syl.syllable.endMs(),
                        textLayoutResult.getPrimaryHorizontal(clampedStart),
                        subLayout,
                        romW));
            }
        }
        return result;
    }

    // Map char index to line start x offset
    private static float[] buildCharXMap(
            String mainText,
            Layout textLayoutResult,
            int lineCount,
            boolean[] lineIsRtl,
            float[] lineStarts) {
        final int len = mainText.length();
        final float[] map = new float[len + 1];
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

    // Calculate start shift so romanization words dont overlap with neighbor words
    private static float[] buildRomanStartShift(
            String mainText,
            float[] charXMap,
            Layout textLayoutResult,
            int lineCount,
            List<AlignedSyllable> alignedSyllables,
            List<RomanizedWordCluster> romanClusters,
            Layout.Alignment alignment) {
        final float[] shiftByWordIndex = new float[romanClusters.size() + alignedSyllables.size() + 1];
        final float[] lineTotalExtra = new float[lineCount];
        if (romanClusters.isEmpty()) {
            return shiftByWordIndex;
        }

        final Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2);
        for (RomanizedWordCluster rc : romanClusters) {
            clusterMap.put(rc.wordIndex, rc);
        }

        float runningShift = 0f;
        int currentLine = -1;
        final int maxCharIdx = Math.max(0, mainText.length() - 1);
        final int maxMapIdx = Math.max(0, charXMap.length - 1);

        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            final AlignedSyllable syl = alignedSyllables.get(sylIdx);
            final int li = textLayoutResult.getLineForOffset(
                    Math.max(0, Math.min(syl.startOffset, maxCharIdx)));
            if (li != currentLine) {
                currentLine = li;
                runningShift = 0f;
            }
            if (sylIdx < shiftByWordIndex.length) {
                shiftByWordIndex[sylIdx] = runningShift;
            }

            final RomanizedWordCluster cluster = clusterMap.get(sylIdx);
            if (cluster == null) {
                continue;
            }
            final int sylEndLine = textLayoutResult.getLineForOffset(
                    Math.max(0, Math.min(syl.endOffset, maxCharIdx)));
            final int lineEndOff = textLayoutResult.getLineVisibleEnd(li);

            // Include any trailing space before the next syllable on the same line so we compare
            // (word + space) in mainLayout against (romaji + space) in cluster.romanWidth.
            int effectiveEndOff = syl.endOffset;
            if (li == sylEndLine && sylIdx + 1 < alignedSyllables.size()) {
                final AlignedSyllable nextSyl = alignedSyllables.get(sylIdx + 1);
                final int nextLi = textLayoutResult.getLineForOffset(
                        Math.max(0, Math.min(nextSyl.startOffset, maxCharIdx)));
                if (nextLi == li && nextSyl.startOffset > syl.endOffset) {
                    effectiveEndOff = nextSyl.startOffset;
                }
            }

            final float startX = charXMap[Math.max(0, Math.min(syl.startOffset, maxMapIdx))];
            final float endX = (li == sylEndLine)
                    ? charXMap[Math.max(0, Math.min(effectiveEndOff, maxMapIdx))]
                    : charXMap[Math.max(0, Math.min(lineEndOff, maxMapIdx))];
            final float mainWidth = Math.max(1f, endX - startX);
            final float romWidth = cluster.romanWidth;

            if (romWidth > mainWidth) {
                final float extra = romWidth - mainWidth;
                runningShift += extra;
                if (li >= 0 && li < lineTotalExtra.length) {
                    lineTotalExtra[li] += extra;
                }
            }
        }

        if (alignment == Layout.Alignment.ALIGN_CENTER || alignment == Layout.Alignment.ALIGN_OPPOSITE) {
            for (int idx = 0; idx < alignedSyllables.size() && idx < shiftByWordIndex.length; idx++) {
                final AlignedSyllable syl = alignedSyllables.get(idx);
                final int li = textLayoutResult.getLineForOffset(
                        Math.max(0, Math.min(syl.startOffset, maxCharIdx)));
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
            boolean[] lineIsRtl,
            float[] lineBaselines,
            TextPaint mainPaint) {
        final List<ClusterMeta> metaList = new ArrayList<>(formattedMainText.length());
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
            final float y = textLayoutResult.getLineTop(li);
            metaList.add(new ClusterMeta(start, end, li, x, y, w, lineIsRtl[li]));
        }

        final int[] sylIndices = new int[metaList.size()];
        final int[] shiftIndices = new int[metaList.size()];
        int sylPointer = 0;
        int lastSylOnLine = -1;
        int curLine = -1;
        for (int mIdx = 0; mIdx < metaList.size(); mIdx++) {
            final ClusterMeta m = metaList.get(mIdx);
            if (m.lineIdx != curLine) {
                curLine = m.lineIdx;
                lastSylOnLine = -1;
            }
            while (sylPointer < alignedSyllables.size()
                    && alignedSyllables.get(sylPointer).endOffset <= m.start) {
                sylPointer++;
            }
            if (sylPointer < alignedSyllables.size()) {
                final AlignedSyllable s = alignedSyllables.get(sylPointer);
                if (m.start >= s.startOffset && m.end <= s.endOffset) {
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

        final List<VisualCluster> list = new ArrayList<>();
        int idx = 0;
        while (idx < metaList.size()) {
            final ClusterMeta m = metaList.get(idx);
            final int runSylIdx = sylIndices[idx];
            final int runShiftIdx = shiftIndices[idx];
            int j = idx;
            // Only merge consecutive characters that belong to the same valid syllable on the same line
            if (runSylIdx >= 0) {
                while (j + 1 < metaList.size()
                        && metaList.get(j + 1).lineIdx == m.lineIdx
                        && sylIndices[j + 1] == runSylIdx) {
                    j++;
                }
            }
            float runLeft = Float.MAX_VALUE;
            float runRight = -Float.MAX_VALUE;
            for (int k = idx; k <= j; k++) {
                final ClusterMeta cm = metaList.get(k);
                if (cm.x < runLeft) runLeft = cm.x;
                if (cm.x + cm.width > runRight) runRight = cm.x + cm.width;
            }
            final ClusterMeta last = metaList.get(j);
            final String runText = formattedMainText.substring(
                    Math.max(0, Math.min(m.start, len)),
                    Math.max(0, Math.min(last.end, len)));
            final TextPaint subPaint = new TextPaint(mainPaint);
            final StaticLayout subLayout = buildSingleLineLayout(runText, subPaint);
            final float subW = Math.max(runRight - runLeft, singleLineWidth(runText, subPaint, subLayout));
            final float clusterY = lineBaselines[m.lineIdx] - subLayout.getLineBaseline(0);
            list.add(new VisualCluster(
                    m.start,
                    last.end,
                    m.lineIdx,
                    runLeft,
                    clusterY,
                    Math.max(1f, subW),
                    m.isRtl,
                    subLayout,
                    runSylIdx,
                    runShiftIdx));
            idx = j + 1;
        }
        return list;
    }

    private static ClusterStatics buildClusterStatics(
            List<VisualCluster> clusters,
            float[] romanStartShift,
            float lineExtraHeight,
            boolean[] lineIsRtl) {
        final int n = clusters.size();
        final float[] baseX = new float[n];
        final float[] baseY = new float[n];
        final boolean[] rtl = new boolean[n];
        for (int i = 0; i < n; i++) {
            final VisualCluster vc = clusters.get(i);
            final int shiftIdx = vc.syllableIndex >= 0 ? vc.syllableIndex : vc.shiftIndex;
            baseX[i] = vc.x + calculateGapShift(shiftIdx, romanStartShift);
            baseY[i] = vc.y + vc.lineIdx * lineExtraHeight;
            rtl[i] = lineIsRtl[vc.lineIdx];
        }
        return new ClusterStatics(baseX, baseY, rtl);
    }

    @SuppressWarnings("unchecked")
    private static List<RomanizedWordCluster>[] groupRomanClustersByLine(
            List<RomanizedWordCluster> romanClusters, int lineCount) {
        if (romanClusters.isEmpty()) {
            return new List[0];
        }
        final List<RomanizedWordCluster>[] lines = new List[lineCount];
        for (int li = 0; li < lineCount; li++) {
            lines[li] = new ArrayList<>();
        }
        for (RomanizedWordCluster rc : romanClusters) {
            if (rc.lineIdx >= 0 && rc.lineIdx < lineCount) {
                lines[rc.lineIdx].add(rc);
            }
        }
        return lines;
    }

    private static RomanStatics buildRomanStatics(
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
        final RomanLineStatics[] lines = new RomanLineStatics[count];

        for (int li = 0; li < count; li++) {
            final List<RomanizedWordCluster> cs = romanLines[li];
            if (cs == null || cs.isEmpty()) {
                continue;
            }
            final float lineLeft = li < lineLefts.length ? lineLefts[li] : 0f;
            final float lineStart = li < lineStarts.length ? lineStarts[li] : 0f;
            final int n = cs.size();
            final float[] drawX = new float[n];
            final float[] romWidth = new float[n];
            for (int i = 0; i < n; i++) {
                final RomanizedWordCluster c = cs.get(i);
                final float gx = c.startIndex < 0 ? 0f
                        : (c.startIndex >= charXMap.length ? totalWidth : charXMap[c.startIndex]);
                final float shift = (c.wordIndex >= 0 && c.wordIndex < romanStartShift.length)
                        ? romanStartShift[c.wordIndex] : 0f;
                drawX[i] = gx - lineStart + lineLeft + shift;
                romWidth[i] = c.romanWidth;
            }
            final float baseLineBottom = li < lineBottoms.length
                    ? lineBottoms[li] : (lineBaselines[li] + fontSizePx * 0.25f);
            final float y = baseLineBottom + li * lineExtraHeight + fontSizePx * 0.08f;
            lines[li] = new RomanLineStatics(cs, drawX, romWidth, y);
        }
        return new RomanStatics(lines);
    }

    /**
     * Draws syllable-romanized lines using the EXACT SAME {@code canvas.clipRect} progressive
     * word sweep, progress formula, and alpha/color rules as {@link #onDraw(Canvas)} when
     * {@code showSyllableRomanization} is false.
     */
    private void drawSyllableRomanizedLine(Canvas canvas, SyllableRomanCache cache) {
        if (contentReveal <= 0f) {
            return;
        }
        final float contentNow = contentReveal;
        final float originX = textOriginX();
        final float originY = textOriginY();

        // 1. Compute base (unsung) alphas & colors 1:1 with drawBaseText()
        final int mainUnsungAlpha = Math.round(((unsungColor != 0 && !wordTimings.isEmpty())
                ? LyricsColors.UNSUNG_ALPHA * 255f : baseTextAlpha) * lineAlpha * contentNow);
        final float secondaryBaseAlpha = activeLine
                ? LyricsColors.ACTIVE_TRANSLATION_ALPHA * 255f
                : Color.alpha(LyricsColors.secondaryTextColor());
        final int transAlpha = Math.round(lineAlpha * secondaryBaseAlpha * contentNow * transReveal);
        final float readingBaseAlpha = activeLine
                ? (Settings.LYRICS_WORD_SYNC.get() && unsungColor != 0 && !wordTimings.isEmpty()
                        ? LyricsColors.UNSUNG_ALPHA * 255f
                        : LyricsColors.ACTIVE_READING_ALPHA * 255f)
                : Color.alpha(LyricsColors.secondaryTextColor());
        final int romaUnsungAlpha = Math.round(lineAlpha * readingBaseAlpha * contentNow * romaReveal);

        final int baseMainRgb = (getCurrentTextColor() | 0xFF000000) & 0x00FFFFFF;
        final int readingRgb = ((activeLine && secondaryActiveColor != 0
                ? secondaryActiveColor : LyricsColors.secondaryTextColor()) | 0xFF000000) & 0x00FFFFFF;

        final int mainUnsungColor = baseMainRgb | (Math.max(0, Math.min(255, mainUnsungAlpha)) << 24);
        final int romaUnsungColor = readingRgb | (Math.max(0, Math.min(255, romaUnsungAlpha)) << 24);
        final int transColor = readingRgb | (Math.max(0, Math.min(255, transAlpha)) << 24);

        final List<VisualCluster> clusters = cache.clusters;
        final ClusterStatics statics = cache.statics;

        // 2. Draw base (unsung) main clusters
        if (mainUnsungAlpha > 0) {
            for (int idx = 0; idx < clusters.size(); idx++) {
                final VisualCluster vc = clusters.get(idx);
                final TextPaint p = vc.subLayout.getPaint();
                p.setShader(null);
                p.setColor(mainUnsungColor);
                canvas.save();
                canvas.translate(originX + statics.baseX[idx], originY + statics.baseY[idx]);
                vc.subLayout.draw(canvas);
                canvas.restore();
            }
        }

        // 3. Draw base (unsung) romaji clusters
        if (romaReveal > 0f && romaUnsungAlpha > 0) {
            final RomanLineStatics[] romLines = cache.romanStatics.lines;
            for (int li = 0; li < romLines.length; li++) {
                final RomanLineStatics line = romLines[li];
                if (line == null) {
                    continue;
                }
                final List<RomanizedWordCluster> cs = line.clusters;
                final float topY = originY + line.y;
                for (int i = 0; i < cs.size(); i++) {
                    final RomanizedWordCluster rc = cs.get(i);
                    final TextPaint p = rc.subLayout.getPaint();
                    p.setShader(null);
                    p.setColor(romaUnsungColor);
                    canvas.save();
                    canvas.translate(originX + line.drawX[i], topY);
                    rc.subLayout.draw(canvas);
                    canvas.restore();
                }
            }
        }

        // 4. Draw translation layout
        if (cache.transLayout != null && transReveal > 0f && transAlpha > 0) {
            final TextPaint tp = cache.transLayout.getPaint();
            tp.setShader(null);
            tp.setColor(transColor);
            canvas.save();
            canvas.translate(originX, originY + cache.transTopY);
            cache.transLayout.draw(canvas);
            canvas.restore();
        }

        // 5. Word-sync progressive clipRect sweep (100% identical to onDraw)
        if (unsungColor == 0 || (wordTimings.isEmpty() && !allSung)) {
            return;
        }

        final int lineCount = cache.lineCount;
        if (lineMaxSungX == null || lineMaxSungX.length < lineCount) {
            lineMaxSungX = new float[lineCount];
        }
        Arrays.fill(lineMaxSungX, 0, lineCount, Float.NaN);

        final List<AlignedSyllable> alignedSyllables = cache.alignedSyllables;
        final int sylCount = alignedSyllables.size();
        final float[] sylProgress = cache.sylProgressScratch;
        int firstSungLine = -1;

        for (int i = 0; i < sylCount; i++) {
            final LyricsSpanBuilder.WordTiming timing = alignedSyllables.get(i).syllable;
            final float progress;
            if (allSung) {
                progress = 1f;
            } else if (positionMs >= timing.endMs()) {
                progress = 1f;
            } else if (positionMs <= timing.startMs()) {
                progress = 0f;
            } else {
                progress = (float) (positionMs - timing.startMs())
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

        final int mainSungAlpha = Math.round(255f * contentNow);
        final int mainSungColor = (LyricsColors.lineTextColor() & 0x00FFFFFF)
                | (Math.max(0, Math.min(255, mainSungAlpha)) << 24);

        // Draw sung main clusters clipped by lineMaxSungX[ln]
        for (int idx = 0; idx < clusters.size(); idx++) {
            final VisualCluster vc = clusters.get(idx);
            final int ln = vc.lineIdx;
            if (ln < 0 || ln >= lineCount) {
                continue;
            }
            final float edge = lineMaxSungX[ln];
            if (Float.isNaN(edge)) {
                continue;
            }
            final float cx = statics.baseX[idx];
            if (edge <= cx) {
                continue;
            }
            final TextPaint p = vc.subLayout.getPaint();
            p.setShader(null);
            p.setColor(mainSungColor);
            canvas.save();
            final float tx = originX + cx;
            final float ty = originY + statics.baseY[idx];
            if (edge < cx + vc.width) {
                canvas.clipRect(tx, ty - Dim.dp(4), originX + edge, ty + vc.subLayout.getHeight() + Dim.dp(4));
            }
            canvas.translate(tx, ty);
            vc.subLayout.draw(canvas);
            canvas.restore();
        }

        // Draw sung romaji clusters clipped by each syllable's exact progress
        if (romaReveal > 0f) {
            final int romaSungAlpha = Math.round(LyricsColors.ACTIVE_READING_ALPHA * 255f * contentNow * romaReveal);
            final int romaSungColor = readingRgb | (Math.max(0, Math.min(255, romaSungAlpha)) << 24);
            final RomanLineStatics[] romLines = cache.romanStatics.lines;

            for (int li = 0; li < romLines.length; li++) {
                final RomanLineStatics line = romLines[li];
                if (line == null) {
                    continue;
                }
                final List<RomanizedWordCluster> cs = line.clusters;
                final float topY = originY + line.y;
                for (int i = 0; i < cs.size(); i++) {
                    final RomanizedWordCluster rc = cs.get(i);
                    final float prog = (rc.wordIndex >= 0 && rc.wordIndex < sylCount)
                            ? sylProgress[rc.wordIndex]
                            : (allSung ? 1f : 0f);
                    if (prog <= 0f) {
                        continue;
                    }
                    final TextPaint p = rc.subLayout.getPaint();
                    p.setShader(null);
                    p.setColor(romaSungColor);
                    final float rx = originX + line.drawX[i];
                    canvas.save();
                    if (prog < 1f) {
                        canvas.clipRect(rx, topY - Dim.dp(2),
                                rx + rc.romanWidth * prog,
                                topY + rc.subLayout.getHeight() + Dim.dp(2));
                    }
                    canvas.translate(rx, topY);
                    rc.subLayout.draw(canvas);
                    canvas.restore();
                }
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
            final CharSequence text = layout.getText();
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