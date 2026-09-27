/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsFileSaver;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.LyricsPanelInstaller;
import app.morphe.extension.music.patches.lyrics.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.LyricsTranslator;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.ViewAnimations;

/**
 * Third party lyrics, drawn over the content of the lyrics engagement panel.
 *
 * <p>Hides itself when there are no lyrics to show, which leaves the built-in
 * lyrics visible underneath.
 */
public final class LyricsPanelView extends FrameLayout implements LyricsManager.Listener {

    private static final float INACTIVE_LINE_ALPHA = 0.35f;

    private static final float FOOTER_ALPHA = 0.6f;

    private static final long HIGHLIGHT_FADE_DURATION_MILLISECONDS = 200;

    private static final long SECONDARY_REVEAL_MILLISECONDS = 250;

    /** Fade length when the panel appears over the built-in content. */
    private static final long OVERLAY_FADE_DURATION_MILLISECONDS = 150;

    /** How long auto scrolling stays off after the user touches the panel. */
    private static final long MANUAL_SCROLL_PAUSE_MILLISECONDS = 5000;

    private static final long OVERLAY_CACHE_TTL_MS = 300;

    private static final long MIN_TICK_INTERVAL_MS = 50;

    private static final int SCROLL_OFFSET_FRACTION = 3;
    private static final int SCROLL_INSTANT_THRESHOLD_FACTOR = 2;
    private static final int SCROLL_SMOOTH_THRESHOLD_FACTOR = 4;

    /** Own string, because the app string {@code lyrics_source} exists in English only. */
    private static final String LYRICS_SOURCE_KEY = "morphe_music_lyrics_source_label";

    /** Size of the source line under the lyrics. */
    private static final float FOOTER_TEXT_SIZE_SP = 16;

    private static final float BUTTON_TEXT_SIZE_SP = 14;

    /** Color the app uses for primary text. */
    private static final String APP_PRIMARY_TEXT_COLOR = "ytm_text_color_primary";

    /** Color the app uses for secondary text, applied to the translation. */
    private static final String APP_SECONDARY_TEXT_COLOR = "ytm_text_color_secondary";

    /** Background the app uses for the pill buttons under its own lyrics. */
    private static final String APP_BUTTON_BACKGROUND_COLOR = "ytm_color_white_at_10pct";

    /** Active (feature on) button background: pure white. */
    private static final int ACTIVE_BUTTON_BG_COLOR = 0xFFFFFFFF;
    /** How long a button takes to cross between its inactive and active colors. */
    private static final long BUTTON_STATE_FADE_MILLISECONDS = 150;
    private static final ArgbEvaluator BUTTON_COLOR_EVALUATOR = new ArgbEvaluator();

    /** Alpha channel for the unsung (not-yet-sung) word color. */
    private static final float UNSUNG_ALPHA = 0.4f;

    /** Icons of the buttons the app draws under its own lyrics. */
    private static final String APP_TRANSLATE_ICON = "yt_outline_experimental_translate_vd_theme_24";

    /** Icon for the romanize button, showing the pronunciation above each line. */
    private static final String APP_ROMANIZE_ICON = "yt_outline_experimental_waveform_vd_theme_24";

    private static final String REFRESH_ICON = "ic_mtrl_arrow_circle";

    /** Own icon, because the app ships no copy icon of its own. */
    private static final String COPY_ICON = "morphe_yt_copy_bold";

    /** Translation/romanization size relative to the lyrics line it belongs to. */
    private static final float TRANSLATION_RELATIVE_SIZE = 0.7f;
    /** Per-word romanization size relative to the lyrics line it belongs to. */
    private static final float ROMAJI_RELATIVE_SIZE = 0.7f;

    private static final int ORPHAN_MAX_CHARS = 2;
    private static final int FIT_MAX_STEPS = 10;
    private static final float FIT_LETTER_SPACING_STEP_EM = 0.005f;
    private static final float FIT_MIN_LETTER_SPACING_EM = -0.05f;
    private static final float FIT_WIDTH_AXIS_STEP = 1.5f;
    private static final float FIT_MIN_WIDTH_AXIS = 85f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateTranslateLabelRunnable = this::updateTranslateLabel;
    private final Runnable updateRomanizeLabelRunnable = this::updateRomanizeLabel;

    private final ScrollView scrollView;
    private final LinearLayout linesContainer;
    private final TextView creditView;
    private final TextView footerView;
    @Nullable
    private final TextView translateView;
    @Nullable
    private final TextView romanizeView;
    /** Copy button, or {@code null} when hidden by settings. */
    @Nullable
    private final TextView copyView;
    @Nullable
    private final TextView refreshView;
    private final LinearLayout footerContainer;
    private final LinearLayout buttonRow;
    private final ProgressBar progressBar;

    /** One translated line per lyrics line, or {@code null} when showing the original only. */
    @Nullable
    private List<String> translatedLines;

    /** One romanized line per lyrics line, or {@code null} when not shown. */
    @Nullable
    private List<LyricsLine> romanizedLines;
    private boolean romanizedFromGoogle;
    /** When true, the translation shown came from Google (not the provider's native one). */
    private boolean translatedFromGoogle;
    private boolean translatedFromAI;
    private boolean romanizedFromAI;
    @Nullable
    private String translatedAiModel;
    @Nullable
    private String romanizedAiModel;
    /** When true, romanization is carried per-word on each {@link Word} (rendered above each word). */
    private boolean perWordRomaji;
    /** URL to the song page on the provider's platform, opened when the source label is clicked. */
    @Nullable
    private String currentSourceUrl;
    /** When true, the next LOADED state was triggered by a refresh/cycle action. */
    private boolean refreshInProgress;
    @Nullable
    private TrackInfo lastKnownTrack;
    private final Runnable refreshLabelResetRunnable = this::updateRefreshLabel;
    private boolean translateInProgress;
    private boolean romanizeInProgress;
    private int translateRequestId;
    private int romanizeRequestId;

    private enum OnlyMode { NONE, TRANS, ROMA }

    private OnlyMode onlyMode = OnlyMode.NONE;

    private final List<TextView> lineViews = new ArrayList<>();

    /** Wrapper holding the optional romanization line above each line of lyrics. */
    private final List<View> lineRows = new ArrayList<>();

    private final List<List<WordTiming>> lineWordSpans = new ArrayList<>();
    private final List<Integer> lineOriginalStarts = new ArrayList<>();
    private final List<ForegroundColorSpan> lineUnsungSpans = new ArrayList<>();

    private static final ExecutorService LINE_BUILDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private volatile int buildGeneration;

    private boolean wholeFadeNextBuild;
    private boolean contentOutPending;
    private OnlyMode displayedOnlyMode = OnlyMode.NONE;
    private OnlyMode builtOnlyMode = OnlyMode.NONE;
    private ValueAnimator rowHeightAnimator;

    private int pendingHideAnchorIndex = -1;
    private float pendingHideAnchorScreenY;
    private int scrollAnchorRowIndex = -1;
    private int scrollAnchorBaseRowTop;
    private int scrollAnchorChildTop;
    private float scrollAnchorScreenY;
    private int scrollAnchorBaseContentHeight;
    private ViewTreeObserver.OnPreDrawListener pendingAnchorScroll;

    private boolean growthAnchorValid;
    private int growthAnchorIndex = -1;
    private float growthAnchorContainerY;

    private int lastWordLineIndex = -1;

    private int lastOverlayIndex = -1;
    /** Whether hide-played/unplayed was active on the last {@link #applyLineOverlay} pass. */
    private boolean lastOverlayHideActive;

    private int pendingOldWordLineIndex = -1;

    private boolean seekPending;

    /** Floating ruler for temporary offset adjustment via horizontal swipe. */
    private OffsetRulerView offsetRulerView;
    private GestureDetector offsetGestureDetector;
    private boolean isOffsetAdjusting;
    private float offsetSwipeStartX;
    private int offsetSwipeStartMs;
    private final Runnable hideOffsetRunnable = this::hideOffsetRuler;

    private record WordTiming(int start, int end, long startMs, long endMs,
            @Nullable String romaji) {
    }

