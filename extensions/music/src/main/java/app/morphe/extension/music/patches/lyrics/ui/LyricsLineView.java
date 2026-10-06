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
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Layout;
import android.text.Spannable;
import android.text.style.RelativeSizeSpan;
import android.view.MotionEvent;
import android.widget.TextView;
import androidx.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import app.morphe.extension.shared.Logger;

final class LyricsLineView extends TextView {
    private static final int REVEAL_CONTENT = 0;
    private static final int REVEAL_TRANS = 1;
    private static final int REVEAL_ROMA = 2;

    private List<LyricsSpanBuilder.WordTiming> wordTimings = Collections.emptyList();
    long positionMs = Long.MIN_VALUE;
    boolean allSung = false;
    private int unsungColor;
    private int sungColor;
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

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        wordTimings = Collections.emptyList();
        positionMs = Long.MIN_VALUE;
        allSung = false;
        cachedLayout = null;
        cachedText = null;
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
    float lastTouchY;
    private final Paint layerPaint = new Paint();
    private int baseTextAlpha = 255;
    float lineAlpha = 1f;
    ValueAnimator fadeAnimator;

    float contentReveal = 1f;
    float transReveal = 1f;
    float romaReveal = 1f;
    private ValueAnimator contentRevealAnimator;
    private ValueAnimator transRevealAnimator;
    private ValueAnimator romaRevealAnimator;

    LyricsLineView(Context context) {
        super(context);
    }

    @Override
    public void setTextColor(int color) {
        baseTextAlpha = Color.alpha(color);
        super.setTextColor(color);
    }

    void setTranslationBounds(int start, int end) {
        transStart = start;
        transEnd = end;
    }

