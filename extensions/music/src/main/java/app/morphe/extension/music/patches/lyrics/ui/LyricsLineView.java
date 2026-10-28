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
import android.view.animation.PathInterpolator;
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

import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

final class LyricsLineView extends TextView {
    private static final int REVEAL_CONTENT = 0;
    private static final int REVEAL_TRANS = 1;
    private static final int REVEAL_ROMA = 2;

    /** A background vocal opens over this long when its lead line comes up, and closes faster. */
    private static final long SECONDARY_EXPAND_MILLISECONDS = 400;
    private static final long SECONDARY_COLLAPSE_MILLISECONDS = 320;
    /** A little ahead of the growth, so the vocal is readable before it settles. */
    private static final long SECONDARY_FADE_IN_MILLISECONDS = 380;
    /** A closed background vocal is drawn this much smaller, so it grows out of its lead line. */
    private static final float SECONDARY_CLOSED_SCALE = 0.8f;
    private static final TimeInterpolator SECONDARY_EXPAND_EASING =
            new PathInterpolator(0.41f, 0f, 0.12f, 0.99f);

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

    /** A background vocal, drawn in its lead line's row and only open around that line. */
    private boolean isSecondary;
    private int parentLineIndex = -1;
    /** Sung ahead of its lead line, so it sits above it and opens upwards. */
    private boolean bgBeforeMain;
    private boolean isRightAligned;
    /** How far a background vocal is open. Its measured height follows this. */
    private float secondaryFraction = 1f;
    private boolean secondaryExpanded = true;
    private int secondaryFullHeight;
    @Nullable
    private ValueAnimator secondaryAnimator;

    LyricsLineView(Context context) {
        super(context);
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

    boolean isRightAligned() {
        return isRightAligned;
    }

    /** The gap to the lead line, on whichever side of it this background vocal sits. */
    private void updateSecondaryMargin(int marginPx) {
        if (getLayoutParams() instanceof ViewGroup.MarginLayoutParams params) {
            params.topMargin = bgBeforeMain ? 0 : marginPx;
            params.bottomMargin = bgBeforeMain ? marginPx : 0;
        }
    }

    /**
     * Opens or closes a background vocal. A closed one is gone from the layout, so its lead line
     * takes only its own room until the vocal is sung.
     *
     * @param instant Skips the animation, for a row that was only just built.
     */
    void animateSecondaryExpansion(boolean expand, boolean instant) {
        // Called on every highlight tick, so one already on its way there is left to finish.
        if (!instant && expand == secondaryExpanded
                && (secondaryAnimator != null || secondaryFraction == (expand ? 1f : 0f))) {
            return;
        }
        // Cleared first, or the cancelled animator's end listener settles the old target.
        ValueAnimator running = secondaryAnimator;
        secondaryAnimator = null;
        if (running != null) {
            running.cancel();
        }
        secondaryExpanded = expand;
        if (instant) {
            settleSecondary(expand);
            return;
        }

        setVisibility(View.VISIBLE);
        final float startFraction = secondaryFraction;
        final float startAlpha = getAlpha();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        if (expand) {
            animator.setDuration(SECONDARY_EXPAND_MILLISECONDS);
            animator.setInterpolator(new LinearInterpolator());
        } else {
            animator.setDuration(SECONDARY_COLLAPSE_MILLISECONDS);
            animator.setInterpolator(LyricsViewSupport.FAST_OUT_SLOW_IN);
        }
        animator.addUpdateListener(animation -> {
            final float progress = (float) animation.getAnimatedValue();
            final float alpha;
            if (expand) {
                secondaryFraction = startFraction + (1f - startFraction)
                        * SECONDARY_EXPAND_EASING.getInterpolation(progress);
                final float fade = Math.min(1f, progress * SECONDARY_EXPAND_MILLISECONDS
                        / (float) SECONDARY_FADE_IN_MILLISECONDS);
                alpha = startAlpha + (LyricsColors.ACTIVE_BG_ALPHA - startAlpha)
                        * LyricsViewSupport.LINEAR_OUT_SLOW_IN.getInterpolation(fade);
            } else {
                secondaryFraction = startFraction * (1f - progress);
                alpha = startAlpha * (1f - progress);
            }
            applySecondaryFrame(alpha);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (secondaryAnimator == animation) {
                    secondaryAnimator = null;
                    settleSecondary(expand);
                }
            }
        });
        secondaryAnimator = animator;
        animator.start();
    }

