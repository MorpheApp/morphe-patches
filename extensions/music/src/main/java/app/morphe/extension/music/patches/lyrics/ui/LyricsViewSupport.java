/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ui.Dim;

/** All of it is static and reads no state of its own, which is why it lives outside the view. */
final class LyricsViewSupport {

    private static final float FOOTER_ALPHA = 0.6f;

    private static final long HIGHLIGHT_FADE_DURATION_MILLISECONDS = 200;

    private static final String LYRICS_SOURCE_KEY = "morphe_music_lyrics_source_label";

    private static final float FOOTER_TEXT_SIZE_SP = 16;

    private static final float BACKGROUND_TEXT_SIZE_FACTOR = 0.85f;
    private static final float SECTION_TAG_TEXT_SIZE_FACTOR = 0.45f;

    private static final float SECTION_DOT_INSET_FACTOR = 0.18f;

    private static final ArgbEvaluator BUTTON_COLOR_EVALUATOR = new ArgbEvaluator();

    private LyricsViewSupport() {
    }

    static int leadingRegionSize(Layout layout, int start, int end) {
        if (start != 0 || end <= start || end > layout.getText().length()) {
            return 0;
        }
        return layout.getLineBottom(layout.getLineForOffset(end - 1));
    }

    static int trailingRegionSize(Layout layout, int start, int end) {
        if (start <= 0 || end <= start || end > layout.getText().length()) {
            return 0;
        }
        return layout.getHeight() - layout.getLineTop(layout.getLineForOffset(start));
    }