    void setRomanizationBounds(int start, int end) {
        romaStart = start;
        romaEnd = end;
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            lastTouchY = event.getY();
        }
        return super.onTouchEvent(event);
    }

    void setHighlight(List<LyricsSpanBuilder.WordTiming> timings, long posMs, boolean sung,
                      int unsungCol, int sungCol, int origStart) {
        if (this.positionMs == posMs && this.allSung == sung
                && this.unsungColor == unsungCol && this.sungColor == sungCol
                && this.originalTextStart == origStart
                && this.wordTimings == timings) {
            return;
        }
        final boolean structuralChange = this.wordTimings != timings
                || this.originalTextStart != origStart
                || this.unsungColor != unsungCol || this.sungColor != sungCol;
        this.wordTimings = timings != null ? timings : Collections.emptyList();
        this.positionMs = posMs;
        this.allSung = sung;
        this.unsungColor = unsungCol;
        this.sungColor = sungCol;
        this.originalTextStart = origStart;
        if (structuralChange) {
            cachedLayout = null;
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
        for (int i = 0; i < cachedWordCount; i++) {
            final LyricsSpanBuilder.WordTiming timing = timings.get(i);
            final int s = timing.start() + origStart;
            final int e = Math.min(timing.end() + origStart, text.length());
            if (s < 0 || s >= text.length() || s >= e) {
                cachedWordWrappedLine[i] = -1;
                continue;
            }
            final int line = layout.getLineForOffset(s);
            final float lead = layout.getPrimaryHorizontal(s);
            float trail = layout.getPrimaryHorizontal(e);
            final int endLine = layout.getLineForOffset(e);
            if (trail == lead || endLine != line) {
                final float width = paint.measureText(text, s, e);
                trail = layout.isRtlCharAt(s) ? lead - width : lead + width;
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
            cachedLineLeft[ln] = layout.getLineLeft(ln);
            cachedLineRight[ln] = layout.getLineRight(ln);
        }
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
        if (!(text instanceof Spannable spannable)) {
            return;
        }
        if (spannable.getSpans(start, textLength, RelativeSizeSpan.class).length == 0) {
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
        final Paint textPaint = getPaint();
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

    private void drawBaseText(Canvas canvas) {
        final Layout layout = getLayout();
        if (layout == null) {
            super.onDraw(canvas);
            return;
        }
        if (getWidth() <= 0 || getHeight() <= 0) {
            super.onDraw(canvas);
            return;
        }

        final int lineCount = layout.getLineCount();
        if (lineCount <= 0) {
            super.onDraw(canvas);
            return;
        }

        final float contentNow = contentReveal;
        final int mainAlpha = Math.round(((unsungColor != 0 && !wordTimings.isEmpty())
                ? LyricsColors.UNSUNG_ALPHA * 255f : baseTextAlpha) * lineAlpha * contentNow);
        final int secondaryAlpha = Math.round(
                Color.alpha(LyricsColors.secondaryTextColor()) * lineAlpha * contentNow);

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

        final boolean regionRevealing = transReveal < 1f || romaReveal < 1f;
        if (!hasSecondary || (secondaryAlpha == mainAlpha && !regionRevealing)) {
            drawTextRun(canvas, layout, 0, lineCount - 1, mainAlpha);
            return;
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
                    final float reveal = isRoma ? romaReveal : transReveal;
                    drawTextRun(canvas, layout, runStart, runLast,
                            Math.round(secondaryAlpha * reveal));
                } else {
                    drawTextRun(canvas, layout, runStart, runLast, mainAlpha);
                }
                if (!end) {
                    runStart = i;
                    runSecondary = nextSecondary;
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int width = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0) {
            return;
        }
        final CharSequence text = getText();
        if (text == null || text.length() == 0) {
            return;
        }
        if (LyricsFit.applyOrphanFit(this, width, text, fittedState)) {
            cachedLayout = null;
            cachedText = null;
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawBaseText(canvas);
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

            for (int i = 0; i < cachedWordCount; i++) {
                final LyricsSpanBuilder.WordTiming timing = wordTimings.get(i);
                final int s = timing.start() + origStart;
                final int e = Math.min(timing.end() + origStart, text.length());
                if (s < 0 || s >= text.length() || s >= e) {
                    continue;
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
                if (progress <= 0f) {
                    continue;
                }
                final int lineNum = cachedWordLine[i];
                if (firstSungLine < 0) {
                    firstSungLine = lineNum;
                }
                final float lead = cachedWordLeadX[i];
                final float edge = lead + (cachedWordTrailX[i] - lead) * progress;
                lineMaxSungX[lineNum] = extendSung(lineMaxSungX[lineNum], edge,
                        isRightToLeftLine(layout, lineNum));
                final int wrappedLine = cachedWordWrappedLine[i];
                if (wrappedLine >= 0 && wrappedLine < lineCount) {
                    final int charsOnLine0 = layout.getLineEnd(lineNum) - s;
                    final int charsOnLine1 = e - layout.getLineEnd(lineNum);
                    if (charsOnLine1 > 0) {
                        final float line1Progress = Math.max(0f,
                                (progress * (charsOnLine0 + charsOnLine1) - charsOnLine0)
                                        / (float) charsOnLine1);
                        if (line1Progress > 0f) {
                            final float line1Edge = layout.getLineLeft(wrappedLine)
                                    + (layout.getLineRight(wrappedLine)
                                            - layout.getLineLeft(wrappedLine))
                                            * line1Progress;
                            lineMaxSungX[wrappedLine] = extendSung(
                                    lineMaxSungX[wrappedLine], line1Edge,
                                    isRightToLeftLine(layout, wrappedLine));
                        }
                    }
                }
            }

            if (allSung && firstSungLine < 0 && origStart >= 0
                    && origStart < text.length()) {
                final int lastChar = originalEnd(text, origStart) - 1;
                final int firstLine = layout.getLineForOffset(origStart);
                final int lastLine = layout.getLineForOffset(Math.max(lastChar, origStart));
                for (int ln = firstLine; ln <= lastLine && ln < lineCount; ln++) {
                    lineMaxSungX[ln] = isRightToLeftLine(layout, ln)
                            ? cachedLineLeft[ln] : cachedLineRight[ln];
                }
            }

            final ColorFilter prevFilter = tp.getColorFilter();
            tp.setColorFilter(new PorterDuffColorFilter(
                    sungColor | 0xFF000000, PorterDuff.Mode.SRC_IN));

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
                canvas.save();
                final int clipTop = ln == 0 ? -textOriginY() : cachedLineTop[ln];
                if (isRightToLeftLine(layout, ln)) {
                    canvas.clipRect(edge, clipTop,
                            cachedLineRight[ln], cachedLineBottom[ln]);
                } else {
                    canvas.clipRect(cachedLineLeft[ln], clipTop,
                            edge, cachedLineBottom[ln]);
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

    private static boolean isRightToLeftLine(Layout layout, int line) {
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
