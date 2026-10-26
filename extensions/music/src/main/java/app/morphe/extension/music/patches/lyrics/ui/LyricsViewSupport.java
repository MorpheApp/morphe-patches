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
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ui.Dim;

/** All of it is static and reads no state of its own, which is why it lives outside the view. */
final class LyricsViewSupport {

    private static final float FOOTER_ALPHA = 0.6f;

    private static final long HIGHLIGHT_FADE_DURATION_MILLISECONDS = 320;

    private static final String LYRICS_SOURCE_KEY = "morphe_music_lyrics_source_label";

    private static final float FOOTER_TEXT_SIZE_SP = 16;

    // Scale factor for background vocal text size relative to main lyrics
    static final float BACKGROUND_TEXT_SIZE_FACTOR = 0.7f;
    private static final float SECTION_TAG_TEXT_SIZE_FACTOR = 0.45f;

    private static final float SECTION_DOT_INSET_FACTOR = 0.18f;

    static final float ACTIVE_LINE_SCALE = 1.0f;
    static final float INACTIVE_LINE_SCALE = 0.95f;

    private static final ArgbEvaluator BUTTON_COLOR_EVALUATOR = new ArgbEvaluator();

    // Cubic bezier easing for scroll animation
    static final TimeInterpolator LYPLUS_SCROLL_EASING =
            new PathInterpolator(0.41f, 0f, 0.12f, 0.99f);

    /** Standard Compose LinearOutSlowInEasing (0f, 0f, 0.2f, 1f). */
    static final TimeInterpolator LINEAR_OUT_SLOW_IN =
            new PathInterpolator(0f, 0f, 0.2f, 1f);

    /** Standard Compose FastOutSlowInEasing (0.4f, 0f, 0.2f, 1f). */
    static final TimeInterpolator FAST_OUT_SLOW_IN =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private static volatile Boolean cachedIsMiuiOrHyperOs = null;
    private static volatile Typeface cachedLyricsBoldTypeface = null;