    static int rowHeightOutsideText(View row, View text) {
        if (!(row instanceof ViewGroup group)) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);
            if (child == text) {
                continue;
            }
            int height = child.getHeight();
            final ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (height <= 0 && lp != null && lp.height > 0) {
                height = lp.height;
            }
            total += height;
            if (lp instanceof ViewGroup.MarginLayoutParams margins) {
                total += margins.topMargin + margins.bottomMargin;
            }
        }
        return total;
    }

    static void applySectionDotSize(@Nullable LinearLayout row) {
        if (row == null) {
            return;
        }
        final int size = scaledPixels(row.getContext(), LyricsPanelView.SECTION_DOT_SIZE_FACTOR);
        final int gap = scaledPixels(row.getContext(), LyricsPanelView.SECTION_DOT_GAP_FACTOR);
        for (int i = 0; i < row.getChildCount(); i++) {
            final View dot = row.getChildAt(i);
            if (dot == null) {
                continue;
            }
            final LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) dot.getLayoutParams();
            if (params == null) {
                continue;
            }
            params.width = size;
            params.height = size;
            params.setMarginStart(i == 0 ? 0 : gap);
            dot.setLayoutParams(params);
        }
    }

    /**
     * The words the karaoke pass paints for one line.
     *
     * <p>An only mode drops the original and measures its reading against the text it draws, so the
     * reading's words already sit on the {@code origStart} base and the original's are not in that text
     * at all; a plain mode keeps the original, which is the base {@link #withReading} moves the reading's
     * onto. Deciding by the reading alone would rob a plain mode of the words it paints.
     */
    static List<LyricsSpanBuilder.WordTiming> wordSpansFor(
            LyricsSpanBuilder.BuildResult result, boolean only,
            List<LyricsSpanBuilder.WordTiming> lineTimings, int origStart) {
        if (!only) {
            return withReading(lineTimings, result, origStart);
        }
        final List<LyricsSpanBuilder.WordTiming> spans = result.readingSpans();
        return spans == null || spans.isEmpty() ? Collections.emptyList() : spans;
    }

    /**
     * Puts the reading's words on the line's offset base: the view adds {@code originalTextStart} to
     * every offset it is given, and the reading sits elsewhere in the text, so its offsets move by the
     * difference.
     */
    static List<LyricsSpanBuilder.WordTiming> withReading(
            List<LyricsSpanBuilder.WordTiming> lineTimings,
            LyricsSpanBuilder.BuildResult result, int origStart) {
        final List<LyricsSpanBuilder.WordTiming> readingSpans = result.readingSpans();
        if (readingSpans == null || readingSpans.isEmpty() || result.romaStart() < 0) {
            return lineTimings;
        }
        final int shift = result.romaStart() - origStart;
        final List<LyricsSpanBuilder.WordTiming> combined =
                new ArrayList<>(lineTimings.size() + readingSpans.size());
        combined.addAll(lineTimings);
        for (LyricsSpanBuilder.WordTiming span : readingSpans) {
            combined.add(new LyricsSpanBuilder.WordTiming(
                    span.start() + shift, span.end() + shift, span.startMs(), span.endMs(), null));
        }
        return combined;
    }

    /** Eases the highlight between lines the way the built-in panel does. */
    static void fadeTo(TextView lineView, float alpha) {
        if (!(lineView instanceof LyricsLineView view)) {
            return;
        }
        if (view.fadeAnimator != null) {
            view.fadeAnimator.cancel();
            view.fadeAnimator = null;
        }
        if (Math.abs(view.lineAlpha - alpha) < 0.01f) {
            view.lineAlpha = alpha;
            view.invalidate();
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(view.lineAlpha, alpha);
        animator.setDuration(HIGHLIGHT_FADE_DURATION_MILLISECONDS);
        animator.addUpdateListener(animation -> {
            view.lineAlpha = (float) animation.getAnimatedValue();
            view.invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (view.fadeAnimator == animation) {
                    view.fadeAnimator = null;
                }
            }
        });
        view.fadeAnimator = animator;
        animator.start();
    }

    static void applyFooterStyle(TextView footer) {
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, FOOTER_TEXT_SIZE_SP);
        footer.setTextColor(LyricsColors.secondaryTextColor());
        // The secondary color alone is brighter than the app draws this line, which
        // sits dimmer than even the inactive lyrics above it.
        footer.setAlpha(FOOTER_ALPHA);
    }

    static int lineTextSizeSp(boolean isBackground, int textSize) {
        return isBackground
                ? Math.round(textSize * BACKGROUND_TEXT_SIZE_FACTOR)
                : textSize;
    }

    static int sectionTagTextSizeSp() {
        return Math.round(Settings.LYRICS_TEXT_SIZE.get() * SECTION_TAG_TEXT_SIZE_FACTOR);
    }

    static int scaledPixels(@NonNull Context context, float factor) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                Settings.LYRICS_TEXT_SIZE.get() * factor,
                context.getResources().getDisplayMetrics()));
    }

    static int sectionTagPaddingHorizontal(@NonNull Context context) {
        return scaledPixels(context, LyricsPanelView.SECTION_TAG_PADDING_HORIZONTAL_FACTOR);
    }

    static int sectionDotInset(@NonNull Context context) {
        return scaledPixels(context, SECTION_DOT_INSET_FACTOR);
    }

    static void fadeDualButtonOut(View button) {
        button.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f)
                .setDuration(LyricsPanelView.BUTTON_STATE_FADE_MILLISECONDS).start();
    }

    /** So the next merge starts from whole again. */
    static void resetDualButton(View button) {
        button.animate().cancel();
        button.setAlpha(1f);
        button.setScaleX(1f);
        button.setScaleY(1f);
    }

    static void fadeDualButtonIn(View button) {
        button.setAlpha(0f);
        button.setScaleX(0.6f);
        button.setScaleY(0.6f);
        button.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(LyricsPanelView.BUTTON_STATE_FADE_MILLISECONDS).start();
    }

    /**
     * Eases a button between its two colors: pill, label and icon change at once, so a single animator
     * drives all of them.
     */
    static void fadeButtonColors(TextView button, int background, int foreground) {
        // The tag is free on these buttons and keeps the running animator with its view.
        if (button.getTag() instanceof ValueAnimator running) {
            running.cancel();
        }

        GradientDrawable pill;
        if (button.getBackground() instanceof GradientDrawable existing) {
            pill = existing;
        } else {
            pill = new GradientDrawable();
            pill.setShape(GradientDrawable.RECTANGLE);
            pill.setCornerRadius(Dim.dp20);
            button.setBackground(pill);
        }

        final ColorStateList pillColor = pill.getColor();
        final int fromBackground = pillColor == null ? background : pillColor.getDefaultColor();
        final int fromForeground = button.getCurrentTextColor();
        // All of them: the dual pill's far twin would keep the inactive tint while it is up.
        final Drawable[] icons = button.getCompoundDrawablesRelative();

        // Nothing to ease from before the panel is on screen, or when nothing changed.
        if (!button.isAttachedToWindow()
                || (fromBackground == background && fromForeground == foreground)) {
            setButtonColors(button, pill, icons, background, foreground);
            return;
        }

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(LyricsPanelView.BUTTON_STATE_FADE_MILLISECONDS);
        animator.addUpdateListener(update -> {
            final float fraction = update.getAnimatedFraction();
            setButtonColors(button, pill, icons,
                    (int) BUTTON_COLOR_EVALUATOR.evaluate(fraction, fromBackground, background),
                    (int) BUTTON_COLOR_EVALUATOR.evaluate(fraction, fromForeground, foreground));
        });
        button.setTag(animator);
        animator.start();
    }

    static void setButtonColors(TextView button, GradientDrawable pill,
                                @NonNull Drawable[] icons, int background, int foreground) {
        pill.setColor(background);
        button.setTextColor(foreground);
        for (Drawable icon : icons) {
            if (icon != null) {
                icon.mutate().setTint(foreground);
            }
        }
        button.invalidate();
    }

    static boolean hasTranslation(@Nullable List<String> translated, List<LyricsLine> originals) {
        if (translated == null) {
            return false;
        }
        final int size = Math.min(translated.size(), originals.size());
        for (int i = 0; i < size; i++) {
            String text = translated.get(i);
            if (text != null && !text.isEmpty() && !text.equals(originals.get(i).text())) {
                return true;
            }
        }
        return false;
    }

    static String sourceText(String providerName, boolean translated,
                             boolean translatedFromGoogle, boolean translatedFromAI, @Nullable String translatedAiModel,
                             boolean romanized, boolean romanizedFromGoogle, boolean romanizedFromAI,
                             @Nullable String romanizedAiModel, LyricsPanelView.OnlyMode onlyMode,
                             @Nullable String romanizedLocalDictionary) {
        String text = String.format(str(LYRICS_SOURCE_KEY), providerName);

        if (onlyMode != LyricsPanelView.OnlyMode.ROMA) {
            if (translated && translatedFromGoogle) {
                text += "\n" + str("morphe_music_lyrics_translated_by_google");
            } else if (translated && translatedFromAI && translatedAiModel != null) {
                text += "\n" + String.format(str("morphe_music_lyrics_translated_by_ai"),
                        translatedAiModel);
            }
        }
        if (onlyMode != LyricsPanelView.OnlyMode.TRANS && romanized) {
            if (romanizedFromGoogle) {
                text += "\n" + str("morphe_music_lyrics_romanized_by_google");
            } else if (romanizedFromAI && romanizedAiModel != null) {
                text += "\n" + String.format(str("morphe_music_lyrics_romanized_by_ai"),
                        romanizedAiModel);
            } else if (romanizedLocalDictionary != null) {
                // Named and nothing more: the licences are in the About row.
                text += "\n" + String.format(str("morphe_music_lyrics_romanized_by_local"),
                        romanizedLocalDictionary);
            }
        }
        return text;
    }
}