    /** Grows out of the lead line's side while it opens, and shrinks back into it. */
    private void applySecondaryFrame(float alpha) {
        final int fullHeight = secondaryFullHeight > 0 ? secondaryFullHeight : getHeight();
        final float scale = SECONDARY_CLOSED_SCALE + (1f - SECONDARY_CLOSED_SCALE) * secondaryFraction;
        setPivotX(isRightAligned ? getWidth() : 0f);
        setPivotY(bgBeforeMain ? fullHeight : 0f);
        setScaleX(scale);
        setScaleY(scale);
        final float slide = Math.min(Dim.dp(18), Math.max(Dim.dp8, fullHeight * 0.35f));
        setTranslationY((bgBeforeMain ? 1f : -1f) * (1f - secondaryFraction) * slide);
        setAlpha(alpha);
        updateSecondaryMargin(Math.round(Dim.dp4 * secondaryFraction));
        requestLayout();
        invalidate();
    }

    private void settleSecondary(boolean expanded) {
        secondaryFraction = expanded ? 1f : 0f;
        final float scale = expanded ? 1f : SECONDARY_CLOSED_SCALE;
        setScaleX(scale);
        setScaleY(scale);
        setTranslationY(0f);
        setAlpha(expanded ? LyricsColors.ACTIVE_BG_ALPHA : 0f);
        updateSecondaryMargin(expanded ? Dim.dp4 : 0);
        setVisibility(expanded ? View.VISIBLE : View.GONE);
        requestLayout();
        invalidate();
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
    String getCopyTextForTouch(float touchY) {
        SyllableRomanCache syllables = syllableCache;
        if (syllables != null && syllables.hasSyllableRomanization) {
            final float y = touchY - textOriginY();
            if (syllables.transLayout != null && (syllables.mainTopY > 0
                    ? y < syllables.mainTopY : y >= syllables.transTopY)) {
                return syllables.transText;
            }
            return syllables.mainText != null ? syllables.mainText.trim() : null;
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
                    // Only one of the two ranges is set on a dual line, so this run is the reading's
                    // exactly when the reading's range is the one present.
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
        if (isSecondary && secondaryFraction < 0.999f) {
            setMeasuredDimension(getMeasuredWidth(),
                    Math.max(1, Math.round(secondaryFullHeight * secondaryFraction)));
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
                } else if (positionMs >= timing.endMs()) {
                    progress = 1f;
                } else if (positionMs <= timing.startMs()) {
                    progress = 0f;
                } else {
                    progress = (float) (positionMs - timing.startMs())
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

            // Smallness follows the run beside it, never the words it holds: a dual line hands those to the
            // reading wherever the swap puts it, so a main run without them is still the main run.
            final int readingColor = (activeLine && secondaryActiveColor != 0
                    ? secondaryActiveColor : LyricsColors.secondaryTextColor()) | 0xFF000000;
            final int lyricColor = LyricsColors.lineTextColor() | 0xFF000000;
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
    private record AlignedSyllable(LyricsSpanBuilder.WordTiming syllable, int startOffset, int endOffset) {
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
        /** Where the translation and the lyric start; with the swap on the translation goes first. */
        final int transTopY;
        final int mainTopY;
        final int totalHeightPx;

        SyllableRomanCache(int widthPx, float fontSizePx, boolean hasSyllableRomanization,
                @Nullable String mainText, int lineCount, List<AlignedSyllable> alignedSyllables,
                int extraRomanHeightPx, List<VisualCluster> clusters, ClusterStatics statics,
                RomanLineStatics[] romanLines, int[] sylLine,
                float[] sylShiftedLeadX, float[] sylShiftedTrailX,
                @Nullable String transText, @Nullable StaticLayout transLayout,
                int transTopY, int mainTopY, int totalHeightPx) {
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
            this.sylLine = sylLine;
            this.sylShiftedLeadX = sylShiftedLeadX;
            this.sylShiftedTrailX = sylShiftedTrailX;
            this.sylProgressScratch = new float[alignedSyllables.size()];
            this.transText = transText;
            this.transLayout = transLayout;
            this.transTopY = transTopY;
            this.mainTopY = mainTopY;
            this.totalHeightPx = totalHeightPx;
        }

        /** Remembers that a line has nothing to draw this way, so it is not worked out every frame. */
        static SyllableRomanCache none(int widthPx, float fontSizePx) {
            return new SyllableRomanCache(widthPx, fontSizePx, false, null, 0,
                    Collections.emptyList(), 0, Collections.emptyList(),
                    new ClusterStatics(new float[0], new float[0]),
                    new RomanLineStatics[0], new int[0], new float[0], new float[0],
                    null, null, 0, 0, 0);
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
        Layout.Alignment mainAlign = LyricsSpanBuilder.resolveAlignment(false, isRightAligned);

        TextPaint mainPaint = new TextPaint(getPaint());
        mainPaint.setTextSize(fontSizePx);
        LyricsViewSupport.applyLyricsBoldPaint(mainPaint);
        mainPaint.setLetterSpacing(LyricsViewSupport.lineLetterSpacing(
                rawMainText, Settings.LYRICS_TEXT_SIZE.get()));

        TextPaint romPaint = new TextPaint(mainPaint);
        romPaint.setTextSize(fontSizePx * LyricsSpanBuilder.TRANSLATION_RELATIVE_SIZE);
        LyricsViewSupport.applyLyricsBoldPaint(romPaint);
        romPaint.setLetterSpacing(0f);

        String formattedMainText = buildFormattedMainText(
                rawMainText, wordTimings, mainPaint, romPaint, widthPx, fontSizePx);

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
                buildRomanizedWordClusters(alignedSyllables, formattedMainText, mainLayout, romPaint);
        if (romanClusters.isEmpty()) {
            return SyllableRomanCache.none(widthPx, fontSizePx);
        }

        // Each lyric line makes room under it for its readings.
        final float lineExtraHeight = fontSizePx * 1.05f;
        final int bottomMargin = Math.round(fontSizePx * 0.25f);
        final int extraRomanHeightPx = Math.round(lineCount * lineExtraHeight) + bottomMargin;

        float[] charXMap = buildCharXMap(
                formattedMainText, mainLayout, lineCount, lineIsRtl, lineStarts);
        float[] romanStartShift = buildRomanStartShift(
                formattedMainText, charXMap, mainLayout, lineCount,
                alignedSyllables, romanClusters, mainAlign);

        List<VisualCluster> clusters = buildVisualClusters(
                formattedMainText, mainLayout, alignedSyllables, lineIsRtl, lineBaselines, mainPaint);
        ClusterStatics statics = buildClusterStatics(
                clusters, romanStartShift, lineExtraHeight);
        List<RomanizedWordCluster>[] romanLines =
                groupRomanClustersByLine(romanClusters, lineCount);
        RomanLineStatics[] romanStatics = buildRomanStatics(
                romanLines, charXMap, romanStartShift, lineBaselines, lineBottoms,
                lineLefts, lineStarts, lineCount, fontSizePx, lineExtraHeight, totalW);

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
        final int mainSectionH = mainLayout.getHeight() + extraRomanHeightPx;
        int transTopY = 0;
        int mainTopY = 0;
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
                        LyricsSpanBuilder.resolveAlignment(isTransRtl, isRightAligned);
                transLayout = StaticLayout.Builder.obtain(
                                transText, 0, transText.length(), transPaint, widthPx)
                        .setAlignment(transAlign)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1.18f)
                        .build();
                final int transH = transLayout.getHeight();
                if (transStart < origStart) {
                    mainTopY = transH + Dim.dp4;
                } else {
                    transTopY = mainSectionH + Dim.dp4;
                }
                totalH = mainSectionH + Dim.dp4 + transH;
            }
        }

        return new SyllableRomanCache(widthPx, fontSizePx, true, rawMainText, lineCount,
                alignedSyllables, extraRomanHeightPx, clusters, statics, romanStatics,
                sylLine, sylShiftedLeadX, sylShiftedTrailX,
                transText, transLayout, transTopY, mainTopY, totalH);
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
            int widthPx,
            float fontSizePx) {
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
                buildRomanizedWordClusters(alignedSyllables, mainText, unconstrained, romPaint);
        if (romanClusters.isEmpty()) {
            return mainText;
        }

        Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2);
        for (RomanizedWordCluster rc : romanClusters) {
            clusterMap.put(rc.wordIndex(), rc);
        }

        float[] sylSlotWidths = new float[alignedSyllables.size()];
        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            RomanizedWordCluster cluster = clusterMap.get(sylIdx);
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
            sylSlotWidths[sylIdx] = Math.max(slotMainWidth, romWidth);
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
            TextPaint romPaint) {
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
                StaticLayout subLayout = buildSingleLineLayout(text, subPaint);
                final float romW = singleLineWidth(text, subPaint, subLayout);
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
            Layout.Alignment alignment) {
        float[] shiftByWordIndex = new float[romanClusters.size() + alignedSyllables.size() + 1];
        float[] lineTotalExtra = new float[lineCount];
        if (romanClusters.isEmpty()) {
            return shiftByWordIndex;
        }

        Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2);
        for (RomanizedWordCluster rc : romanClusters) {
            clusterMap.put(rc.wordIndex(), rc);
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
            if (cluster == null) {
                continue;
            }
            final int sylEndLine = textLayoutResult.getLineForOffset(
                    Math.max(0, Math.min(syl.endOffset(), maxCharIdx)));
            final int lineEndOff = textLayoutResult.getLineVisibleEnd(li);

            // Include any trailing space before the next syllable on the same line so we compare
            // (word + space) in mainLayout against (romaji + space) in cluster.romanWidth().
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
            final float romWidth = cluster.romanWidth();

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
            boolean[] lineIsRtl,
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
            final float baseLineBottom = li < lineBottoms.length
                    ? lineBottoms[li] : (lineBaselines[li] + fontSizePx * 0.25f);
            final float y = baseLineBottom + li * lineExtraHeight + fontSizePx * 0.08f;
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

        // The unsung colours, as drawBaseText picks them.
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
            RomanLineStatics[] romLines = cache.romanLines;
            for (int li = 0; li < romLines.length; li++) {
                RomanLineStatics line = romLines[li];
                if (line == null) {
                    continue;
                }
                List<RomanizedWordCluster> cs = line.clusters();
                final float topY = mainY + line.y();
                for (int i = 0; i < cs.size(); i++) {
                    RomanizedWordCluster rc = cs.get(i);
                    TextPaint p = rc.subLayout().getPaint();
                    p.setShader(null);
                    p.setColor(romaUnsungColor);
                    canvas.save();
                    canvas.translate(originX + line.drawX()[i], topY);
                    rc.subLayout().draw(canvas);
                    canvas.restore();
                }
            }
        }

        if (cache.transLayout != null && transReveal > 0f && transAlpha > 0) {
            TextPaint tp = cache.transLayout.getPaint();
            tp.setShader(null);
            tp.setColor(transColor);
            canvas.save();
            canvas.translate(originX, originY + cache.transTopY);
            cache.transLayout.draw(canvas);
            canvas.restore();
        }

        // The sweep, as onDraw runs it.
        if (unsungColor == 0 || (wordTimings.isEmpty() && !allSung)) {
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

        for (int i = 0; i < sylCount; i++) {
            LyricsSpanBuilder.WordTiming timing = alignedSyllables.get(i).syllable();
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
        if (romaReveal > 0f) {
            final int romaSungAlpha = Math.round(LyricsColors.ACTIVE_READING_ALPHA * 255f * contentNow * romaReveal);
            final int romaSungColor = readingRgb | (Math.max(0, Math.min(255, romaSungAlpha)) << 24);
            RomanLineStatics[] romLines = cache.romanLines;

            for (int li = 0; li < romLines.length; li++) {
                RomanLineStatics line = romLines[li];
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
                    p.setColor(romaSungColor);
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