    private record BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
            int transStart, int transEnd, int romaStart, int romaEnd) {
    }

    private static final class RomajiSpan extends ReplacementSpan {
        private final String romaji;
        private final int color;
        private final float relativeSize;
        private final Paint romajiPaint = new Paint();
        private final Paint.FontMetrics romajiFm = new Paint.FontMetrics();
        private final Paint.FontMetrics wordFm = new Paint.FontMetrics();
        private float cachedRomajiWidth = -1f;
        private float cachedWordWidth = -1f;
        private boolean wordFmCached;

        RomajiSpan(String romaji, int color, float relativeSize) {
            this.romaji = romaji;
            this.color = color;
            this.relativeSize = relativeSize;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            cachedWordWidth = (int) Math.ceil(paint.measureText(text, start, end));
            wordFmCached = false;
            if (fm != null) {
                romajiPaint.set(paint);
                romajiPaint.setTextSize(paint.getTextSize() * relativeSize);
                romajiPaint.getFontMetrics(romajiFm);
                final int romajiHeight = (int) Math.ceil(romajiFm.descent - romajiFm.ascent);
                final int gap = Math.max(1, (int) (paint.getTextSize() * 0.1f));
                final int reserve = romajiHeight + gap;
                fm.ascent -= reserve;
                fm.top -= reserve;
            }
            return (int) cachedWordWidth;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, @NonNull Paint paint) {
            romajiPaint.set(paint);
            romajiPaint.setTextSize(paint.getTextSize() * relativeSize);
            romajiPaint.setColor(color);

            if (cachedRomajiWidth < 0f) {
                cachedRomajiWidth = romajiPaint.measureText(romaji);
            }
            final float wordWidth = cachedWordWidth >= 0f
                    ? cachedWordWidth : paint.measureText(text, start, end);
            final float romajiX = x + Math.max(0f, (wordWidth - cachedRomajiWidth) / 2f);

            if (!wordFmCached) {
                romajiPaint.getFontMetrics(romajiFm);
                paint.getFontMetrics(wordFm);
                wordFmCached = true;
            }
            final int gap = Math.max(1, (int) (paint.getTextSize() * 0.1f));
            final float romajiBaseline = y + wordFm.ascent - romajiFm.descent - gap;

            canvas.drawText(romaji, romajiX, romajiBaseline, romajiPaint);
            canvas.drawText(text, start, end, x, y, paint);
        }
    }

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

    private static final class FittedState {
        int width = -1;
        int textSizePx;
        float letterSpacingEm;
        @Nullable
        String fontVariation;
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

    private static boolean applyOrphanFit(TextView view, int width, CharSequence text,
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
        return changed;
    }

    private static final class FittingTextView extends TextView {
        private final FittedState fittedState = new FittedState();

        FittingTextView(Context context) {
            super(context);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            if (fittedState != null) {
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

    private static final class LyricsLineView extends TextView {
        private static final int REVEAL_CONTENT = 0;
        private static final int REVEAL_TRANS = 1;
        private static final int REVEAL_ROMA = 2;

        private List<WordTiming> wordTimings = Collections.emptyList();
        private long positionMs = Long.MIN_VALUE;
        private boolean allSung = false;
        private int unsungColor;
        private int sungColor;
        private int originalTextStart;
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
            if (fittedState != null) {
                fittedState.width = -1;
            }
            invalidate();
        }

        private final FittedState fittedState = new FittedState();

        private int transStart = -1;
        private int transEnd = -1;
        private int romaStart = -1;
        private int romaEnd = -1;
        private float lastTouchY;
        private final Paint layerPaint = new Paint();
        private int baseTextAlpha = 255;
        private float lineAlpha = 1f;
        private ValueAnimator fadeAnimator;

        private float contentReveal = 1f;
        private float transReveal = 1f;
        private float romaReveal = 1f;
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
            animator.setDuration(SECONDARY_REVEAL_MILLISECONDS);
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

        void setHighlight(List<WordTiming> timings, long posMs, boolean sung,
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
            for (int i = 0; i < cachedWordCount; i++) {
                final WordTiming timing = timings.get(i);
                final int s = timing.start() + origStart;
                final int e = Math.min(timing.end() + origStart, text.length());
                if (s >= text.length() || s >= e) {
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
                    ? UNSUNG_ALPHA * 255f : baseTextAlpha) * lineAlpha * contentNow);
            final int secondaryAlpha = Math.round(
                    Color.alpha(secondaryTextColor()) * lineAlpha * contentNow);

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
            if (applyOrphanFit(this, width, text, fittedState)) {
                cachedLayout = null;
                cachedText = null;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawBaseText(canvas);
            if (contentReveal <= 0f
                    || unsungColor == 0 || (wordTimings.isEmpty() && !allSung)) {
                return;
            }

            Layout layout = getLayout();
            if (layout == null) {
                return;
            }
            CharSequence text = getText();
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
                final WordTiming timing = wordTimings.get(i);
                final int s = timing.start() + origStart;
                final int e = Math.min(timing.end() + origStart, text.length());
                if (s >= text.length() || s >= e) {
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
                    if (charsOnLine0 + charsOnLine1 > 0) {
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

            if (allSung && firstSungLine < 0 && origStart < text.length()) {
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

    @Nullable
    private Lyrics lyrics;
    private Lyrics lastBuiltLyrics;

    private int highlightedIndex = -1;

    private boolean wordSyncWasEnabled = true;

    /** Whether this panel should currently cover the built-in content. */
    private boolean overlayVisible;

    private boolean cachedLyricsPanelOpen;
    private boolean cachedOtherPanelOpen;
    private long overlayCacheUptimeMs;

    /** Built-in views hidden by this panel, so that only what was hidden is shown again. */
    private final Set<View> hiddenSiblings = new HashSet<>();

    /** Suppresses auto scrolling for a while after the user scrolls manually. */
    private long userScrollUntilUptimeMs;

    /** Last scroll target to avoid redundant smoothScrollTo calls. */
    private int lastScrollTarget = -1;

    private long cachedTickInterval = 16;
    @Nullable
    private String cachedRefreshRateSetting;
    private boolean cachedWordSyncTick = true;

    private boolean saveInProgress;

    private long computeTickInterval() {
        String rate = SharedYouTubeSettings.APP_REFRESH_RATE.get();
        final boolean wordSyncTick = onlyMode == OnlyMode.NONE
                && Settings.LYRICS_WORD_SYNC.get();
        if (Objects.equals(rate, cachedRefreshRateSetting) && cachedWordSyncTick == wordSyncTick) {
            return cachedTickInterval;
        }
        cachedRefreshRateSetting = rate;
        cachedWordSyncTick = wordSyncTick;
        long interval;
        if ("DEFAULT".equals(rate)) {
            float deviceRate = 0f;
            try {
                android.view.Display display = getDisplay();
                if (display != null) {
                    deviceRate = display.getRefreshRate();
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not get display refresh rate", ex);
            }
            interval = deviceRate > 0f ? Math.round(1000f / deviceRate) : 16;
        } else {
            try {
                int fps = Integer.parseInt(rate);
                interval = fps > 0 ? Math.round(1000f / fps) : 16;
            } catch (NumberFormatException ex) {
                Logger.printDebug(() -> "Could not parse refresh rate setting", ex);
                interval = 16;
            }
        }
        if (!wordSyncTick) {
            interval = Math.max(interval, MIN_TICK_INTERVAL_MS);
        }
        cachedTickInterval = interval;
        return interval;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                if (onlyMode != OnlyMode.NONE) {
                    updateOnlyHighlight();
                } else {
                    updateHighlight();
                    updateWordSync(LyricsManager.getInstance().getPositionMs());
                }

                // The app restores its own panel content asynchronously, and switching
                // to another engagement panel gives no lyrics state change to react to,
                // so the wanted state is reapplied on every tick rather than on changes.
                // The cached answer is kept here, since ticks carry no news of their own.
                applyOverlayVisibility();
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not update lyrics panel view", ex);
            }
            handler.postDelayed(this, computeTickInterval());
        }
    };

    public LyricsPanelView(Context context) {
        super(context);

        final int horizontalPadding = Dim.dp32;
        final int verticalPadding = Dim.dp16;

        linesContainer = new LinearLayout(context);
        linesContainer.setOrientation(LinearLayout.VERTICAL);
        linesContainer.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        footerView = new FittingTextView(context);
        applyFooterStyle(footerView);
        footerView.setVisibility(GONE);

        // Same order as the buttons the app draws under its own lyrics.
        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        LayoutTransition buttonTransition = new LayoutTransition();
        buttonTransition.enableTransitionType(LayoutTransition.CHANGING);
        buttonTransition.setDuration(LayoutTransition.CHANGING, BUTTON_STATE_FADE_MILLISECONDS);
        // Without this the panel around the row animates along with the buttons.
        buttonTransition.setAnimateParentHierarchy(false);
        buttonRow.setLayoutTransition(buttonTransition);
        buttonRow.setVisibility(GONE);

        if (Settings.LYRICS_SHOW_COPY_BUTTON.get()) {
            copyView = new TextView(context);
            applyButtonStyle(copyView, COPY_ICON);
            copyView.setOnClickListener(view -> onCopyClicked());
            copyView.setOnLongClickListener(view -> {
                onCopyLongPressed();
                return true;
            });
            buttonRow.addView(copyView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            copyView = null;
        }

        if (Settings.LYRICS_SHOW_TRANSLATE_BUTTON.get()) {
            translateView = new TextView(context);
            applyButtonStyle(translateView, APP_TRANSLATE_ICON);
            translateView.setOnClickListener(view -> onTranslateClicked());
            translateView.setOnLongClickListener(view -> {
                onTranslateLongPressed();
                return true;
            });
            LinearLayout.LayoutParams translateParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            translateParams.setMarginStart(Dim.dp12);
            buttonRow.addView(translateView, translateParams);
        } else {
            translateView = null;
        }

        if (Settings.LYRICS_SHOW_ROMANIZE_BUTTON.get()) {
            romanizeView = new TextView(context);
            applyButtonStyle(romanizeView, APP_ROMANIZE_ICON);
            romanizeView.setOnClickListener(view -> onRomanizeClicked());
            romanizeView.setOnLongClickListener(view -> {
                onRomanizeLongPressed();
                return true;
            });
            LinearLayout.LayoutParams romanizeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            romanizeParams.setMarginStart(Dim.dp12);
            buttonRow.addView(romanizeView, romanizeParams);
        } else {
            romanizeView = null;
        }

        if (Settings.LYRICS_SHOW_REFRESH_BUTTON.get()) {
            refreshView = new TextView(context);
            applyButtonStyle(refreshView, REFRESH_ICON);
            refreshView.setOnClickListener(view -> onRefreshClicked());
            refreshView.setOnLongClickListener(view -> {
                onRefreshLongPressed();
                return true;
            });
            LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            refreshParams.setMarginStart(Dim.dp12);
            buttonRow.addView(refreshView, refreshParams);
        } else {
            refreshView = null;
        }

        // The source line lives in a container of its own, so that lyrics lines can be
        // inserted before it without depending on how many views it holds.
        footerContainer = new LinearLayout(context);
        footerContainer.setOrientation(LinearLayout.VERTICAL);
        // The bottom padding keeps the last lines clear of the pinned buttons.
        footerContainer.setPadding(0, Dim.dp16, 0, Dim.dp(200));

        creditView = new FittingTextView(context);
        applyFooterStyle(creditView);
        creditView.setVisibility(GONE);
        creditView.setOnLongClickListener(v -> {
            CharSequence text = creditView.getText();
            //noinspection SizeReplaceableByIsEmpty
            if (text != null && text.length() > 0) {
                ClipboardManager clipboard = (ClipboardManager) getContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("songwriters", text.toString()));
                    Utils.showToastShort(str("morphe_music_lyrics_copied"));
                }
            }
            return true;
        });
        LinearLayout.LayoutParams creditParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        creditParams.bottomMargin = Dim.dp16;
        footerContainer.addView(creditView, creditParams);

        footerContainer.addView(footerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        linesContainer.addView(footerContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(linesContainer, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        addView(scrollView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(context);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(GONE);
        addView(progressBar, new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // Added last, and outside the scroll view, so the buttons stay pinned at the
        // bottom while the lyrics scroll behind them, the way the app does it.
        LayoutParams buttonRowParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        buttonRowParams.bottomMargin = Dim.dp40;
        addView(buttonRow, buttonRowParams);

        offsetRulerView = new OffsetRulerView(context);
        LayoutParams rulerParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rulerParams.bottomMargin = Dim.dp8;
        addView(offsetRulerView, rulerParams);

        offsetGestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onScroll(@NonNull MotionEvent e1, @Nullable MotionEvent e2,
                            float distanceX, float distanceY) {
                        if (e2 == null) {
                            return false;
                        }
                        if (isOffsetAdjusting) {
                            float dx = e2.getX() - offsetSwipeStartX;
                            int deltaMs = Math.round(-dx / getResources().getDisplayMetrics().density) * 10;
                            int newMs = Math.max(-20000, Math.min(20000, offsetSwipeStartMs + deltaMs));
                            LyricsManager.getInstance().setTemporaryOffsetMs(newMs);
                            offsetRulerView.setOffsetMs(newMs);
                            scheduleHideOffsetRuler();
                            return true;
                        }
                        float density = getResources().getDisplayMetrics().density;
                        float touchY = e1.getY();
                        boolean inButtonArea = buttonRow.getVisibility() == VISIBLE
                                && touchY >= buttonRow.getTop() - Dim.dp8
                                && touchY <= buttonRow.getBottom() + Dim.dp8;
                        if (inButtonArea
                                && Math.abs(distanceX) > Math.abs(distanceY) * 1.5
                                && Math.abs(distanceX) > 15 * density) {
                            isOffsetAdjusting = true;
                            offsetSwipeStartX = e1.getX();
                            offsetSwipeStartMs = LyricsManager.getInstance().getTemporaryOffsetMs();
                            showOffsetRuler();
                            return true;
                        }
                        return false;
                    }
                });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // Any touch counts as manual interaction, so auto scrolling backs off
        // instead of fighting the user. The event itself is left untouched.
        userScrollUntilUptimeMs = SystemClock.uptimeMillis() + MANUAL_SCROLL_PAUSE_MILLISECONDS;
        lastScrollTarget = -1;
        offsetGestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            isOffsetAdjusting = false;
        }
        return isOffsetAdjusting || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isOffsetAdjusting) {
            offsetGestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                isOffsetAdjusting = false;
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void showOffsetRuler() {
        handler.removeCallbacks(hideOffsetRunnable);
        offsetRulerView.setOffsetMs(LyricsManager.getInstance().getTemporaryOffsetMs());
        if (offsetRulerView.getVisibility() != VISIBLE) {
            offsetRulerView.setAlpha(0f);
            offsetRulerView.setVisibility(VISIBLE);
            offsetRulerView.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void hideOffsetRuler() {
        if (offsetRulerView.getVisibility() != VISIBLE) return;
        offsetRulerView.animate().alpha(0f).setDuration(200).withEndAction(() ->
                offsetRulerView.setVisibility(GONE)).start();
    }

    private void scheduleHideOffsetRuler() {
        handler.removeCallbacks(hideOffsetRunnable);
        handler.postDelayed(hideOffsetRunnable, 2000);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LyricsManager.getInstance().addListener(this);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setKeepScreenOn(false);
        LyricsManager.getInstance().removeListener(this);
        handler.removeCallbacksAndMessages(null);
        LyricsManager.getInstance().resetTemporaryOffsetMs();
        offsetRulerView.setVisibility(GONE);
        if (!LyricsPanelInstaller.isOtherPanelForeground()) {
            restoreHiddenSiblings();
        }
    }

    @Override
    public void onLyricsChanged(LyricsManager.State state, @Nullable Lyrics newLyrics) {
        try {
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (track != lastKnownTrack) {
                lastKnownTrack = track;
                refreshInProgress = false;
                handler.removeCallbacks(refreshLabelResetRunnable);
                updateRefreshLabel();
            }
            lyrics = newLyrics;
            highlightedIndex = -1;
            pendingOldWordLineIndex = -1;
            seekPending = false;
            userScrollUntilUptimeMs = 0;
            handler.removeCallbacks(updateTranslateLabelRunnable);
            handler.removeCallbacks(updateRomanizeLabelRunnable);
            LyricsManager.getInstance().resetTemporaryOffsetMs();
            hideOffsetRuler();
            isOffsetAdjusting = false;
            translatedLines = null;
            romanizedLines = null;
            romanizedFromGoogle = false;
            translatedFromGoogle = false;
            translatedFromAI = false;
            romanizedFromAI = false;
            translatedAiModel = null;
            romanizedAiModel = null;
            perWordRomaji = false;
            // Invalidate any in-flight normal/ONLY callbacks for the previous track.
            translateRequestId++;
            romanizeRequestId++;
            translateInProgress = false;
            romanizeInProgress = false;
            if (Settings.LYRICS_TRANSLATE_ONLY.get() && Settings.LYRICS_ROMANIZE_ONLY.get()) {
                Settings.LYRICS_ROMANIZE_ONLY.save(false);
            }
            onlyMode = OnlyMode.NONE;
            updateFooter();

            switch (state) {
                case LOADING:
                    showLoading();
                    setOverlayVisible(true);
                    break;
                case LOADED:
                    if (newLyrics == null || newLyrics.isEmpty()) {
                        setOverlayVisible(false);
                        if (refreshInProgress) {
                            refreshInProgress = false;
                            updateRefreshLabel();
                        }
                    } else {
                        showLyrics(newLyrics);
                        setOverlayVisible(true);
                        if (Settings.LYRICS_TRANSLATE_ONLY.get()) {
                            requestTranslateOnly(newLyrics);
                        } else if (Settings.LYRICS_TRANSLATE.get()) {
                            onTranslateClicked();
                        }
                        if (Settings.LYRICS_ROMANIZE_ONLY.get()) {
                            requestRomanizeOnly(newLyrics);
                        } else if (Settings.LYRICS_ROMANIZE.get()) {
                            onRomanizeClicked();
                        }
                        if (refreshInProgress) {
                            refreshInProgress = false;
                            setButtonLabel(refreshView, str("morphe_music_lyrics_refreshed"), true);
                            handler.removeCallbacks(refreshLabelResetRunnable);
                            handler.postDelayed(refreshLabelResetRunnable, 1500);
                        }
                    }
                    break;
                case NOT_FOUND:
                case ERROR:
                case IDLE:
                default:
                    clearLines();
                    setOverlayVisible(false);
                    if (refreshInProgress) {
                        refreshInProgress = false;
                        updateRefreshLabel();
                    }
                    break;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLyricsChanged failure", ex);
        }
    }

    /** Hides the built-in content along with showing this panel, so the two texts never overlap. */
    private void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
        applyOverlayVisibility();
    }

    /**
     * Reapplies the wanted state, because reopening the panel makes the app restore
     * its own content, and opening another engagement panel makes it take the same
     * container over, neither of which is a lyrics state change to react to.
     *
     * <p>Called when the panel on screen has just changed, so the cached answer from
     * before the change would keep the built-in lyrics visible until it expires.
     */
    public void syncOverlay() {
        overlayCacheUptimeMs = 0;
        applyOverlayVisibility();
    }

    private void applyOverlayVisibility() {
        final long now = SystemClock.uptimeMillis();
        if (now - overlayCacheUptimeMs > OVERLAY_CACHE_TTL_MS) {
            overlayCacheUptimeMs = now;
            cachedLyricsPanelOpen = LyricsPanelInstaller.isLyricsPanelOpen();
            cachedOtherPanelOpen = LyricsPanelInstaller.isOtherPanelForeground();
        }

        if (cachedOtherPanelOpen) {
            if (getParent() instanceof ViewGroup parent) {
                parent.removeView(this);
            }
            setVisibility(GONE);
            return;
        }

        final boolean visible = overlayVisible && cachedLyricsPanelOpen;
        final boolean wasVisible = getVisibility() == VISIBLE;
        setVisibility(visible ? VISIBLE : GONE);

        if (visible && !wasVisible) {
            animate().cancel();
            setAlpha(0f);
            animate().alpha(1f).setDuration(OVERLAY_FADE_DURATION_MILLISECONDS).start();
        }

        if (!(getParent() instanceof ViewGroup parent)) {
            return;
        }

        if (!visible) {
            restoreHiddenSiblings();
            return;
        }

        for (int i = 0; i < parent.getChildCount(); i++) {
            View sibling = parent.getChildAt(i);
            if (sibling == this
                    || sibling.getVisibility() != VISIBLE
                    || hiddenSiblings.contains(sibling)) {
                continue;
            }
            sibling.setVisibility(GONE);
            hiddenSiblings.add(sibling);
        }
    }

    /**
     * Shows the built-in views this panel hid, and only those, so that views the app
     * hides on its own and the content of a panel that took the container over are
     * left the way the app left them.
     */
    private void restoreHiddenSiblings() {
        for (View sibling : hiddenSiblings) {
            sibling.setVisibility(VISIBLE);
        }
        hiddenSiblings.clear();
    }

    private void showLoading() {
        clearLines();
        footerContainer.setVisibility(GONE);
        buttonRow.setVisibility(GONE);
        scrollView.setVisibility(GONE);
        progressBar.setVisibility(VISIBLE);
    }

    private void showLyrics(Lyrics newLyrics) {
        if (onlyMode != displayedOnlyMode && !contentOutPending && !lineViews.isEmpty()) {
            contentOutPending = true;
            wholeFadeNextBuild = true;
            displayedOnlyMode = onlyMode;
            final int outGeneration = buildGeneration;
            for (TextView lineView : lineViews) {
                if (lineView instanceof LyricsLineView view) {
                    view.animateContentOut();
                }
            }
            handler.postDelayed(() -> {
                contentOutPending = false;
                if (outGeneration == buildGeneration && lyrics != null) {
                    showLyrics(lyrics);
                }
            }, SECONDARY_REVEAL_MILLISECONDS);
            return;
        }
        displayedOnlyMode = onlyMode;
        growthAnchorValid = false;
        final boolean preserveHidePosition = pendingHideAnchorIndex >= 0;
        final int preserveHideIndex = pendingHideAnchorIndex;
        final float preserveHideScreenY = pendingHideAnchorScreenY;
        pendingHideAnchorIndex = -1;
        pendingHideAnchorScreenY = 0f;
        final boolean sameContent = !lineRows.isEmpty() && lastBuiltLyrics == newLyrics;
        final int prevCount = sameContent
                ? Math.min(lineViews.size(), newLyrics.lines().size()) : 0;
        final List<TextView> prevViews = sameContent
                ? new ArrayList<>(lineViews) : Collections.emptyList();
        final int[] prevHeights = new int[prevCount];
        for (int i = 0; i < prevCount; i++) {
            prevHeights[i] = i < lineRows.size() ? lineRows.get(i).getHeight() : 0;
        }
        final List<List<WordTiming>> prevSpans = sameContent
                ? new ArrayList<>(lineWordSpans) : Collections.emptyList();
        final int prevHighlighted = highlightedIndex;
        lastBuiltLyrics = newLyrics;
        clearLines();
        colorCacheValid = false;
        progressBar.setVisibility(GONE);
        scrollView.setVisibility(VISIBLE);

        final boolean contentInBuild =
                onlyMode != OnlyMode.NONE || wholeFadeNextBuild;
        wholeFadeNextBuild = false;

        Context context = getContext();
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int foregroundColor = lineTextColor();
        final boolean tapToSeek = newLyrics.synced() && Settings.LYRICS_TAP_TO_SEEK.get();

        final int generation = buildGeneration;
        final OnlyMode onlySnap = onlyMode;
        builtOnlyMode = onlySnap;
        final List<LyricsLine> romanizedSnap = romanizedLines;
        final List<String> translatedSnap = translatedLines;
        final boolean perWordRomajiSnap = perWordRomaji;

        for (int i = 0; i < newLyrics.lines().size(); i++) {
            LyricsLine line = newLyrics.lines().get(i);
            final LyricsLineView prevView = i < prevCount
                    && prevViews.get(i) instanceof LyricsLineView pv ? pv : null;
            final boolean reusePrevious = prevView != null && !contentInBuild;
            final List<WordTiming> gapTimings = reusePrevious && i < prevSpans.size()
                    ? prevSpans.get(i) : Collections.emptyList();
            lineWordSpans.add(reusePrevious ? gapTimings : new ArrayList<>());

            LyricsLineView lineView = new LyricsLineView(context);
            // Plain text first so the panel paints immediately; the karaoke spans are added on a
            // background thread (see the LINE_BUILDER_EXECUTOR pass below). A hide rebuild keeps
            // the surviving regions so its first frame matches the shrunken layout exactly, and a
            // same-content rebuild reuses the previous row so toggles never blink existing text.
            int gapOrigStart = 0;
            if (preserveHidePosition) {
                BuildResult result = buildLineText(line, gapTimings, i);
                lineView.setText(result.text());
                lineView.setTranslationBounds(result.transStart(), result.transEnd());
                lineView.setRomanizationBounds(result.romaStart(), result.romaEnd());
                gapOrigStart = computeOriginalTextStart(line, i, onlySnap, romanizedSnap,
                        translatedSnap, perWordRomajiSnap);
            } else if (reusePrevious) {
                lineView.setText(prevView.getText());
                lineView.setTranslationBounds(prevView.transStart, prevView.transEnd);
                lineView.setRomanizationBounds(prevView.romaStart, prevView.romaEnd);
                lineView.transReveal = prevView.transReveal;
                lineView.romaReveal = prevView.romaReveal;
                gapOrigStart = prevView.originalTextStart;
            } else {
                lineView.setText(line.text().isEmpty() ? "♪" : line.text());
            }
            lineOriginalStarts.add(gapOrigStart);
            if (reusePrevious) {
                lineView.setHighlight(gapTimings, prevView.positionMs, prevView.allSung,
                        unsungWordColor(), lineTextColor(), gapOrigStart);
            }
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
            lineView.setTextColor(foregroundColor);
            lineView.setAlpha(1f);
            lineView.lineAlpha = newLyrics.synced()
                    ? (reusePrevious && i == prevHighlighted ? 1f : INACTIVE_LINE_ALPHA)
                    : 1f;
            lineView.contentReveal = contentInBuild ? 0f : 1f;
            lineView.setPadding(0, Dim.dp8, 0, Dim.dp8);
            lineView.setIncludeFontPadding(false);
            lineView.getPaint().setElegantTextHeight(true);
            lineView.setTypeface(null, Typeface.BOLD);

            if (newLyrics.synced()) {
                lineView.setTextColor(unsungWordColor());
            }

            if (tapToSeek) {
                lineView.setOnClickListener(view -> {
                    final long videoLength = VideoInformation.getVideoLength();
                    long target = line.startTimeMs()
                            + Settings.LYRICS_OFFSET_MS.get()
                            + LyricsManager.getInstance().getTemporaryOffsetMs();
                    if (target < 0) {
                        target = 0;
                    } else if (videoLength > 0 && target > videoLength) {
                        target = videoLength;
                    }
                    final long seekTime = target;
                    if (!VideoInformation.seekTo(seekTime)) {
                        Logger.printDebug(() -> "Seek to lyrics line failed: " + seekTime);
                    }
                    userScrollUntilUptimeMs = 0;
                    seekPending = true;
                });
            }

            lineView.setOnLongClickListener(v -> {
                LyricsLineView lv = (LyricsLineView) v;
                String textToCopy = lv.getCopyTextForTouch(lv.lastTouchY);
                if (textToCopy == null || textToCopy.isEmpty()) {
                    textToCopy = onlyMode != OnlyMode.NONE
                            ? String.valueOf(lv.getText())
                            : line.text();
                }
                if (textToCopy.isEmpty()) {
                    return false;
                }
                ClipboardManager clipboard = (ClipboardManager) getContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(
                            ClipData.newPlainText("lyric_line", textToCopy));
                    Utils.showToastShort(str("morphe_music_lyrics_copied"));
                }
                return true;
            });

            LinearLayout lineRow = new LinearLayout(context);
            lineRow.setOrientation(LinearLayout.VERTICAL);

            if (line.isDuet()) {
                lineView.setGravity(Gravity.END | Gravity.TOP);
            } else {
                lineView.setGravity(Gravity.START | Gravity.TOP);
            }

            lineRow.addView(lineView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            // Inserted before the last child, because the footer was added first
            // and has to stay below the lyrics.
            linesContainer.addView(lineRow, linesContainer.getChildCount() - 1,
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
            lineViews.add(lineView);
            lineRows.add(lineRow);
            lineUnsungSpans.add(null);
        }

        LINE_BUILDER_EXECUTOR.execute(() -> {
            try {
                final int lineCount = newLyrics.lines().size();
                List<List<WordTiming>> allTimings = new ArrayList<>(lineCount);
                List<Integer> allOrigStarts = new ArrayList<>(lineCount);
                for (int i = 0; i < lineCount; i++) {
                    if (generation != buildGeneration || lyrics != newLyrics) {
                        return;
                    }
                    allTimings.add(computeWordTimings(newLyrics.lines(), i));
                    allOrigStarts.add(computeOriginalTextStart(newLyrics.lines().get(i), i,
                            onlySnap, romanizedSnap, translatedSnap, perWordRomajiSnap));
                }
                handler.post(() -> {
                    if (generation != buildGeneration || lyrics != newLyrics) {
                        return;
                    }
                    final boolean only = onlyMode != OnlyMode.NONE;
                    final int count = Math.min(allTimings.size(), lineViews.size());
                    final int[] plainHeights = new int[count];
                    final boolean[] freshTrans = new boolean[count];
                    final boolean[] freshRoma = new boolean[count];
                    for (int i = 0; i < count; i++) {
                        final int h = lineRows.get(i).getHeight();
                        plainHeights[i] = h > 1 ? h : (i < prevHeights.length ? prevHeights[i] : h);
                        final TextView prevRaw = i < prevViews.size() ? prevViews.get(i) : null;
                        final LyricsLineView prevView = prevRaw instanceof LyricsLineView pv
                                ? pv : null;
                        freshTrans[i] = !(prevView != null && prevView.transStart >= 0
                                && prevView.transReveal >= 1f);
                        freshRoma[i] = !(prevView != null && prevView.romaStart >= 0
                                && prevView.romaReveal >= 1f);
                    }
                    linesContainer.suppressLayout(true);
                    try {
                        for (int i = 0; i < count; i++) {
                            lineWordSpans.set(i, only ? Collections.emptyList() : allTimings.get(i));
                            lineOriginalStarts.set(i, only ? 0 : allOrigStarts.get(i));
                            TextView tv = lineViews.get(i);
                            if (i < newLyrics.lines().size()) {
                                BuildResult result = buildLineText(newLyrics.lines().get(i),
                                        allTimings.get(i), i);
                                tv.setText(result.text());
                                lineUnsungSpans.set(i, result.unsungSpan());
                                if (tv instanceof LyricsLineView lineView) {
                                    lineView.setTranslationBounds(result.transStart(), result.transEnd());
                                    lineView.setRomanizationBounds(result.romaStart(), result.romaEnd());
                                    if (!contentInBuild && !preserveHidePosition) {
                                        if (result.transStart() >= 0 && freshTrans[i]) {
                                            lineView.transReveal = 0f;
                                        }
                                        if (result.romaStart() >= 0 && freshRoma[i]) {
                                            lineView.romaReveal = 0f;
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        linesContainer.suppressLayout(false);
                    }
                    final List<RowResize> resizes = new ArrayList<>();
                    if (!contentInBuild) {
                        for (int i = 0; i < count; i++) {
                            final int oldH = plainHeights[i];
                            if (oldH <= 1 || lineRows.get(i).getWidth() <= 0
                                    || !(lineViews.get(i) instanceof LyricsLineView view)) {
                                continue;
                            }
                            final TextView tv = lineViews.get(i);
                            tv.measure(View.MeasureSpec.makeMeasureSpec(
                                            lineRows.get(i).getWidth(), View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0,
                                            View.MeasureSpec.UNSPECIFIED));
                            final int newH = tv.getMeasuredHeight();
                            if (Math.abs(newH - oldH) <= 1) {
                                continue;
                            }
                            final Layout layout = tv.getLayout();
                            if (layout == null) {
                                continue;
                            }
                            final int leading = leadingRegionSize(layout,
                                    view.transStart, view.transEnd)
                                    + leadingRegionSize(layout, view.romaStart, view.romaEnd);
                            final int trailing = trailingRegionSize(layout,
                                    view.transStart, view.transEnd)
                                    + trailingRegionSize(layout, view.romaStart, view.romaEnd);
                            resizes.add(new RowResize(i, lineRows.get(i), tv, oldH, newH,
                                    leading, trailing));
                        }
                    }
                    final Runnable revealTask = () -> {
                        for (int i = 0; i < count && i < lineViews.size(); i++) {
                            if (!(lineViews.get(i) instanceof LyricsLineView view)) {
                                continue;
                            }
                            if (contentInBuild) {
                                view.startContentIn();
                            } else if (!preserveHidePosition) {
                                if (view.transStart >= 0 && freshTrans[i]) {
                                    view.startRegionIn(true);
                                }
                                if (view.romaStart >= 0 && freshRoma[i]) {
                                    view.startRegionIn(false);
                                }
                            }
                        }
                    };
                    if (resizes.isEmpty()) {
                        revealTask.run();
                    } else {
                        startRowHeightAnimation(resizes, true, revealTask);
                    }
                });
            } catch (Exception ex) {
                Logger.printDebug(() -> "line builder pass failure", ex);
                handler.post(() -> {
                    if (generation != buildGeneration || lyrics != newLyrics) {
                        return;
                    }
                    for (TextView lineView : lineViews) {
                        if (lineView instanceof LyricsLineView view
                                && view.contentReveal <= 0f) {
                            view.contentReveal = 1f;
                            view.invalidate();
                        }
                    }
                });
            }
        });

        currentSourceUrl = newLyrics.sourceUrl();
        updateFooter();
        footerView.setOnClickListener(view -> onSourceClicked());

        List<String> songwriters = newLyrics.songwriters();
        if (songwriters != null && !songwriters.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < songwriters.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(songwriters.get(i));
            }
            creditView.setText(sb.toString());
            creditView.setVisibility(Settings.LYRICS_HIDE_INFO.get() ? GONE : VISIBLE);
        } else {
            creditView.setVisibility(GONE);
        }
        footerContainer.setVisibility(VISIBLE);
        footerView.setVisibility(VISIBLE);
        buttonRow.setVisibility(VISIBLE);
        updateTranslateLabel();
        updateRomanizeLabel();

        final boolean hidePlayed = Settings.LYRICS_HIDE_PLAYED.get();
        final boolean hideUnplayed = Settings.LYRICS_HIDE_UNPLAYED.get();
        if (hidePlayed || hideUnplayed) {
            final long pos = LyricsManager.getInstance().getPositionMs();
            final int initialIndex = newLyrics.indexForPosition(pos, -1);
            for (int i = 0; i < lineRows.size(); i++) {
                if (hidePlayed && i < initialIndex) {
                    lineRows.get(i).setVisibility(GONE);
                } else if (hideUnplayed && i > initialIndex) {
                    lineRows.get(i).setVisibility(GONE);
                }
            }
            lastOverlayIndex = initialIndex;
            lastOverlayHideActive = true;
        } else {
            lastOverlayIndex = -1;
            lastOverlayHideActive = false;
        }

        if (newLyrics.synced() && !lineViews.isEmpty()) {
            userScrollUntilUptimeMs = SystemClock.uptimeMillis()
                    + SECONDARY_REVEAL_MILLISECONDS + 100;
            if (onlyMode != OnlyMode.NONE) {
                updateOnlyHighlight();
            } else {
                updateHighlight();
            }
            lastScrollTarget = -1;
            final int preserveIndex = preserveHidePosition ? preserveHideIndex : -1;
            final float preserveScreenY = preserveHideScreenY;
            final int preserveGeneration = buildGeneration;
            linesContainer.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            if (preserveGeneration != buildGeneration
                                    || lyrics == null || !lyrics.synced()
                                    || lineViews.isEmpty() || lineRows.isEmpty()
                                    || seekPending) {
                                linesContainer.getViewTreeObserver()
                                        .removeOnPreDrawListener(this);
                                return true;
                            }
                            if (rowHeightAnimator != null) {
                                return true;
                            }
                            linesContainer.getViewTreeObserver()
                                    .removeOnPreDrawListener(this);
                            final int index;
                            if (highlightedIndex >= 0
                                    && highlightedIndex < lineViews.size()
                                    && highlightedIndex < lineRows.size()) {
                                index = highlightedIndex;
                            } else if (preserveIndex >= 0
                                    && preserveIndex < lineViews.size()
                                    && preserveIndex < lineRows.size()) {
                                index = preserveIndex;
                            } else {
                                index = 0;
                            }
                            final boolean preserved = index == preserveIndex;
                            final int maxScroll = Math.max(0,
                                    linesContainer.getHeight() - scrollView.getHeight());
                            final TextView lineView = lineViews.get(index);
                            final int mainOffset = mainLineOffset(lineView);
                            final int y = Math.max(0, Math.min(maxScroll,
                                    lineRows.get(index).getTop()
                                            + lineView.getTop()
                                            + mainOffset
                                            - (preserved
                                                    ? Math.round(preserveScreenY)
                                                    : scrollView.getHeight()
                                                            / SCROLL_OFFSET_FRACTION)));
                            scrollView.scrollTo(0, y);
                            lastScrollTarget = y;
                            growthAnchorValid = true;
                            growthAnchorIndex = index;
                            growthAnchorContainerY = lineRows.get(index).getTop()
                                    + lineView.getTop() + lineView.getTranslationY()
                                    + mainOffset;
                            userScrollUntilUptimeMs = SystemClock.uptimeMillis()
                                    + SECONDARY_REVEAL_MILLISECONDS;
                            return true;
                        }
                    });
        } else {
            final int clampGeneration = buildGeneration;
            linesContainer.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            linesContainer.getViewTreeObserver()
                                    .removeOnPreDrawListener(this);
                            if (clampGeneration != buildGeneration) {
                                return true;
                            }
                            final int maxScroll = Math.max(0,
                                    linesContainer.getHeight() - scrollView.getHeight());
                            final int y = Math.max(0,
                                    Math.min(maxScroll, scrollView.getScrollY()));
                            if (y != scrollView.getScrollY()) {
                                scrollView.scrollTo(0, y);
                                lastScrollTarget = y;
                            }
                            return true;
                        }
                    });
        }
    }

    private static List<WordTiming> computeWordTimings(List<LyricsLine> lines, int index) {
        final LyricsLine line = lines.get(index);
        if (isSingleWord(line.text())) {
            long rangeStart = line.startTimeMs();
            if (rangeStart < 0 && line.hasWords()) {
                rangeStart = line.words().get(0).startMs();
            }
            long rangeEnd = line.endTimeMs();
            if (rangeEnd <= rangeStart) {
                rangeEnd = index + 1 < lines.size()
                        ? lines.get(index + 1).startTimeMs()
                        : LyricsLine.NO_TIME;
            }
            if (rangeEnd <= rangeStart && line.hasWords()) {
                rangeEnd = line.words().get(line.words().size() - 1).endMs();
            }
            if (rangeStart >= 0 && rangeEnd > rangeStart) {
                final List<WordTiming> synthesized =
                        synthesizeLineChars(line.text(), rangeStart, rangeEnd);
                if (!synthesized.isEmpty()) {
                    return synthesized;
                }
            }
        }
        return computeWordTimingsFromWords(line);
    }

    private static List<WordTiming> synthesizeLineChars(String text, long startMs, long endMs) {
        final int charCount = text.codePointCount(0, text.length());
        if (charCount < 2 || endMs <= startMs) {
            return Collections.emptyList();
        }
        final List<WordTiming> timings = new ArrayList<>(charCount);
        int offset = 0;
        for (int i = 0; i < charCount; i++) {
            final int next = offset + Character.charCount(text.codePointAt(offset));
            final long charStart = startMs + (endMs - startMs) * i / charCount;
            final long charEnd = i + 1 == charCount
                    ? endMs
                    : startMs + (endMs - startMs) * (i + 1) / charCount;
            timings.add(new WordTiming(offset, next, charStart, charEnd, null));
            offset = next;
        }
        return timings;
    }

    private static boolean isSingleWord(String text) {
        return countWords(text) == 1;
    }

    private static int countWords(String text) {
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); ) {
            final int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                if (inWord) {
                    words++;
                    inWord = false;
                }
                continue;
            }
            if (isCjkCodePoint(codePoint)) {
                if (inWord) {
                    words++;
                    inWord = false;
                }
                words++;
                continue;
            }
            inWord = true;
        }
        return words + (inWord ? 1 : 0);
    }

    private static boolean isCjkCodePoint(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0x31F0 && codePoint <= 0x31FF);
    }

    private static List<WordTiming> computeWordTimingsFromWords(LyricsLine line) {
        if (!line.hasWords()) {
            return Collections.emptyList();
        }

        if (line.words().size() == 1) {
            Word single = line.words().get(0);
            if (isWholeLineWord(single.text(), line.text())) {
                return Collections.emptyList();
            }
            if (single.endMs() <= single.startMs()) {
                return Collections.emptyList();
            }
        }

        List<WordTiming> timings = new ArrayList<>(line.words().size());
        String text = line.text();
        int textLength = text.length();
        int offset = 0;
        for (Word word : line.words()) {
            String wordText = word.text();
            int wordLength = wordText.length();
            if (wordLength == 0) {
                continue;
            }
            int start = text.indexOf(wordText, offset);
            int len = wordLength;
            if (start < 0) {
                String trimmed = wordText.trim();
                if (!trimmed.isEmpty()) {
                    start = text.indexOf(trimmed, offset);
                    len = trimmed.length();
                }
            }
            if (start < 0) {
                // Unmatched word: advance past it so following words stay aligned,
                // rather than emitting a span that falls outside the line text.
                offset = Math.min(offset + len, textLength);
                continue;
            }
            int end = Math.min(start + len, textLength);
            if (start >= end) {
                continue;
            }
            timings.add(new WordTiming(start, end, word.startMs(), word.endMs(), word.romaji()));
            offset = end;
        }
        return timings;
    }

    private static boolean isWholeLineWord(String wordText, String lineText) {
        return compactText(wordText).equalsIgnoreCase(compactText(lineText));
    }

    private static String compactText(String value) {
        final StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!Character.isWhitespace(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * Builds the displayed text for a line, appending the translation (when shown) in a
     * smaller, dimmer style and coloring each word sung or unsung for the karaoke
     * highlight.
     *
     * <p>A fresh {@link SpannableString} is returned on every call so that
     * {@link android.widget.TextView#setText(CharSequence)} performs a full re-layout
     * and repaint. Mutating an existing Spannable in place was not reliably redrawn by
     * this TextView, which left the highlight invisible.
     *
     */
    private BuildResult buildLineText(LyricsLine line, List<WordTiming> timings, int index) {
        String original = line.text();
        String originalTrimmed = original.trim();

        final boolean usePerWord = perWordRomaji && line.hasWords() && lineHasWordRomaji(line);

        String romanization = null;
        if (!usePerWord && romanizedLines != null && index < romanizedLines.size()) {
            String roma = romanizedLines.get(index).text().trim();
            if (!roma.isEmpty() && !roma.equalsIgnoreCase(originalTrimmed)) {
                romanization = roma;
            }
        }

        List<String> translated = translatedLines;
        String translation = null;
        if (translated != null && index < translated.size()) {
            String t = translated.get(index).trim();
            if (!t.isEmpty() && !t.equalsIgnoreCase(originalTrimmed)) {
                translation = t;
            }
        }

        if (onlyMode == OnlyMode.TRANS) {
            final String content = translation != null ? translation : "";
            SpannableString text = new SpannableString(content);
            final int transStart = content.isEmpty() ? -1 : 0;
            final int transEnd = content.isEmpty() ? -1 : content.length();
            return new BuildResult(text, null, transStart, transEnd, -1, -1);
        }

        if (onlyMode == OnlyMode.ROMA) {
            String content = null;
            if (romanizedLines != null && index < romanizedLines.size()) {
                String roma = romanizedLines.get(index).text().trim();
                if (!roma.isEmpty() && !roma.equalsIgnoreCase(originalTrimmed)) {
                    content = roma;
                }
            }
            if (content == null && line.hasWords()) {
                content = joinWordRomaji(line);
            }
            if (content == null) {
                content = "";
            }
            SpannableString text = new SpannableString(content);
            final int romaStart = content.isEmpty() ? -1 : 0;
            final int romaEnd = content.isEmpty() ? -1 : content.length();
            return new BuildResult(text, null, -1, -1, romaStart, romaEnd);
        }

        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        StringBuilder builder = new StringBuilder();
        int romaStart = -1;
        int romaEnd = -1;
        int transStart = -1;
        int transEnd = -1;
        if (swap) {
            if (translation != null) {
                transStart = 0;
                builder.append(translation);
                builder.append('\n');
                transEnd = builder.length();
            }
            final int originalStart = builder.length();
            builder.append(original);
            final int originalEnd = builder.length();
            if (romanization != null) {
                builder.append('\n');
                romaStart = builder.length();
                builder.append(romanization);
                romaEnd = builder.length();
            }
            SpannableString text = new SpannableString(builder.toString());
            ForegroundColorSpan unsungSpan = applySpans(text, timings, originalStart, originalEnd,
                    romaStart, romaEnd, transStart, transEnd, usePerWord);
            return new BuildResult(text, unsungSpan, transStart, transEnd, romaStart, romaEnd);
        }
        if (romanization != null) {
            romaStart = 0;
            builder.append(romanization);
            romaEnd = builder.length();
            builder.append('\n');
        }
        final int originalStart = builder.length();
        builder.append(original);
        final int originalEnd = builder.length();
        if (translation != null) {
            builder.append('\n');
            transStart = builder.length();
            builder.append(translation);
            transEnd = builder.length();
        }

        SpannableString text = new SpannableString(builder.toString());
        ForegroundColorSpan unsungSpan = applySpans(text, timings, originalStart, originalEnd,
                romaStart, romaEnd, transStart, transEnd, usePerWord);
        return new BuildResult(text, unsungSpan, transStart, transEnd, romaStart, romaEnd);
    }

    @Nullable
    private static String joinWordRomaji(LyricsLine line) {
        if (!line.hasWords()) {
            return null;
        }
        StringBuilder sb = null;
        for (Word word : line.words()) {
            final String romaji = word.romaji();
            if (romaji == null || romaji.isEmpty()) {
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder();
            } else if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(romaji);
        }
        return sb == null || sb.length() == 0 ? null : sb.toString();
    }

    @Nullable
    private static ForegroundColorSpan applySpans(SpannableString text, List<WordTiming> timings,
            int originalStart, int originalEnd,
            int romaStart, int romaEnd, int transStart, int transEnd,
            boolean usePerWord) {
        ForegroundColorSpan unsungSpan = null;
        if (romaStart >= 0) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), romaStart, romaEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(secondaryTextColor() | 0xFF000000),
                    romaStart, romaEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (transStart >= 0) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), transStart, transEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(secondaryTextColor() | 0xFF000000),
                    transStart, transEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (Settings.LYRICS_WORD_SYNC.get() && !timings.isEmpty()) {
            int unsung = unsungWordColor() | 0xFF000000;
            unsungSpan = new ForegroundColorSpan(unsung);
            text.setSpan(unsungSpan, originalStart, originalEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (usePerWord) {
            final int romajiColor = secondaryTextColor() | 0xFF000000;
            for (WordTiming timing : timings) {
                if (timing.romaji() != null && !timing.romaji().isEmpty()) {
                    text.setSpan(new RomajiSpan(timing.romaji(), romajiColor, ROMAJI_RELATIVE_SIZE),
                            originalStart + timing.start(), originalStart + timing.end(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        return unsungSpan;
    }

    private static boolean lineHasWordRomaji(LyricsLine line) {
        if (!line.hasWords()) {
            return false;
        }
        for (Word word : line.words()) {
            if (word.romaji() != null && !word.romaji().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void onTranslateClicked() {
        try {
            // The saved translation state outlives the button, so a track change can
            // auto translate when there is no button to drive the translation from.
            if (translateView == null) {
                return;
            }
            if (onlyMode != OnlyMode.NONE
                    || Settings.LYRICS_TRANSLATE_ONLY.get()
                    || Settings.LYRICS_ROMANIZE_ONLY.get()) {
                return;
            }

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            if (translateInProgress) {
                cancelNormalTranslate();
                setButtonLabel(translateView, null, false);
                return;
            }

            if (translatedLines != null) {
                cancelNormalTranslate();
                hideSecondaryRegions(true, current);
                return;
            }

            Settings.LYRICS_TRANSLATE.save(true);
            Settings.LYRICS_TRANSLATE_ONLY.save(false);
            translateInProgress = true;
            final int requestId = ++translateRequestId;
            setButtonLabel(translateView, str("morphe_music_lyrics_translating"), true);

            LyricsTranslator.translate(track, current, current.providerName(),
                    (lines, fromGoogle, fromAI, model) -> {
                if (requestId != translateRequestId || !translateInProgress) {
                    return;
                }
                translateInProgress = false;

                // The track may have changed while the translation was in flight.
                if (lyrics != current) {
                    return;
                }

                translatedLines = hasTranslation(lines, current.lines()) ? lines : null;
                translatedFromGoogle = translatedLines != null && fromGoogle;
                translatedFromAI = translatedLines != null && fromAI;
                if (translatedFromAI && model != null) {
                    translatedAiModel = model;
                }
                if (lines == null) {
                    Utils.showToastShort(str("morphe_music_lyrics_translate_failed"));
                }
                showLyrics(current);
                if (translatedLines != null) {
                    setButtonLabel(translateView, str("morphe_music_lyrics_translate_hide"), true);
                    handler.removeCallbacks(updateTranslateLabelRunnable);
                    handler.postDelayed(updateTranslateLabelRunnable, 3000);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onTranslateClicked failure", ex);
        }
    }

    private void onTranslateLongPressed() {
        try {
            if (translateView == null) {
                return;
            }
            if (onlyMode == OnlyMode.TRANS || Settings.LYRICS_TRANSLATE_ONLY.get()) {
                cancelTranslateOnly();
                return;
            }
            final boolean sameKeyActive = normalTranslateActive();
            final boolean otherActive = normalRomanizeActive();
            if (sameKeyActive && !otherActive) {
                return;
            }

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            final boolean leavingOtherOnly =
                    onlyMode == OnlyMode.ROMA || Settings.LYRICS_ROMANIZE_ONLY.get();
            if (leavingOtherOnly) {
                clearRomanizeOnlyState();
            }

            final boolean hadNormalRomanize = normalRomanizeActive();
            if (hadNormalRomanize) {
                disableNormalRomanize();
                updateRomanizeLabel();
            }

            translateRequestId++;
            translateInProgress = false;
            Settings.LYRICS_TRANSLATE_ONLY.save(true);
            Settings.LYRICS_TRANSLATE.save(false);

            if (translatedLines != null) {
                onlyMode = OnlyMode.TRANS;
                enterOnlyModeState();
                showLyrics(current);
                setButtonLabel(translateView, str("morphe_music_lyrics_translate_only"), true);
                handler.removeCallbacks(updateTranslateLabelRunnable);
                handler.postDelayed(updateTranslateLabelRunnable, 3000);
                return;
            }
            onlyMode = OnlyMode.NONE;
            if (leavingOtherOnly || hadNormalRomanize) {
                showLyrics(current);
            }
            requestTranslateOnly(current);
        } catch (Exception ex) {
            Logger.printException(() -> "onTranslateLongPressed failure", ex);
        }
    }

    private void requestTranslateOnly(Lyrics current) {
        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        if (track == null || current == null || translateInProgress) {
            return;
        }
        Settings.LYRICS_TRANSLATE_ONLY.save(true);
        Settings.LYRICS_TRANSLATE.save(false);
        if (normalRomanizeActive()) {
            disableNormalRomanize();
            updateRomanizeLabel();
        }
        if (translatedLines != null) {
            onlyMode = OnlyMode.TRANS;
            enterOnlyModeState();
            showLyrics(current);
            updateTranslateLabel();
            return;
        }
        cancelNormalTranslate();
        translateInProgress = true;
        final int requestId = ++translateRequestId;
        setButtonLabel(translateView, str("morphe_music_lyrics_translating"), true);

        LyricsTranslator.translate(track, current, current.providerName(),
                (lines, fromGoogle, fromAI, model) -> {
            if (requestId != translateRequestId || !Settings.LYRICS_TRANSLATE_ONLY.get()) {
                return;
            }
            if (lyrics != current) {
                return;
            }
            translateInProgress = false;
            translatedLines = hasTranslation(lines, current.lines()) ? lines : null;
            translatedFromGoogle = translatedLines != null && fromGoogle;
            translatedFromAI = translatedLines != null && fromAI;
            if (translatedFromAI && model != null) {
                translatedAiModel = model;
            }
            if (translatedLines == null) {
                Utils.showToastShort(str("morphe_music_lyrics_translate_failed"));
                clearTranslateOnlyState();
                showLyrics(current);
                updateTranslateLabel();
                return;
            }
            onlyMode = OnlyMode.TRANS;
            enterOnlyModeState();
            showLyrics(current);
            setButtonLabel(translateView, str("morphe_music_lyrics_translate_only"), true);
            handler.removeCallbacks(updateTranslateLabelRunnable);
            handler.postDelayed(updateTranslateLabelRunnable, 3000);
        });
    }

    private void cancelTranslateOnly() {
        clearTranslateOnlyState();
        Lyrics current = lyrics;
        if (current != null) {
            showLyrics(current);
        }
        updateTranslateLabel();
        updateRomanizeLabel();
    }

    private void clearTranslateOnlyState() {
        Settings.LYRICS_TRANSLATE_ONLY.save(false);
        cancelNormalTranslate();
        if (onlyMode == OnlyMode.TRANS) {
            onlyMode = OnlyMode.NONE;
            exitOnlyModeState();
        }
        updateFooter();
    }

    private void clearRomanizeOnlyState() {
        Settings.LYRICS_ROMANIZE_ONLY.save(false);
        cancelNormalRomanize();
        if (onlyMode == OnlyMode.ROMA) {
            onlyMode = OnlyMode.NONE;
            exitOnlyModeState();
        }
        updateFooter();
    }

    private void disableNormalTranslate() {
        cancelNormalTranslate();
        if (translateView != null) {
            setButtonLabel(translateView, null, false);
        }
    }

    private void disableNormalRomanize() {
        cancelNormalRomanize();
        if (romanizeView != null) {
            setButtonLabel(romanizeView, null, false);
        }
    }

    /** Invalidates an in-flight normal translation and clears its data flags. */
    private void cancelNormalTranslate() {
        translateRequestId++;
        translateInProgress = false;
        Settings.LYRICS_TRANSLATE.save(false);
        translatedLines = null;
        translatedFromGoogle = false;
        translatedFromAI = false;
        translatedAiModel = null;
        updateFooter();
    }

    private void cancelNormalRomanize() {
        romanizeRequestId++;
        romanizeInProgress = false;
        Settings.LYRICS_ROMANIZE.save(false);
        romanizedLines = null;
        perWordRomaji = false;
        romanizedFromGoogle = false;
        romanizedFromAI = false;
        romanizedAiModel = null;
        updateFooter();
    }

    private void updateFooter() {
        Lyrics current = lyrics;
        if (current == null) {
            return;
        }
        footerView.setText(sourceText(current.providerName(),
                translatedLines != null, translatedFromGoogle, translatedFromAI, translatedAiModel,
                romanizedLines != null || perWordRomaji,
                romanizedFromGoogle, romanizedFromAI, romanizedAiModel, onlyMode));
    }

    private boolean normalTranslateActive() {
        return Settings.LYRICS_TRANSLATE.get()
                || translateInProgress
                || translatedLines != null;
    }

    private boolean normalRomanizeActive() {
        return Settings.LYRICS_ROMANIZE.get()
                || romanizeInProgress
                || romanizedLines != null
                || perWordRomaji;
    }

    private void resetOnlyModeKaraokeState() {
        lastWordLineIndex = -1;
        pendingOldWordLineIndex = -1;
        wordSyncWasEnabled = Settings.LYRICS_WORD_SYNC.get();
    }

    private void enterOnlyModeState() {
        captureOnlyModeScrollAnchor();
        resetOnlyModeKaraokeState();
    }

    private void exitOnlyModeState() {
        captureOnlyModeScrollAnchor();
        resetOnlyModeKaraokeState();
    }

    private void captureOnlyModeScrollAnchor() {
        pendingHideAnchorIndex = -1;
        pendingHideAnchorScreenY = 0f;
        if (highlightedIndex < 0 || highlightedIndex >= lineRows.size()
                || highlightedIndex >= lineViews.size()) {
            return;
        }
        final View row = lineRows.get(highlightedIndex);
        final TextView child = lineViews.get(highlightedIndex);
        if (child.getHeight() <= 0 || linesContainer.getHeight() <= 0) {
            return;
        }
        final int mainOffset = builtOnlyMode == OnlyMode.NONE
                ? mainLineOffsetFor(child)
                : 0;
        pendingHideAnchorIndex = highlightedIndex;
        pendingHideAnchorScreenY = row.getTop() + child.getTop()
                + child.getTranslationY() + mainOffset
                - scrollView.getScrollY();
    }

    /**
     * Opens the lyrics source URL in a browser.
     */
    private void onSourceClicked() {
        try {
            if (currentSourceUrl == null || currentSourceUrl.isEmpty()) {
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(currentSourceUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception ex) {
            Logger.printDebug(() -> "onSourceClicked failure", ex);
        }
    }

    private void onCopyClicked() {
        try {
            Lyrics current = lyrics;
            if (current == null) {
                return;
            }

            List<LyricsLine> lines = current.lines();
            StringBuilder text = new StringBuilder();
            for (int i = 0, linesSize = lines.size(); i < linesSize; i++) {
                if (i != 0) {
                    text.append('\n');
                }
                text.append(lines.get(i).text());
            }

            ClipboardManager clipboard = (ClipboardManager) getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("lyrics", text.toString()));
            Utils.showToastShort(str("morphe_music_lyrics_copied"));
            setButtonLabel(copyView, str("morphe_music_lyrics_copied"), true);
            handler.postDelayed(() -> setButtonLabel(copyView, null, false), 1500);
        } catch (Exception ex) {
            Logger.printException(() -> "onCopyClicked failure", ex);
        }
    }

    private void onCopyLongPressed() {
        try {
            if (saveInProgress) {
                return;
            }
            Lyrics current = lyrics;
            if (current == null || current.rawFormat() == null) {
                return;
            }
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (track == null) {
                return;
            }
            saveInProgress = true;
            final Context appContext = getContext().getApplicationContext();
            Utils.runOnBackgroundThread(() -> {
                try {
                    final String savedPath = LyricsFileSaver.save(appContext, track, current);
                    Utils.runOnMainThread(() -> {
                        saveInProgress = false;
                        if (savedPath == null) {
                            return;
                        }
                        Utils.showToastShort("Saved to " + savedPath);
                        if (copyView != null) {
                            setButtonLabel(copyView, str("morphe_music_lyrics_saved"), true);
                            handler.postDelayed(() -> setButtonLabel(copyView, null, false), 1500);
                        }
                    });
                } catch (Exception ex) {
                    Logger.printDebug(() -> "onCopyLongPressed failure", ex);
                    Utils.runOnMainThread(() -> saveInProgress = false);
                }
            });
        } catch (Exception ex) {
            saveInProgress = false;
            Logger.printDebug(() -> "onCopyLongPressed failure", ex);
        }
    }

    private void updateTranslateLabel() {
        if (translateView != null) {
            final boolean on = onlyMode == OnlyMode.TRANS
                    || (onlyMode == OnlyMode.NONE && translatedLines != null)
                    || (onlyMode == OnlyMode.ROMA && Settings.LYRICS_TRANSLATE.get());
            setButtonLabel(translateView, null, on);
        }
    }

    private void onRomanizeClicked() {
        try {
            // The saved romanization state outlives the button, so a track change can
            // auto romanize when there is no button to drive the romanization from.
            if (romanizeView == null) {
                return;
            }
            if (onlyMode != OnlyMode.NONE
                    || Settings.LYRICS_TRANSLATE_ONLY.get()
                    || Settings.LYRICS_ROMANIZE_ONLY.get()) {
                return;
            }

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            if (romanizeInProgress) {
                cancelNormalRomanize();
                setButtonLabel(romanizeView, null, false);
                return;
            }

            if (romanizedLines != null || perWordRomaji) {
                cancelNormalRomanize();
                hideSecondaryRegions(false, current);
                return;
            }

            Settings.LYRICS_ROMANIZE.save(true);
            Settings.LYRICS_ROMANIZE_ONLY.save(false);
            romanizeInProgress = true;
            final int requestId = ++romanizeRequestId;
            setButtonLabel(romanizeView, str("morphe_music_lyrics_romanizing"), true);

            LyricsRomanizer.romanize(track, current, current.providerName(),
                    (lines, fromGoogle, fromAI, model, perWord) -> {
                if (requestId != romanizeRequestId || !romanizeInProgress) {
                    return;
                }
                romanizeInProgress = false;

                // The track may have changed while the romanization was in flight.
                if (lyrics != current) {
                    return;
                }

                final boolean romaOk = LyricsMerge.hasText(lines);
                romanizedLines = romaOk ? lines : null;
                romanizedFromGoogle = romaOk && fromGoogle;
                romanizedFromAI = romaOk && fromAI;
                if (romanizedFromAI && model != null) {
                    romanizedAiModel = model;
                }
                perWordRomaji = romaOk && perWord;
                if (lines == null && !perWord) {
                    Utils.showToastShort(str("morphe_music_lyrics_romanize_failed"));
                }
                showLyrics(current);
                if (romaOk) {
                    setButtonLabel(romanizeView, str("morphe_music_lyrics_romanize_hide"), true);
                    handler.removeCallbacks(updateRomanizeLabelRunnable);
                    handler.postDelayed(updateRomanizeLabelRunnable, 3000);
                }
            });
        } catch (Exception ex) {
            Logger.printDebug(() -> "onRomanizeClicked failure", ex);
        }
    }

    private void onRomanizeLongPressed() {
        try {
            if (romanizeView == null) {
                return;
            }
            if (onlyMode == OnlyMode.ROMA || Settings.LYRICS_ROMANIZE_ONLY.get()) {
                cancelRomanizeOnly();
                return;
            }
            final boolean sameKeyActive = normalRomanizeActive();
            final boolean otherActive = normalTranslateActive();
            if (sameKeyActive && !otherActive) {
                return;
            }

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            final boolean leavingOtherOnly =
                    onlyMode == OnlyMode.TRANS || Settings.LYRICS_TRANSLATE_ONLY.get();
            if (leavingOtherOnly) {
                clearTranslateOnlyState();
            }

            final boolean hadNormalTranslate = normalTranslateActive();
            if (hadNormalTranslate) {
                disableNormalTranslate();
                updateTranslateLabel();
            }

            // Drop any in-flight normal romanization so it cannot apply after ONLY
            // starts, but keep already-fetched lines for the ONLY render.
            romanizeRequestId++;
            romanizeInProgress = false;
            Settings.LYRICS_ROMANIZE_ONLY.save(true);
            Settings.LYRICS_ROMANIZE.save(false);

            if (romanizedLines != null || perWordRomaji) {
                onlyMode = OnlyMode.ROMA;
                enterOnlyModeState();
                showLyrics(current);
                setButtonLabel(romanizeView, str("morphe_music_lyrics_romanize_only"), true);
                handler.removeCallbacks(updateRomanizeLabelRunnable);
                handler.postDelayed(updateRomanizeLabelRunnable, 3000);
                return;
            }
            onlyMode = OnlyMode.NONE;
            if (leavingOtherOnly || hadNormalTranslate) {
                showLyrics(current);
            }
            requestRomanizeOnly(current);
        } catch (Exception ex) {
            Logger.printException(() -> "onRomanizeLongPressed failure", ex);
        }
    }

    private void requestRomanizeOnly(Lyrics current) {
        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        if (track == null || current == null || romanizeInProgress) {
            return;
        }
        Settings.LYRICS_ROMANIZE_ONLY.save(true);
        Settings.LYRICS_ROMANIZE.save(false);
        if (normalTranslateActive()) {
            disableNormalTranslate();
            updateTranslateLabel();
        }
        if (romanizedLines != null || perWordRomaji) {
            onlyMode = OnlyMode.ROMA;
            enterOnlyModeState();
            showLyrics(current);
            updateRomanizeLabel();
            return;
        }
        cancelNormalRomanize();
        romanizeInProgress = true;
        final int requestId = ++romanizeRequestId;
        setButtonLabel(romanizeView, str("morphe_music_lyrics_romanizing"), true);

        LyricsRomanizer.romanize(track, current, current.providerName(),
                (lines, fromGoogle, fromAI, model, perWord) -> {
            if (requestId != romanizeRequestId || !Settings.LYRICS_ROMANIZE_ONLY.get()) {
                return;
            }
            if (lyrics != current) {
                return;
            }
            romanizeInProgress = false;
            final boolean romaOk = LyricsMerge.hasText(lines);
            romanizedLines = romaOk ? lines : null;
            romanizedFromGoogle = romaOk && fromGoogle;
            romanizedFromAI = romaOk && fromAI;
            if (romanizedFromAI && model != null) {
                romanizedAiModel = model;
            }
            perWordRomaji = romaOk && perWord;
            if (!romaOk) {
                Utils.showToastShort(str("morphe_music_lyrics_romanize_failed"));
                clearRomanizeOnlyState();
                showLyrics(current);
                updateRomanizeLabel();
                return;
            }
            onlyMode = OnlyMode.ROMA;
            enterOnlyModeState();
            showLyrics(current);
            setButtonLabel(romanizeView, str("morphe_music_lyrics_romanize_only"), true);
            handler.removeCallbacks(updateRomanizeLabelRunnable);
            handler.postDelayed(updateRomanizeLabelRunnable, 3000);
        });
    }

    private void cancelRomanizeOnly() {
        clearRomanizeOnlyState();
        Lyrics current = lyrics;
        if (current != null) {
            showLyrics(current);
        }
        updateRomanizeLabel();
        updateTranslateLabel();
    }

    private void updateRomanizeLabel() {
        if (romanizeView != null) {
            final boolean on = onlyMode == OnlyMode.ROMA
                    || (onlyMode == OnlyMode.NONE && (romanizedLines != null || perWordRomaji))
                    || (onlyMode == OnlyMode.TRANS && Settings.LYRICS_ROMANIZE.get());
            setButtonLabel(romanizeView, null, on);
        }
    }

    private void onRefreshClicked() {
        if (refreshView == null) {
            return;
        }
        refreshInProgress = true;
        setButtonLabel(refreshView, str("morphe_music_lyrics_refreshing"), true);
        LyricsManager.getInstance().fetchNextCandidate();
    }

    private void onRefreshLongPressed() {
        if (refreshView == null) {
            return;
        }
        LyricsManager manager = LyricsManager.getInstance();
        if (manager.isOverrideNative()) {
            setButtonLabel(refreshView, null, false);
            manager.setOverrideNative(false);
        } else {
            manager.setOverrideNative(true);
            setButtonLabel(refreshView, str("morphe_music_lyrics_refreshing"), true);
        }
    }

    private void updateRefreshLabel() {
        if (refreshView != null) {
            setButtonLabel(refreshView, null, false);
        }
    }

    private static final class RowResize {
        final int rowIndex;
        final View row;
        final View child;
        final int from;
        final int to;
        final int leading;
        final int trailing;

        RowResize(int rowIndex, View row, View child, int from, int to, int leading,
                int trailing) {
            this.rowIndex = rowIndex;
            this.row = row;
            this.child = child;
            this.from = from;
            this.to = to;
            this.leading = leading;
            this.trailing = trailing;
        }
    }

    private void startRowHeightAnimation(List<RowResize> resizes, boolean releaseAtEnd,
            Runnable onEnd) {
        if (rowHeightAnimator != null) {
            rowHeightAnimator.cancel();
            rowHeightAnimator = null;
        }
        if (scrollAnchorRowIndex < 0 && growthAnchorValid
                && growthAnchorIndex >= 0 && growthAnchorIndex < lineRows.size()
                && growthAnchorIndex < lineViews.size()
                && linesContainer.getHeight() > 0) {
            final View row = lineRows.get(growthAnchorIndex);
            final TextView child = lineViews.get(growthAnchorIndex);
            scrollAnchorRowIndex = growthAnchorIndex;
            scrollAnchorBaseRowTop = row.getTop();
            scrollAnchorChildTop = child.getTop() + mainLineOffset(child);
            scrollAnchorScreenY = growthAnchorContainerY - scrollView.getScrollY();
            scrollAnchorBaseContentHeight = linesContainer.getHeight();
            userScrollUntilUptimeMs = Math.max(userScrollUntilUptimeMs,
                    SystemClock.uptimeMillis() + SECONDARY_REVEAL_MILLISECONDS + 100);
        }
        for (RowResize resize : resizes) {
            final ViewGroup.LayoutParams lp = resize.row.getLayoutParams();
            lp.height = resize.from;
            resize.row.setLayoutParams(lp);
        }
        final boolean[] canceled = {false};
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SECONDARY_REVEAL_MILLISECONDS);
        animator.addUpdateListener(animation -> {
            final float t = (float) animation.getAnimatedValue();
            final boolean holdScroll = scrollAnchorRowIndex >= 0;
            int anchorRowTop = holdScroll ? scrollAnchorBaseRowTop : 0;
            int contentHeight = holdScroll ? scrollAnchorBaseContentHeight : 0;
            float anchorTranslation = 0f;
            for (RowResize resize : resizes) {
                final int height = Math.round(
                        resize.from + (resize.to - resize.from) * t);
                final ViewGroup.LayoutParams lp = resize.row.getLayoutParams();
                lp.height = height;
                resize.row.setLayoutParams(lp);
                final int spread = resize.leading + resize.trailing;
                if (resize.leading > 0 && spread > 0) {
                    final int full = Math.max(resize.from, resize.to);
                    resize.child.setTranslationY(
                            -resize.leading * (float) (full - height) / spread);
                }
                if (holdScroll) {
                    if (resize.rowIndex >= 0 && resize.rowIndex < scrollAnchorRowIndex) {
                        anchorRowTop += height - resize.from;
                    }
                    if (resize.rowIndex == scrollAnchorRowIndex) {
                        anchorTranslation = resize.child.getTranslationY();
                    }
                    contentHeight += height - resize.from;
                }
            }
            if (holdScroll) {
                final int maxScroll = Math.max(0, contentHeight - scrollView.getHeight());
                final int target = Math.max(0, Math.min(maxScroll,
                        Math.round(anchorRowTop + scrollAnchorChildTop + anchorTranslation
                                - scrollAnchorScreenY)));
                if (target != scrollView.getScrollY()) {
                    scrollView.scrollTo(0, target);
                    lastScrollTarget = target;
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                canceled[0] = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                final int anchorIndex = scrollAnchorRowIndex;
                final float anchorScreenY = scrollAnchorScreenY;
                scrollAnchorRowIndex = -1;
                if (releaseAtEnd) {
                    for (RowResize resize : resizes) {
                        final ViewGroup.LayoutParams lp = resize.row.getLayoutParams();
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        resize.row.setLayoutParams(lp);
                        resize.child.setTranslationY(0f);
                    }
                }
                if (rowHeightAnimator == animation) {
                    rowHeightAnimator = null;
                }
                if (!canceled[0] && anchorIndex >= 0 && anchorIndex < lineRows.size()
                        && anchorIndex < lineViews.size()) {
                    final int maxScroll = Math.max(0,
                            linesContainer.getHeight() - scrollView.getHeight());
                    final int target = Math.max(0, Math.min(maxScroll,
                            lineRows.get(anchorIndex).getTop()
                                    + lineViews.get(anchorIndex).getTop()
                                    + mainLineOffset(lineViews.get(anchorIndex))
                                    - Math.round(anchorScreenY)));
                    if (target != scrollView.getScrollY()) {
                        scrollView.scrollTo(0, target);
                        lastScrollTarget = target;
                    }
                }
                if (!canceled[0] && onEnd != null) {
                    onEnd.run();
                }
            }
        });
        rowHeightAnimator = animator;
        animator.start();
    }

    private static int leadingRegionSize(Layout layout, int start, int end) {
        if (start != 0 || end <= start || end > layout.getText().length()) {
            return 0;
        }
        return layout.getLineBottom(layout.getLineForOffset(end - 1));
    }

    private static int trailingRegionSize(Layout layout, int start, int end) {
        if (start <= 0 || end <= start || end > layout.getText().length()) {
            return 0;
        }
        return layout.getHeight() - layout.getLineTop(layout.getLineForOffset(start));
    }

    /** Height of the region rendered above the original line, i.e. how far the main lyric sits below the row top. */
    private int mainLineOffset(TextView lineView) {
        if (onlyMode != OnlyMode.NONE) {
            return 0;
        }
        return mainLineOffsetFor(lineView);
    }

    private int mainLineOffsetFor(TextView lineView) {
        if (!(lineView instanceof LyricsLineView view)) {
            return 0;
        }
        final Layout layout = view.getLayout();
        if (layout == null) {
            return 0;
        }
        return leadingRegionSize(layout, view.transStart, view.transEnd)
                + leadingRegionSize(layout, view.romaStart, view.romaEnd);
    }

    private void hideSecondaryRegions(boolean trans, Lyrics current) {
        final int generation = buildGeneration;
        boolean any = false;
        for (TextView lineView : lineViews) {
            if (!(lineView instanceof LyricsLineView view)) {
                continue;
            }
            if (trans ? view.transStart >= 0 : view.romaStart >= 0) {
                any = true;
            }
        }
        if (!any) {
            showLyrics(current);
            return;
        }
        handler.post(() -> {
            if (generation == buildGeneration) {
                userScrollUntilUptimeMs = Math.max(userScrollUntilUptimeMs,
                        SystemClock.uptimeMillis()
                                + 2 * SECONDARY_REVEAL_MILLISECONDS + 100);
            }
        });
        for (TextView lineView : lineViews) {
            if (!(lineView instanceof LyricsLineView view)) {
                continue;
            }
            if (trans ? view.transStart >= 0 : view.romaStart >= 0) {
                view.animateRegionOut(trans);
            }
        }
        handler.postDelayed(() -> {
            if (generation != buildGeneration || lyrics != current) {
                return;
            }
            captureHideScrollAnchor();
            shrinkRegionRows(trans, () -> {
                if (generation != buildGeneration || lyrics != current) {
                    return;
                }
                showLyrics(current);
            });
        }, SECONDARY_REVEAL_MILLISECONDS);
    }

    private void captureHideScrollAnchor() {
        pendingHideAnchorIndex = -1;
        pendingHideAnchorScreenY = 0f;
        scrollAnchorRowIndex = -1;
        if (onlyMode != OnlyMode.NONE) {
            return;
        }
        int index = highlightedIndex;
        if (index < 0 && !lineRows.isEmpty()) {
            index = 0;
        }
        if (index < 0 || index >= lineRows.size() || index >= lineViews.size()) {
            return;
        }
        final View row = lineRows.get(index);
        final TextView child = lineViews.get(index);
        final int mainOffset = mainLineOffset(child);
        pendingHideAnchorIndex = index;
        pendingHideAnchorScreenY = row.getTop() + child.getTop()
                + child.getTranslationY() + mainOffset
                - scrollView.getScrollY();
        scrollAnchorRowIndex = index;
        scrollAnchorBaseRowTop = row.getTop();
        scrollAnchorChildTop = child.getTop() + mainOffset;
        scrollAnchorScreenY = pendingHideAnchorScreenY;
        scrollAnchorBaseContentHeight = linesContainer.getHeight();
        userScrollUntilUptimeMs = SystemClock.uptimeMillis()
                + 2 * SECONDARY_REVEAL_MILLISECONDS + 100;
    }

    private void shrinkRegionRows(boolean trans, Runnable onEnd) {
        final List<RowResize> resizes = new ArrayList<>();
        for (int i = 0; i < lineViews.size() && i < lineRows.size(); i++) {
            final View row = lineRows.get(i);
            final int oldH = row.getHeight();
            if (oldH <= 1 || !(lineViews.get(i) instanceof LyricsLineView view)) {
                continue;
            }
            final int start = trans ? view.transStart : view.romaStart;
            final int end = trans ? view.transEnd : view.romaEnd;
            if (start < 0 || end <= start) {
                continue;
            }
            final TextView tv = lineViews.get(i);
            final Layout layout = tv.getLayout();
            if (layout == null || end > layout.getText().length()) {
                continue;
            }
            final int targetLayoutH = start == 0
                    ? layout.getHeight()
                            - layout.getLineBottom(layout.getLineForOffset(end - 1))
                    : layout.getLineTop(layout.getLineForOffset(start));
            final int targetH =
                    targetLayoutH + tv.getPaddingTop() + tv.getPaddingBottom();
            if (targetH <= 0 || targetH >= oldH - 1) {
                continue;
            }
            resizes.add(new RowResize(i, row, tv, oldH, targetH,
                    start == 0 ? oldH - targetH : 0, 0));
        }
        if (!resizes.isEmpty()) {
            startRowHeightAnimation(resizes, false, onEnd);
        } else if (onEnd != null) {
            onEnd.run();
        }
    }

    private void clearLines() {
        buildGeneration++;
        for (TextView lineView : lineViews) {
            // A running fade would otherwise keep a reference to a removed view.
            if (lineView instanceof LyricsLineView view) {
                if (view.fadeAnimator != null) {
                    view.fadeAnimator.cancel();
                    view.fadeAnimator = null;
                }
                view.cancelRevealAnimators();
            }
        }
        if (rowHeightAnimator != null) {
            rowHeightAnimator.cancel();
            rowHeightAnimator = null;
        }
        for (View lineRow : lineRows) {
            linesContainer.removeView(lineRow);
        }
        lineViews.clear();
        lineRows.clear();
        lineWordSpans.clear();
        lineOriginalStarts.clear();
        lineUnsungSpans.clear();
        highlightedIndex = -1;
        lastWordLineIndex = -1;
        lastOverlayIndex = -1;
        lastOverlayHideActive = false;
        pendingOldWordLineIndex = -1;
        lastScrollTarget = -1;
        seekPending = false;
        scrollAnchorRowIndex = -1;
        if (pendingAnchorScroll != null) {
            linesContainer.getViewTreeObserver().removeOnPreDrawListener(pendingAnchorScroll);
            pendingAnchorScroll = null;
        }
        pendingHideAnchorIndex = -1;
        pendingHideAnchorScreenY = 0f;
    }

    private void updateHighlight() {
        Lyrics current = lyrics;
        if (current == null || !current.synced() || lineViews.isEmpty()) {
            return;
        }

        final boolean karaokeActive = Settings.LYRICS_WORD_SYNC.get();

        LyricsManager manager = LyricsManager.getInstance();
        final long pos = manager.getPositionMs();
        final int index = current.indexForPosition(pos, highlightedIndex);
        if (index == highlightedIndex) {
            if (!karaokeActive && index >= 0 && index < lineViews.size()) {
                lineViews.get(index).setTextColor(lineTextColor());
                for (int b = 1; index + b < lineViews.size()
                        && index + b < current.lines().size()
                        && current.lines().get(index + b).isBG(); b++) {
                    lineViews.get(index + b).setTextColor(lineTextColor());
                }
            }
            applyLineOverlay(index);
            scrollAnchorIntoView(index >= 0 ? index : 0, false);
            return;
        }

        if (highlightedIndex >= 0 && highlightedIndex < lineViews.size()) {
            boolean keepFullOpacity = false;
            if (karaokeActive
                    && highlightedIndex < lineWordSpans.size()) {
                List<WordTiming> timings = lineWordSpans.get(highlightedIndex);
                if (!timings.isEmpty()) {
                    final long lastEnd = timings.get(timings.size() - 1).endMs();
                    final long firstStart = timings.get(0).startMs();
                    if (pos < lastEnd && pos >= firstStart) {
                        keepFullOpacity = true;
                    }
                }
            }
            if (!keepFullOpacity) {
                fadeTo(lineViews.get(highlightedIndex), INACTIVE_LINE_ALPHA);
                if (!karaokeActive) {
                    lineViews.get(highlightedIndex).setTextColor(unsungWordColor());
                }
                for (int b = 1; highlightedIndex + b < lineViews.size()
                        && highlightedIndex + b < current.lines().size()
                        && current.lines().get(highlightedIndex + b).isBG(); b++) {
                    fadeTo(lineViews.get(highlightedIndex + b), INACTIVE_LINE_ALPHA);
                    if (!karaokeActive) {
                        lineViews.get(highlightedIndex + b).setTextColor(unsungWordColor());
                    }
                }
            }
        }
        highlightedIndex = index;
        seekPending = false;

        if (index < 0 || index >= lineViews.size()) {
            applyLineOverlay(index);
            if (index < 0 && !lineRows.isEmpty()
                    && SystemClock.uptimeMillis() >= userScrollUntilUptimeMs) {
                scrollAnchorIntoView(0, true);
            }
            return;
        }

        applyLineOverlay(index);

        fadeTo(lineViews.get(index), 1f);
        if (!karaokeActive) {
            lineViews.get(index).setTextColor(lineTextColor());
        }
        for (int b = 1; index + b < lineViews.size()
                && index + b < current.lines().size()
                && current.lines().get(index + b).isBG(); b++) {
            fadeTo(lineViews.get(index + b), 1f);
            if (!karaokeActive) {
                lineViews.get(index + b).setTextColor(lineTextColor());
            }
        }

        if (SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
            return;
        }

        scrollAnchorIntoView(index, true);
    }

    private void scrollAnchorIntoView(int anchor, boolean lineChanged) {
        if (anchor < 0 || anchor >= lineViews.size() || anchor >= lineRows.size()) {
            return;
        }
        if (seekPending) {
            return;
        }
        if (SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
            return;
        }
        if (isLayoutRequested()) {
            deferScrollToPreDraw(anchor, lineChanged);
            return;
        }
        final int target = lineRows.get(anchor).getTop()
                + lineViews.get(anchor).getTop()
                + mainLineOffset(lineViews.get(anchor))
                - scrollView.getHeight() / SCROLL_OFFSET_FRACTION;
        final int clamped = Math.max(0, target);
        final int dist = Math.abs(scrollView.getScrollY() - clamped);
        if (dist > scrollView.getHeight() * SCROLL_INSTANT_THRESHOLD_FACTOR) {
            scrollView.scrollTo(0, clamped);
            lastScrollTarget = clamped;
        } else if (clamped != lastScrollTarget
                && (lineChanged
                        || dist > scrollView.getHeight() / SCROLL_SMOOTH_THRESHOLD_FACTOR)) {
            scrollView.smoothScrollTo(0, clamped);
            lastScrollTarget = clamped;
        }
    }

    private void deferScrollToPreDraw(int anchor, boolean lineChanged) {
        if (!isAttachedToWindow()) {
            return;
        }
        final ViewTreeObserver observer = linesContainer.getViewTreeObserver();
        if (pendingAnchorScroll != null) {
            observer.removeOnPreDrawListener(pendingAnchorScroll);
        }
        final int generation = buildGeneration;
        final ViewTreeObserver.OnPreDrawListener listener =
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        linesContainer.getViewTreeObserver().removeOnPreDrawListener(this);
                        if (pendingAnchorScroll == this) {
                            pendingAnchorScroll = null;
                        }
                        if (generation == buildGeneration) {
                            scrollAnchorIntoView(anchor, lineChanged);
                        }
                        return true;
                    }
                };
        pendingAnchorScroll = listener;
        observer.addOnPreDrawListener(listener);
    }

    private void applyLineOverlay(int index) {
        final boolean hidePlayed = Settings.LYRICS_HIDE_PLAYED.get();
        final boolean hideUnplayed = Settings.LYRICS_HIDE_UNPLAYED.get();
        final boolean hideActive = hidePlayed || hideUnplayed;
        final boolean modeChanged = hideActive != lastOverlayHideActive;
        lastOverlayHideActive = hideActive;

        if (!hideActive) {
            if (!modeChanged && index == lastOverlayIndex) {
                return;
            }
            linesContainer.suppressLayout(true);
            try {
                for (int i = 0; i < lineRows.size(); i++) {
                    if (lineRows.get(i).getVisibility() != VISIBLE) {
                        lineRows.get(i).setVisibility(VISIBLE);
                    }
                }
            } finally {
                linesContainer.suppressLayout(false);
            }
            lastOverlayIndex = index;
            return;
        }

        if (!modeChanged && index == lastOverlayIndex) {
            return;
        }

        linesContainer.suppressLayout(true);
        try {
            for (int i = 0; i < lineRows.size(); i++) {
                final int newVis;
                if (index < 0) {
                    newVis = hideUnplayed ? GONE : VISIBLE;
                } else if (hidePlayed && i < index) {
                    newVis = GONE;
                } else if (hideUnplayed && i > index) {
                    newVis = GONE;
                } else {
                    newVis = VISIBLE;
                }
                lineRows.get(i).setVisibility(newVis);
            }
        } finally {
            linesContainer.suppressLayout(false);
        }
        lastOverlayIndex = index;
    }

    private void updateOnlyHighlight() {
        Lyrics current = lyrics;
        if (current == null || !current.synced() || lineViews.isEmpty()) {
            return;
        }

        final long pos = LyricsManager.getInstance().getPositionMs();
        final int index = current.indexForPosition(pos, highlightedIndex);
        if (index == highlightedIndex) {
            applyLineOverlay(index);
            scrollAnchorIntoView(index >= 0 ? index : 0, false);
            return;
        }

        if (highlightedIndex >= 0 && highlightedIndex < lineViews.size()) {
            fadeTo(lineViews.get(highlightedIndex), INACTIVE_LINE_ALPHA);
            lineViews.get(highlightedIndex).setTextColor(unsungWordColor());
            for (int b = 1; highlightedIndex + b < lineViews.size()
                    && highlightedIndex + b < current.lines().size()
                    && current.lines().get(highlightedIndex + b).isBG(); b++) {
                fadeTo(lineViews.get(highlightedIndex + b), INACTIVE_LINE_ALPHA);
                lineViews.get(highlightedIndex + b).setTextColor(unsungWordColor());
            }
        }
        highlightedIndex = index;
        seekPending = false;

        if (index < 0 || index >= lineViews.size()) {
            applyLineOverlay(index);
            if (index < 0 && !lineRows.isEmpty()
                    && SystemClock.uptimeMillis() >= userScrollUntilUptimeMs) {
                scrollAnchorIntoView(0, true);
            }
            return;
        }

        applyLineOverlay(index);

        fadeTo(lineViews.get(index), 1f);
        lineViews.get(index).setTextColor(lineTextColor());
        for (int b = 1; index + b < lineViews.size()
                && index + b < current.lines().size()
                && current.lines().get(index + b).isBG(); b++) {
            fadeTo(lineViews.get(index + b), 1f);
            lineViews.get(index + b).setTextColor(lineTextColor());
        }

        if (SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
            return;
        }
        scrollAnchorIntoView(index, true);
    }

    private void updateWordSync(long positionMs) {
        boolean enabled = Settings.LYRICS_WORD_SYNC.get();
        if (enabled != wordSyncWasEnabled) {
            if (!enabled) {
                int count = Math.min(lineWordSpans.size(), lineViews.size());
                for (int i = 0; i < count; i++) {
                    lineViews.get(i).setTextColor(
                            i == highlightedIndex ? lineTextColor() : unsungWordColor());
                    ForegroundColorSpan cached = i < lineUnsungSpans.size()
                            ? lineUnsungSpans.get(i) : null;
                    if (cached != null && lineViews.get(i).getText() instanceof Spannable) {
                        ((Spannable) lineViews.get(i).getText()).removeSpan(cached);
                        lineUnsungSpans.set(i, null);
                    }
                    if (lineViews.get(i) instanceof LyricsLineView) {
                        ((LyricsLineView) lineViews.get(i)).setHighlight(
                                Collections.emptyList(), 0, false, 0, 0, -1);
                    }
                }
                lastWordLineIndex = -1;
                pendingOldWordLineIndex = -1;
                wordSyncWasEnabled = enabled;
                return;
            }
            wordSyncWasEnabled = enabled;
        }
        if (!enabled) {
            return;
        }
        int active = highlightedIndex;

        int count = Math.min(lineWordSpans.size(), lineViews.size());
        if (active < 0 || active >= count) {
            if (lastWordLineIndex >= 0) {
                clearWordHighlight(lastWordLineIndex);
            }
            lastWordLineIndex = -1;
            pendingOldWordLineIndex = -1;
            return;
        }

        if (pendingOldWordLineIndex == active) {
            pendingOldWordLineIndex = -1;
        }
        if (pendingOldWordLineIndex >= 0 && pendingOldWordLineIndex < count) {
            List<WordTiming> pendingTimings = lineWordSpans.get(pendingOldWordLineIndex);
            if (!pendingTimings.isEmpty()) {
                final long lastEnd = pendingTimings.get(pendingTimings.size() - 1).endMs();
                final long firstStart = pendingTimings.get(0).startMs();
                if (positionMs >= lastEnd || positionMs < firstStart) {
                    clearWordHighlight(pendingOldWordLineIndex);
                    fadeTo(lineViews.get(pendingOldWordLineIndex), INACTIVE_LINE_ALPHA);
                    pendingOldWordLineIndex = -1;
                } else {
                    applyWordColors(pendingOldWordLineIndex, positionMs, false);
                    applyBgWordColors(pendingOldWordLineIndex, positionMs, false);
                }
            } else {
                fadeTo(lineViews.get(pendingOldWordLineIndex), INACTIVE_LINE_ALPHA);
                pendingOldWordLineIndex = -1;
            }
        }

        if (lineWordSpans.get(active).isEmpty()) {
            if (lastWordLineIndex >= 0 && lastWordLineIndex != active) {
                List<WordTiming> oldTimings = lineWordSpans.get(lastWordLineIndex);
                if (!oldTimings.isEmpty()) {
                    final long lastEnd = oldTimings.get(oldTimings.size() - 1).endMs();
                    final long firstStart = oldTimings.get(0).startMs();
                    if (positionMs < lastEnd && positionMs >= firstStart) {
                        pendingOldWordLineIndex = lastWordLineIndex;
                    } else {
                        clearWordHighlight(lastWordLineIndex);
                    }
                } else {
                    clearWordHighlight(lastWordLineIndex);
                }
            }
            lastWordLineIndex = active;
            applyWordColors(active, 0, true);
            applyBgWordColors(active, 0, true);
            return;
        }

        if (active != lastWordLineIndex) {
            if (lastWordLineIndex >= 0) {
                List<WordTiming> oldTimings = lineWordSpans.get(lastWordLineIndex);
                if (!oldTimings.isEmpty()) {
                    final long lastEnd = oldTimings.get(oldTimings.size() - 1).endMs();
                    final long firstStart = oldTimings.get(0).startMs();
                    if (positionMs < lastEnd && positionMs >= firstStart) {
                        pendingOldWordLineIndex = lastWordLineIndex;
                    } else {
                        clearWordHighlight(lastWordLineIndex);
                    }
                } else {
                    clearWordHighlight(lastWordLineIndex);
                }
            }
            lastWordLineIndex = active;
        }

        applyWordColors(active, positionMs, false);
        applyBgWordColors(active, positionMs, false);
    }

    private void applyBgWordColors(int parentIndex, long positionMs, boolean allSung) {
        if (lyrics == null) return;
        List<LyricsLine> lines = lyrics.lines();
        for (int i = parentIndex + 1; i < lines.size() && lines.get(i).isBG(); i++) {
            applyWordColors(i, positionMs, allSung);
        }
    }

    private void resetBgWordColors(int parentIndex) {
        if (lyrics == null) return;
        List<LyricsLine> lines = lyrics.lines();
        for (int i = parentIndex + 1; i < lines.size() && lines.get(i).isBG(); i++) {
            applyWordColors(i, Long.MIN_VALUE, false);
        }
    }

    private void clearWordHighlight(int lineIndex) {
        applyWordColors(lineIndex, Long.MIN_VALUE, false);
        resetBgWordColors(lineIndex);
    }

    private int computeOriginalTextStart(LyricsLine line, int index, OnlyMode onlySnap,
            @Nullable List<LyricsLine> romanizedSnap, @Nullable List<String> translatedSnap,
            boolean perWordRomajiSnap) {
        if (onlySnap != OnlyMode.NONE) {
            return 0;
        }
        String original = line.text();
        String originalTrimmed = original.trim();
        final boolean usePerWord = perWordRomajiSnap && line.hasWords() && lineHasWordRomaji(line);
        String romanization = null;
        if (!usePerWord && romanizedSnap != null && index < romanizedSnap.size()) {
            String roma = romanizedSnap.get(index).text().trim();
            if (!roma.isEmpty() && !roma.equalsIgnoreCase(originalTrimmed)) {
                romanization = roma;
            }
        }
        String translation = null;
        if (translatedSnap != null && index < translatedSnap.size()) {
            String t = translatedSnap.get(index).trim();
            if (!t.isEmpty() && !t.equalsIgnoreCase(originalTrimmed)) {
                translation = t;
            }
        }
        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        String above = swap ? translation : romanization;
        if (above != null) {
            return above.length() + 1;
        }
        return 0;
    }

    private void applyWordColors(int index, long positionMs, boolean allSung) {
        if (index < 0 || index >= lineWordSpans.size() || index >= lineViews.size()) {
            return;
        }

        List<WordTiming> timings = lineWordSpans.get(index);
        int origStart = index < lineOriginalStarts.size() ? lineOriginalStarts.get(index) : 0;
        TextView lineView = lineViews.get(index);

        if (positionMs == Long.MIN_VALUE && lineView.getText() instanceof Spannable) {
            ForegroundColorSpan cached = index < lineUnsungSpans.size()
                    ? lineUnsungSpans.get(index) : null;
            if (cached != null) {
                ((Spannable) lineView.getText()).removeSpan(cached);
                lineUnsungSpans.set(index, null);
            }
        }

        if (lineView instanceof LyricsLineView) {
            ((LyricsLineView) lineView).setHighlight(
                    timings, positionMs, allSung, unsungWordColor(), lineTextColor(), origStart);
        }
    }

    /** Eases the highlight between lines the way the built-in panel does. */
    private static void fadeTo(TextView lineView, float alpha) {
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

    private static void applyFooterStyle(TextView footer) {
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, FOOTER_TEXT_SIZE_SP);
        footer.setTextColor(secondaryTextColor());
        // The secondary color alone is brighter than the app draws this line, which
        // sits dimmer than even the inactive lyrics above it.
        footer.setAlpha(FOOTER_ALPHA);
    }

    /**
     * Styles the button as a pill, the shape the app uses for the buttons under its
     * own lyrics, with the background taken from the app palette so it follows the theme.
     *
     * @param iconName Drawable name for the button icon, or {@code null} for a text only button.
     */
    private void applyButtonStyle(TextView button, @Nullable String iconName) {
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SIZE_SP);
        button.setTextColor(lineTextColor());
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Dim.dp16, Dim.dp6, Dim.dp16, Dim.dp6);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(Dim.dp20);
        background.setColor(ResourceUtils.getColor(APP_BUTTON_BACKGROUND_COLOR, 0x1AFFFFFF));
        button.setBackground(background);

        ViewAnimations.applyPressEffect(button);

        if (iconName == null || iconName.isEmpty()) {
            return;
        }

        // The drawable is themed with an attribute the panel context does not carry,
        // so it is tinted explicitly to match the button label.
        Drawable icon = ResourceUtils.getDrawable(iconName);
        if (icon == null) {
            Logger.printDebug(() -> "Missing icon: " + iconName);
            return;
        }
        icon = icon.mutate();
        icon.setTint(lineTextColor());
        final int iconSize = Dim.dp24;
        icon.setBounds(0, 0, iconSize, iconSize);
        button.setCompoundDrawablesRelative(icon, null, null, null);
        // No text yet (icon-only default): without padding the icon stays centred.
        button.setCompoundDrawablePadding(0);
    }

    private void applyButtonAppearance(TextView button, boolean active) {
        Drawable icon = button.getCompoundDrawablesRelative()[0];
        if (icon != null) {
            // Reserve padding for the label only when one is actually shown, otherwise
            // the reserved space pushes the icon to the left of the pill.
            CharSequence currentText = button.getText();
            button.setCompoundDrawablePadding(
                    currentText != null && currentText.length() > 0 ? Dim.dp8 : 0);
        }

        fadeButtonColors(button,
                active ? ACTIVE_BUTTON_BG_COLOR
                        : ResourceUtils.getColor(APP_BUTTON_BACKGROUND_COLOR, 0x1AFFFFFF),
                active ? ThemeUtils.getAppBackgroundColor() : lineTextColor());
    }

    /**
     * Eases a button between its inactive and active colors. The pill, the label and the
     * icon all change at once, so they are driven by a single animator.
     */
    private static void fadeButtonColors(TextView button, int background, int foreground) {
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
        final Drawable icon = button.getCompoundDrawablesRelative()[0];

        // Nothing to ease from before the panel is on screen, or when nothing changed.
        if (!button.isAttachedToWindow()
                || (fromBackground == background && fromForeground == foreground)) {
            setButtonColors(button, pill, icon, background, foreground);
            return;
        }

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(BUTTON_STATE_FADE_MILLISECONDS);
        animator.addUpdateListener(update -> {
            final float fraction = update.getAnimatedFraction();
            setButtonColors(button, pill, icon,
                    (int) BUTTON_COLOR_EVALUATOR.evaluate(fraction, fromBackground, background),
                    (int) BUTTON_COLOR_EVALUATOR.evaluate(fraction, fromForeground, foreground));
        });
        button.setTag(animator);
        animator.start();
    }

    private static void setButtonColors(TextView button, GradientDrawable pill,
                                        @Nullable Drawable icon, int background, int foreground) {
        pill.setColor(background);
        button.setTextColor(foreground);
        if (icon != null) {
            icon.mutate().setTint(foreground);
            button.invalidate();
        }
    }

    /**
     * Sets a button's text label and whether it is in the active (white background, app
     * background color foreground) state. A {@code null} or empty text collapses the button back to icon-only, but the active
     * state is independent of the label: a button can be active and icon-only (e.g. translation or
     * romanization is on) or flash a label while staying active.
     */
    private void setButtonLabel(@Nullable TextView button, @Nullable String text, boolean active) {
        if (button == null) {
            return;
        }
        button.setText(text == null ? "" : text);
        applyButtonAppearance(button, active);
    }

    private static int secondaryTextColor() {
        // The karaoke highlight needs a color that visibly differs from the sung
        // (primary) color. Prefer the app's secondary text color, but if that
        // resource is unavailable fall back to a dimmed primary so the effect is
        // always visible instead of collapsing to the sung color.
        int secondary = ResourceUtils.getColor(APP_SECONDARY_TEXT_COLOR, 0);
        if (secondary != 0) {
            return secondary;
        }
        int base = lineTextColor();
        return Color.argb(0x66, Color.red(base), Color.green(base), Color.blue(base));
    }

    private static int cachedLineColor;
    private static int cachedUnsungColor;
    private static boolean colorCacheValid;

    private static void ensureColorCache() {
        if (!colorCacheValid) {
            cachedLineColor = computeLineColor();
            cachedUnsungColor = Color.argb(Math.round(UNSUNG_ALPHA * 255f),
                    Color.red(cachedLineColor), Color.green(cachedLineColor), Color.blue(cachedLineColor));
            colorCacheValid = true;
        }
    }

    private static int unsungWordColor() {
        ensureColorCache();
        return cachedUnsungColor;
    }

    /**
     * Color the app uses for lyrics text, falling back to the generic foreground color.
     */
    private static int lineTextColor() {
        ensureColorCache();
        return cachedLineColor;
    }

    private static int computeLineColor() {
        final int colorId = ResourceUtils.getIdentifier(ResourceType.COLOR, APP_PRIMARY_TEXT_COLOR);
        if (colorId == 0) {
            return ThemeUtils.getAppForegroundColor();
        }
        return ResourceUtils.getColor(APP_PRIMARY_TEXT_COLOR, ThemeUtils.getAppForegroundColor());
    }

    private static boolean hasTranslation(@Nullable List<String> translated, List<LyricsLine> originals) {
        if (translated == null) {
            return false;
        }
        final int size = Math.min(translated.size(), originals.size());
        for (int i = 0; i < size; i++) {
            String text = translated.get(i);
            if (!text.isEmpty() && !text.equals(originals.get(i).text())) {
                return true;
            }
        }
        return false;
    }

    private static String sourceText(String providerName, boolean translated,
            boolean translatedFromGoogle, boolean translatedFromAI, @Nullable String translatedAiModel,
            boolean romanized, boolean romanizedFromGoogle, boolean romanizedFromAI,
            @Nullable String romanizedAiModel, OnlyMode onlyMode) {
        String text = String.format(str(LYRICS_SOURCE_KEY), providerName);
        if (onlyMode != OnlyMode.ROMA) {
            if (translated && translatedFromGoogle) {
                text += "\n" + str("morphe_music_lyrics_translated_by_google");
            } else if (translated && translatedFromAI && translatedAiModel != null) {
                text += "\n" + String.format(str("morphe_music_lyrics_translated_by_ai"),
                        translatedAiModel);
            }
        }
        if (onlyMode != OnlyMode.TRANS && romanized) {
            if (romanizedFromGoogle) {
                text += "\n" + str("morphe_music_lyrics_romanized_by_google");
            } else if (romanizedFromAI && romanizedAiModel != null) {
                text += "\n" + String.format(str("morphe_music_lyrics_romanized_by_ai"),
                        romanizedAiModel);
            }
        }
        return text;
    }

    private final class OffsetRulerView extends View {
        private static final int RANGE_MS = 20000;

        private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int currentOffsetMs;

        OffsetRulerView(Context context) {
            super(context);
            valuePaint.setColor(Color.WHITE);
            valuePaint.setTextAlign(Paint.Align.CENTER);
            valuePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            setVisibility(GONE);
        }

        void setOffsetMs(int ms) {
            currentOffsetMs = Math.max(-RANGE_MS, Math.min(RANGE_MS, ms));
            invalidate();
        }

        int getOffsetMs() {
            return currentOffsetMs;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = (int) (28 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float density = getResources().getDisplayMetrics().density;

            String text = (currentOffsetMs >= 0 ? "+" : "") + currentOffsetMs + "ms";
            valuePaint.setTextSize(13 * density);
            canvas.drawText(text, w / 2f, h / 2f + 5 * density, valuePaint);
        }

    }
}