    // MIUI and HyperOS have bug where bold font look thin for CJK characters so we detect it
    static boolean isMiuiOrHyperOs() {
        final Boolean cached = cachedIsMiuiOrHyperOs;
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            final String manufacturer = Build.MANUFACTURER != null
                    ? Build.MANUFACTURER.toLowerCase(Locale.ROOT) : "";
            final String brand = Build.BRAND != null
                    ? Build.BRAND.toLowerCase(Locale.ROOT) : "";
            if (manufacturer.contains("xiaomi") || brand.contains("xiaomi")
                    || brand.contains("redmi") || brand.contains("poco")) {
                result = true;
            } else {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Method get = sp.getMethod("get", String.class, String.class);
                String miuiVer = (String) get.invoke(null, "ro.miui.ui.version.name", "");
                String hyperVer = (String) get.invoke(null, "ro.mi.os.version.name", "");
                if ((miuiVer != null && !miuiVer.isEmpty())
                        || (hyperVer != null && !hyperVer.isEmpty())) {
                    result = true;
                }
            }
        } catch (Throwable ignored) {
        }
        cachedIsMiuiOrHyperOs = result;
        return result;
    }

    @NonNull
    static Typeface getLyricsBoldTypeface() {
        Typeface tf = cachedLyricsBoldTypeface;
        if (tf != null) {
            return tf;
        }
        final Typeface base = Typeface.create("sans-serif", Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tf = Typeface.create(base, 700, false);
        } else {
            tf = base;
        }
        cachedLyricsBoldTypeface = tf;
        return tf;
    }

    static void applyLyricsBoldPaint(@NonNull Paint paint) {
        paint.setTypeface(getLyricsBoldTypeface());
        paint.setFlags(paint.getFlags()
                | Paint.ANTI_ALIAS_FLAG
                | Paint.SUBPIXEL_TEXT_FLAG
                | Paint.LINEAR_TEXT_FLAG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                paint.setFontVariationSettings(isMiuiOrHyperOs() ? "'wght' 750" : "'wght' 700");
            } catch (Throwable ignored) {
            }
        }
        paint.setFakeBoldText(isMiuiOrHyperOs());
    }

    private static final Map<Integer, LyPlusSpring> SPRING_CACHE = new HashMap<>();

    // Spring easing model for accordion expand animation
    static final class LyPlusSpring {
        private final float peakMs;
        private final float tailMs;
        private final float peakValue;
        final float totalMs;

        LyPlusSpring(float peakMs) {
            this(peakMs, 0.78f, 1.5f);
        }

        LyPlusSpring(float peakMs, float zeta, float settleRatio) {
            this.peakMs = peakMs;
            this.tailMs = peakMs * settleRatio;
            this.totalMs = this.peakMs + this.tailMs;
            final double peakS = peakMs / 1000.0;
            final double omegaD = Math.PI / peakS;
            final double omega0 = omegaD / Math.sqrt(1.0 - zeta * zeta);
            final double decayRate = zeta * omega0;
            this.peakValue = (float) (1.0 + Math.exp(-decayRate * peakS));
        }

        float value(float tMs) {
            if (tMs <= 0f) {
                return 0f;
            }
            if (tMs >= totalMs) {
                return 1f;
            }
            if (tMs < peakMs) {
                return peakValue * LYPLUS_SCROLL_EASING.getInterpolation(tMs / peakMs);
            }
            final float u = (tMs - peakMs) / tailMs;
            return 1f + (peakValue - 1f) * 0.5f * (1f + (float) Math.cos(Math.PI * u));
        }
    }

    static synchronized LyPlusSpring lyPlusSpringFor(long durationMs) {
        final int clamped = (int) Math.max(10L, ((Math.max(1L, durationMs) + 5L) / 10L) * 10L);
        LyPlusSpring cached = SPRING_CACHE.get(clamped);
        if (cached == null) {
            cached = new LyPlusSpring((float) clamped);
            SPRING_CACHE.put(clamped, cached);
        }
        return cached;
    }

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
            if (child == text || child.getVisibility() == View.GONE) {
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
     */
    static List<LyricsSpanBuilder.WordTiming> wordSpansFor(
            LyricsSpanBuilder.BuildResult result, boolean only,
            List<LyricsSpanBuilder.WordTiming> lineTimings, int origStart) {
        if (!only) {
            if (result.showSyllableRomanization() && result.readingSpans() != null
                    && !result.readingSpans().isEmpty()) {
                return result.readingSpans();
            }
            return withReading(lineTimings, result, origStart);
        }
        final List<LyricsSpanBuilder.WordTiming> spans = result.readingSpans();
        return spans == null || spans.isEmpty() ? Collections.emptyList() : spans;
    }

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

    // Ease line opacity and scale when active line changes
    static void fadeTo(TextView lineView, float alpha) {
        if (!(lineView instanceof LyricsLineView view)) {
            return;
        }
        if (view.isSecondary()) {
        // Secondary lyrics opacity and scale are handled by animateSecondaryExpansion
            if (view.fadeAnimator != null) {
                view.fadeAnimator.cancel();
                view.fadeAnimator = null;
            }
            view.lineAlpha = 1f;
            view.fadeTargetAlpha = 1f;
            return;
        }
        if (view.fadeAnimator != null && Math.abs(view.fadeTargetAlpha - alpha) < 0.005f) {
            // Already animating toward this exact target; do not cancel and restart on every tick!
            return;
        }
        if (view.fadeAnimator != null) {
            view.fadeAnimator.cancel();
            view.fadeAnimator = null;
        }
        view.fadeTargetAlpha = alpha;
        final float targetScale = alpha >= 0.8f ? ACTIVE_LINE_SCALE : INACTIVE_LINE_SCALE;
        final float startAlpha = view.lineAlpha;
        final float startScale = view.lineScale;
        if (Math.abs(startAlpha - alpha) < 0.01f && Math.abs(startScale - targetScale) < 0.005f) {
            view.lineAlpha = alpha;
            view.lineScale = targetScale;
            applyLineScaleTransform(view, targetScale);
            view.invalidate();
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(HIGHLIGHT_FADE_DURATION_MILLISECONDS);
        animator.setInterpolator(FAST_OUT_SLOW_IN);
        animator.addUpdateListener(animation -> {
            final float t = (float) animation.getAnimatedValue();
            view.lineAlpha = startAlpha + (alpha - startAlpha) * t;
            view.lineScale = startScale + (targetScale - startScale) * t;
            applyLineScaleTransform(view, view.lineScale);
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

    static void applyLineScaleTransform(LyricsLineView view, float scale) {
        if (view.isSecondary()) {
            return;
        }
        final int w = view.getWidth();
        final int h = view.getHeight();
        if (w > 0) {
            final boolean isEnd = view.isRightAligned()
                    || (view.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END;
            view.setPivotX(isEnd ? w - view.getPaddingRight() : view.getPaddingLeft());
        }
        if (h > 0) {
            view.setPivotY(h * 0.5f);
        }
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    static void applyFooterStyle(TextView footer) {
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, FOOTER_TEXT_SIZE_SP);
        footer.setTextColor(LyricsColors.secondaryTextColor());
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

    static void fadeButtonColors(TextView button, int background, int foreground) {
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
        final Drawable[] icons = button.getCompoundDrawablesRelative();

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
                text += "\n" + String.format(str("morphe_music_lyrics_romanized_by_local"),
                        romanizedLocalDictionary);
            }
        }
        return text;
    }
}