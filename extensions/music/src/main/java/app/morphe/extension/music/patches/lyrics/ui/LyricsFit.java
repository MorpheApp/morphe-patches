/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.content.Context;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.TextView;
import androidx.annotation.Nullable;
import java.util.Objects;

final class LyricsFit {
    private static final int ORPHAN_MAX_CHARS = 2;
    private static final int FIT_MAX_STEPS = 10;
    private static final float FIT_LETTER_SPACING_STEP_EM = 0.005f;
    private static final float FIT_MIN_LETTER_SPACING_EM = -0.05f;
    private static final float FIT_WIDTH_AXIS_STEP = 1.5f;
    private static final float FIT_MIN_WIDTH_AXIS = 85f;

private static boolean hasOrphanTail(Layout layout, CharSequence text) {
        final int length = text.length();
        int segmentStart = 0;
        while (segmentStart < length) {
            int segmentEnd = length;
            for (int i = segmentStart; i < length; i++) {
                if (text.charAt(i) == '\n') {
                    segmentEnd = i;
                    break;
                }
            }
            if (segmentEnd > segmentStart) {
                final int firstLine = layout.getLineForOffset(segmentStart);
                final int lastLine = layout.getLineForOffset(segmentEnd - 1);
                if (lastLine > firstLine) {
                    int start = layout.getLineStart(lastLine);
                    int end = Math.min(layout.getLineEnd(lastLine), segmentEnd);
                    while (start < end && Character.isWhitespace(text.charAt(start))) {
                        start++;
                    }
                    while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
                        end--;
                    }
                    final int tailLength = end - start;
                    if (tailLength > 0) {
                        if (tailLength <= ORPHAN_MAX_CHARS) {
                            return true;
                        }
                        boolean letterOrDigit = false;
                        for (int i = start; i < end; i++) {
                            if (Character.isLetterOrDigit(text.charAt(i))) {
                                letterOrDigit = true;
                                break;
                            }
                        }
                        if (!letterOrDigit) {
                            return true;
                        }
                    }
                }
            }
            segmentStart = segmentEnd + 1;
        }
        return false;
    }

    private static Layout buildCandidateLayout(TextView view, int sizePx, int width,
            CharSequence text, float letterSpacingEm, @Nullable String fontVariation) {
        final TextPaint paint = new TextPaint(view.getPaint());
        paint.setTextSize(sizePx);
        paint.setLetterSpacing(letterSpacingEm);
        paint.setFontVariationSettings(fontVariation);
        final Layout current = view.getLayout();
        final Layout.Alignment alignment = current != null
                ? current.getAlignment() : Layout.Alignment.ALIGN_NORMAL;
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(alignment)
                .setIncludePad(false)
                .setBreakStrategy(view.getBreakStrategy())
                .setHyphenationFrequency(view.getHyphenationFrequency())
                .build();
    }

    private static Boolean widthAxisSupport;

    private static boolean widthAxisSupported(TextPaint paint) {
        if (widthAxisSupport == null) {
            final TextPaint probe = new TextPaint(paint);
            probe.setTextSize(100f);
            final String[] sample = {"WAVEWIDTH", "MOMENT", "LONGERSONGTEXT"};
            float before = 0f;
            for (String part : sample) {
                before += probe.measureText(part);
            }
            probe.setFontVariationSettings("'wdth' 85");
            float after = 0f;
            for (String part : sample) {
                after += probe.measureText(part);
            }
            widthAxisSupport = after < before - 0.5f;
        }
        return widthAxisSupport;
    }

    private static final class CompressionFit {
        final float letterSpacingEm;
        @Nullable
        final String fontVariation;

        CompressionFit(float letterSpacingEm, @Nullable String fontVariation) {
            this.letterSpacingEm = letterSpacingEm;
            this.fontVariation = fontVariation;
        }
    }

    static final class FittedState {
        int width = -1;
        int textSizePx;
        float letterSpacingEm;
        @Nullable
        String fontVariation;
        @Nullable
        CharSequence text;
    }

    @Nullable
    private static CompressionFit findCompression(TextView view, int sizePx, int width,
            CharSequence text) {
        if (!widthAxisSupported(view.getPaint())) {
            return null;
        }
        for (int i = 1; i <= FIT_MAX_STEPS; i++) {
            final float spacing = Math.max(FIT_MIN_LETTER_SPACING_EM,
                    -i * FIT_LETTER_SPACING_STEP_EM);
            final String variation = "'wdth' "
                    + Math.max(FIT_MIN_WIDTH_AXIS, 100f - i * FIT_WIDTH_AXIS_STEP);
            if (!hasOrphanTail(buildCandidateLayout(view, sizePx, width, text, spacing,
                    variation), text)) {
                return new CompressionFit(spacing, variation);
            }
        }
        return null;
    }

    static boolean applyOrphanFit(TextView view, int width, CharSequence text,
            FittedState state) {
        final int sizePx = (int) view.getTextSize();
        if (width == state.width && sizePx == state.textSizePx
                && view.getLetterSpacing() == state.letterSpacingEm
                && Objects.equals(view.getFontVariationSettings(), state.fontVariation)) {
            return false;
        }
        Layout base = view.getLayout();
        if (base == null) {
            return false;
        }
        if (view.getLetterSpacing() != 0f || view.getFontVariationSettings() != null) {
            base = buildCandidateLayout(view, sizePx, width, text, 0f, null);
        }
        float spacing = 0f;
        String variation = null;
        if (hasOrphanTail(base, text)) {
            final CompressionFit fit = findCompression(view, sizePx, width, text);
            if (fit != null) {
                spacing = fit.letterSpacingEm;
                variation = fit.fontVariation;
            }
        }
        final boolean changed = view.getLetterSpacing() != spacing
                || !Objects.equals(view.getFontVariationSettings(), variation);
        if (view.getLetterSpacing() != spacing) {
            view.setLetterSpacing(spacing);
        }
        if (!Objects.equals(view.getFontVariationSettings(), variation)) {
            if (variation == null || !view.setFontVariationSettings(variation)) {
                view.setFontVariationSettings(null);
                variation = null;
            }
        }
        state.width = width;
        state.textSizePx = sizePx;
        state.letterSpacingEm = spacing;
        state.fontVariation = variation;
        state.text = text;
        return changed;
    }

    static final class FittingTextView extends TextView {
        private final FittedState fittedState = new FittedState();

        FittingTextView(Context context) {
            super(context);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            if (fittedState != null && !Objects.equals(fittedState.text, text)) {
                fittedState.width = -1;
            }
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
            if (applyOrphanFit(this, width, text, fittedState)) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }
    }
}
