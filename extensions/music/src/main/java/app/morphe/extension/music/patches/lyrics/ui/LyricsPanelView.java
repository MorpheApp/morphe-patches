/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

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
import android.graphics.Paint;
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
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.ViewGroup;
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

    private static final float INACTIVE_LINE_ALPHA = 0.45f;

    /** Applied on top of the secondary color, which alone is brighter than the app draws it. */
    private static final float FOOTER_ALPHA = 0.6f;

    /** Fade length when the highlight moves from one line to the next. */
    private static final long HIGHLIGHT_FADE_DURATION_MILLISECONDS = 200;

    /** Fade length when the panel appears over the built-in content. */
    private static final long OVERLAY_FADE_DURATION_MILLISECONDS = 150;

    /** How long auto scrolling stays off after the user touches the panel. */
    private static final long MANUAL_SCROLL_PAUSE_MILLISECONDS = 5000;

    private static final long OVERLAY_CACHE_TTL_MS = 300;

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
    /** Active (feature on) button foreground: pure black, readable on white. */
    private static final int ACTIVE_BUTTON_FG_COLOR = 0xFF000000;
    /** How long a button takes to cross between its inactive and active colors. */
    private static final long BUTTON_STATE_FADE_MILLISECONDS = 150;
    private static final ArgbEvaluator BUTTON_COLOR_EVALUATOR = new ArgbEvaluator();

    /** Alpha channel for the unsung (not-yet-sung) word color. */
    private static final int UNSUNG_ALPHA = 0x66;

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

    private final Handler handler = new Handler(Looper.getMainLooper());

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
    /** When true, romanization is carried per-word on each {@link Word} (rendered above each word). */
    private boolean perWordRomaji;
    /** URL to the song page on the provider's platform, opened when the source label is clicked. */
    @Nullable
    private String currentSourceUrl;
    /** When true, the next LOADED state was triggered by a refresh/cycle action. */
    private boolean refreshInProgress;
    private boolean translateInProgress;
    private boolean romanizeInProgress;

    private final List<TextView> lineViews = new ArrayList<>();

    /** Wrapper holding the optional romanization line above each line of lyrics. */
    private final List<View> lineRows = new ArrayList<>();

    private final List<List<WordTiming>> lineWordSpans = new ArrayList<>();
    private final List<Integer> lineOriginalStarts = new ArrayList<>();
    private final List<ForegroundColorSpan> lineUnsungSpans = new ArrayList<>();

    private static final ExecutorService LINE_BUILDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private int buildGeneration;

    private int lastWordLineIndex = -1;

    private int pendingOldWordLineIndex = -1;

    private boolean seekPending;

    private record WordTiming(int start, int end, long startMs, long endMs,
            @Nullable String romaji) {
    }

    private record BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan) {
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

    private static final class LyricsLineView extends TextView {
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
        private int cachedWordCount;
        private int cachedLineCount;
        private int[] cachedLineTop;
        private int[] cachedLineBottom;
        private float[] cachedLineLeft;
        private float[] cachedLineRight;
        private int cachedOrigStart;

        private CharSequence cachedText;
        private String cachedTextStr;

        /** The same text painted in the sung color, drawn clipped over the unsung text. */
        private Layout sungLayout;
        private CharSequence sungLayoutText;
        private int sungLayoutWidth = -1;
        private int sungLayoutColor;
        private int sungLayoutOrigStart = -1;

        LyricsLineView(Context context) {
            super(context);
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
                sungLayout = null;
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
            for (int i = 0; i < cachedWordCount; i++) {
                final WordTiming timing = timings.get(i);
                final int s = timing.start() + origStart;
                final int e = Math.min(timing.end() + origStart, text.length());
                if (s >= text.length() || s >= e) {
                    continue;
                }
                final int line = layout.getLineForOffset(s);
                final float lead = layout.getPrimaryHorizontal(s);
                float trail = layout.getPrimaryHorizontal(e);
                // A word broken across visual lines, or one whose end falls on a bidi
                // boundary, gets its width measured instead of read off the layout.
                if (trail == lead || layout.getLineForOffset(e) != line) {
                    final float width = paint.measureText(text, s, e);
                    trail = layout.isRtlCharAt(s) ? lead - width : lead + width;
                }
                cachedWordLeadX[i] = lead;
                cachedWordTrailX[i] = trail;
                cachedWordLine[i] = line;
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

        /**
         * Builds the sung copy of the text. Drawing it as a layout keeps Arabic and Hebrew
         * shaped and ordered, which {@link Canvas#drawText} cannot do because it paints the
         * characters in logical order from a single left edge.
         */
        private Layout ensureSungLayout(Layout base, CharSequence text, int origStart) {
            if (sungLayout != null && sungLayoutText == text
                    && sungLayoutWidth == base.getWidth()
                    && sungLayoutColor == sungColor
                    && sungLayoutOrigStart == origStart) {
                return sungLayout;
            }

            SpannableString copy = new SpannableString(text);
            final int end = originalEnd(copy, origStart);
            if (origStart < end) {
                for (ForegroundColorSpan span : copy.getSpans(origStart, end, ForegroundColorSpan.class)) {
                    copy.removeSpan(span);
                }
                copy.setSpan(new ForegroundColorSpan(sungColor), origStart, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            sungLayout = StaticLayout.Builder
                    .obtain(copy, 0, copy.length(), getPaint(), base.getWidth())
                    .setAlignment(base.getAlignment())
                    .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
                    .setIncludePad(getIncludeFontPadding())
                    .setBreakStrategy(getBreakStrategy())
                    .setHyphenationFrequency(getHyphenationFrequency())
                    .build();
            sungLayoutText = text;
            sungLayoutWidth = base.getWidth();
            sungLayoutColor = sungColor;
            sungLayoutOrigStart = origStart;
            return sungLayout;
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

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (unsungColor == 0) {
                return;
            }
            Layout layout = getLayout();
            if (layout == null) {
                return;
            }
            CharSequence text = getText();
            Paint tp = getPaint();
            final int origStart = originalTextStart;
            final int paddingLeft = getCompoundPaddingLeft();
            final int paddingTop = getExtendedPaddingTop();

            ensureWordCache(layout, text, tp, wordTimings, origStart);

            // Track how far the highlight reached on each visual line, so wrapped lines
            // and right to left scripts both fill from the edge their text starts at.
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

            Layout sung = ensureSungLayout(layout, text, origStart);

            canvas.save();
            canvas.translate(paddingLeft, paddingTop);
            for (int ln = 0; ln < lineCount; ln++) {
                final float edge = lineMaxSungX[ln];
                if (Float.isNaN(edge)) {
                    continue;
                }
                canvas.save();
                if (isRightToLeftLine(layout, ln)) {
                    canvas.clipRect(edge, cachedLineTop[ln],
                            cachedLineRight[ln], cachedLineBottom[ln]);
                } else {
                    canvas.clipRect(cachedLineLeft[ln], cachedLineTop[ln],
                            edge, cachedLineBottom[ln]);
                }
                sung.draw(canvas);
                canvas.restore();
            }
            canvas.restore();
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

    private long computeTickInterval() {
        String rate = SharedYouTubeSettings.APP_REFRESH_RATE.get();
        if ("DEFAULT".equals(rate)) {
            float deviceRate = 0f;
            try {
                android.view.Display display = getDisplay();
                if (display != null) {
                    deviceRate = display.getRefreshRate();
                }
            } catch (Exception ignored) {
            }
            if (deviceRate > 0f) {
                return Math.round(1000f / deviceRate);
            }
            return 16;
        }
        try {
            int fps = Integer.parseInt(rate);
            if (fps > 0) {
                return Math.round(1000f / fps);
            }
        } catch (NumberFormatException ignored) {
        }
        return 16;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                updateHighlight();
                updateWordSync(LyricsManager.getInstance().getPositionMs());

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

        footerView = new TextView(context);
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

        creditView = new TextView(context);
        applyFooterStyle(creditView);
        creditView.setVisibility(GONE);
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
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        buttonRowParams.bottomMargin = Dim.dp40;
        addView(buttonRow, buttonRowParams);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // Any touch counts as manual interaction, so auto scrolling backs off
        // instead of fighting the user. The event itself is left untouched.
        userScrollUntilUptimeMs = SystemClock.uptimeMillis() + MANUAL_SCROLL_PAUSE_MILLISECONDS;
        lastScrollTarget = -1;
        return super.onInterceptTouchEvent(event);
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
        LyricsManager.getInstance().removeListener(this);
        handler.removeCallbacksAndMessages(null);
        if (!LyricsPanelInstaller.isOtherPanelForeground()) {
            restoreHiddenSiblings();
        }
    }

    @Override
    public void onLyricsChanged(LyricsManager.State state, @Nullable Lyrics newLyrics) {
        try {
            lyrics = newLyrics;
            highlightedIndex = -1;
            pendingOldWordLineIndex = -1;
            userScrollUntilUptimeMs = 0;
            translatedLines = null;
            romanizedLines = null;
            romanizedFromGoogle = false;
            translatedFromGoogle = false;
            perWordRomaji = false;
            translateInProgress = false;
            romanizeInProgress = false;

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
                        if (Settings.LYRICS_TRANSLATE.get()) {
                            onTranslateClicked();
                        }
                        if (Settings.LYRICS_ROMANIZE.get()) {
                            onRomanizeClicked();
                        }
                        if (refreshInProgress) {
                            refreshInProgress = false;
                            setButtonLabel(refreshView, str("morphe_music_lyrics_refreshed"), true);
                            handler.postDelayed(this::updateRefreshLabel, 1500);
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
        clearLines();
        colorCacheValid = false;
        progressBar.setVisibility(GONE);
        scrollView.setVisibility(VISIBLE);

        Context context = getContext();
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int foregroundColor = lineTextColor();
        final boolean tapToSeek = newLyrics.synced() && Settings.LYRICS_TAP_TO_SEEK.get();

        final int generation = buildGeneration;

        for (int i = 0; i < newLyrics.lines().size(); i++) {
            LyricsLine line = newLyrics.lines().get(i);
            // Placeholder until the background pass fills in the real timings.
            lineWordSpans.add(new ArrayList<>());
            lineOriginalStarts.add(0);

            LyricsLineView lineView = new LyricsLineView(context);
            // Plain text first so the panel paints immediately; the karaoke spans are added on a
            // background thread (see the LINE_BUILDER_EXECUTOR pass below).
            lineView.setText(line.text().isEmpty() ? "♪" : line.text());
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
            lineView.setTextColor(foregroundColor);
            lineView.setAlpha(newLyrics.synced() ? INACTIVE_LINE_ALPHA : 1f);
            lineView.setPadding(0, Dim.dp8, 0, Dim.dp8);
            lineView.setTypeface(null, Typeface.BOLD);

            if (Settings.LYRICS_WORD_SYNC.get() && newLyrics.synced()) {
                lineView.setTextColor(unsungWordColor());
            }

            if (tapToSeek) {
                final long seekTime = line.startTimeMs();
                lineView.setOnClickListener(view -> {
                    if (!VideoInformation.seekTo(seekTime)) {
                        Logger.printDebug(() -> "Seek to lyrics line failed: " + seekTime);
                    }
                    userScrollUntilUptimeMs = 0;
                    seekPending = true;
                });
            }

            lineView.setOnLongClickListener(v -> {
                String textToCopy = line.text();
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
                lineView.setGravity(Gravity.END);
            } else {
                lineView.setGravity(Gravity.START);
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
            final int lineCount = newLyrics.lines().size();
            List<List<WordTiming>> allTimings = new ArrayList<>(lineCount);
            List<Integer> allOrigStarts = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                allTimings.add(computeWordTimings(newLyrics.lines().get(i)));
                allOrigStarts.add(computeOriginalTextStart(newLyrics.lines().get(i), i));
            }
            handler.post(() -> {
                if (generation != buildGeneration || lyrics != newLyrics) {
                    return;
                }
                final int count = Math.min(allTimings.size(), lineViews.size());
                for (int i = 0; i < count; i++) {
                    lineWordSpans.set(i, allTimings.get(i));
                    lineOriginalStarts.set(i, allOrigStarts.get(i));
                    TextView tv = lineViews.get(i);
                    if (i < newLyrics.lines().size()) {
                        BuildResult result = buildLineText(newLyrics.lines().get(i),
                                allTimings.get(i), i);
                        tv.setText(result.text());
                        lineUnsungSpans.set(i, result.unsungSpan());
                    }
                }
            });
        });

        currentSourceUrl = newLyrics.sourceUrl();
        footerView.setText(sourceText(newLyrics.providerName(),
                translatedLines != null, translatedFromGoogle, romanizedFromGoogle));
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
        }

        scrollView.scrollTo(0, 0);
    }

    private static List<WordTiming> computeWordTimings(LyricsLine line) {
        if (!line.hasWords() || line.words().size() == 1) {
            return Collections.emptyList();
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
            return new BuildResult(text, unsungSpan);
        }
        if (romanization != null) {
            romaStart = 0;
            builder.append(romanization);
            builder.append('\n');
            romaEnd = builder.length();
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
        return new BuildResult(text, unsungSpan);
    }

    @Nullable
    private static ForegroundColorSpan applySpans(SpannableString text, List<WordTiming> timings,
            int originalStart, int originalEnd,
            int romaStart, int romaEnd, int transStart, int transEnd,
            boolean usePerWord) {
        ForegroundColorSpan unsungSpan = null;
        if (romaStart >= 0) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), romaStart, romaEnd - 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(secondaryTextColor()), romaStart, romaEnd - 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (transStart >= 0) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), transStart, transEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(secondaryTextColor()), transStart, transEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (Settings.LYRICS_WORD_SYNC.get() && !timings.isEmpty()) {
            int unsung = unsungWordColor();
            unsungSpan = new ForegroundColorSpan(unsung);
            text.setSpan(unsungSpan, originalStart, originalEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (usePerWord) {
            final int romajiColor = secondaryTextColor();
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

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            if (translateInProgress) {
                translateInProgress = false;
                Settings.LYRICS_TRANSLATE.save(false);
                translatedLines = null;
                translatedFromGoogle = false;
                setButtonLabel(translateView, null, false);
                return;
            }

            if (translatedLines != null) {
                Settings.LYRICS_TRANSLATE.save(false);
                translatedLines = null;
                translatedFromGoogle = false;
                showLyrics(current);
                return;
            }

            Settings.LYRICS_TRANSLATE.save(true);
            translateInProgress = true;
            setButtonLabel(translateView, str("morphe_music_lyrics_translating"), true);

            LyricsTranslator.translate(track, current, current.providerName(), (lines, fromGoogle) -> {
                if (!translateInProgress) {
                    return;
                }
                translateInProgress = false;

                // The track may have changed while the translation was in flight.
                if (lyrics != current) {
                    return;
                }

                translatedLines = hasTranslation(lines, current.lines()) ? lines : null;
                translatedFromGoogle = translatedLines != null && fromGoogle;
                if (lines == null) {
                    Utils.showToastShort(str("morphe_music_lyrics_translate_failed"));
                }
                showLyrics(current);
                if (translatedLines != null) {
                    setButtonLabel(translateView, str("morphe_music_lyrics_translate_hide"), true);
                    handler.postDelayed(this::updateTranslateLabel, 3000);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onTranslateClicked failure", ex);
        }
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
        } catch (Exception ignored) {
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
            Lyrics current = lyrics;
            if (current == null || current.rawFormat() == null) {
                return;
            }
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (track == null) {
                return;
            }
            String savedPath = LyricsFileSaver.save(getContext(), track, current);
            if (savedPath != null) {
                Utils.showToastShort("Saved to " + savedPath);
                setButtonLabel(copyView, str("morphe_music_lyrics_saved"), true);
                handler.postDelayed(() -> setButtonLabel(copyView, null, false), 1500);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateTranslateLabel() {
        if (translateView != null) {
            final boolean on = translatedLines != null;
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

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            if (romanizeInProgress) {
                romanizeInProgress = false;
                Settings.LYRICS_ROMANIZE.save(false);
                romanizedLines = null;
                perWordRomaji = false;
                romanizedFromGoogle = false;
                setButtonLabel(romanizeView, null, false);
                return;
            }

            if (romanizedLines != null || perWordRomaji) {
                Settings.LYRICS_ROMANIZE.save(false);
                romanizedLines = null;
                perWordRomaji = false;
                romanizedFromGoogle = false;
                showLyrics(current);
                return;
            }

            Settings.LYRICS_ROMANIZE.save(true);
            romanizeInProgress = true;
            setButtonLabel(romanizeView, str("morphe_music_lyrics_romanizing"), true);

            LyricsRomanizer.romanize(track, current, current.providerName(),
                    (lines, fromGoogle, perWord) -> {
                if (!romanizeInProgress) {
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
                perWordRomaji = romaOk && perWord;
                if (lines == null && !perWord) {
                    Utils.showToastShort(str("morphe_music_lyrics_romanize_failed"));
                }
                showLyrics(current);
                if (romaOk) {
                    setButtonLabel(romanizeView, str("morphe_music_lyrics_romanize_hide"), true);
                    handler.postDelayed(this::updateRomanizeLabel, 3000);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void updateRomanizeLabel() {
        if (romanizeView != null) {
            final boolean on = romanizedLines != null || perWordRomaji;
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

    private void clearLines() {
        buildGeneration++;
        for (TextView lineView : lineViews) {
            // A running fade would otherwise keep a reference to a removed view.
            lineView.animate().cancel();
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
        pendingOldWordLineIndex = -1;
        lastScrollTarget = -1;
    }

    private void updateHighlight() {
        Lyrics current = lyrics;
        if (current == null || !current.synced() || lineViews.isEmpty()) {
            return;
        }

        LyricsManager manager = LyricsManager.getInstance();
        final long pos = manager.getPositionMs();
        final int index = current.indexForPosition(pos, highlightedIndex);
        if (index == highlightedIndex) {
            if (highlightedIndex >= 0 && highlightedIndex < lineViews.size()
                    && !seekPending
                    && SystemClock.uptimeMillis() >= userScrollUntilUptimeMs) {
                final int target = lineRows.get(highlightedIndex).getTop()
                        + lineViews.get(highlightedIndex).getTop()
                        - scrollView.getHeight() / SCROLL_OFFSET_FRACTION;
                final int clamped = Math.max(0, target);
                final int dist = Math.abs(scrollView.getScrollY() - clamped);
                if (dist > scrollView.getHeight() * SCROLL_INSTANT_THRESHOLD_FACTOR) {
                    scrollView.scrollTo(0, clamped);
                    lastScrollTarget = clamped;
                } else if (dist > scrollView.getHeight() / SCROLL_SMOOTH_THRESHOLD_FACTOR
                        && clamped != lastScrollTarget) {
                    scrollView.smoothScrollTo(0, clamped);
                    lastScrollTarget = clamped;
                }
            }
            return;
        }

        if (highlightedIndex >= 0 && highlightedIndex < lineViews.size()) {
            boolean keepFullOpacity = false;
            if (Settings.LYRICS_WORD_SYNC.get()
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
                for (int b = 1; highlightedIndex + b < lineViews.size()
                        && highlightedIndex + b < current.lines().size()
                        && current.lines().get(highlightedIndex + b).isBG(); b++) {
                    fadeTo(lineViews.get(highlightedIndex + b), INACTIVE_LINE_ALPHA);
                }
            }
        }
        highlightedIndex = index;
        seekPending = false;

        if (index < 0 || index >= lineViews.size()) {
            return;
        }

        fadeTo(lineViews.get(index), 1f);
        for (int b = 1; index + b < lineViews.size()
                && index + b < current.lines().size()
                && current.lines().get(index + b).isBG(); b++) {
            fadeTo(lineViews.get(index + b), 1f);
        }

        final boolean hidePlayed = Settings.LYRICS_HIDE_PLAYED.get();
        final boolean hideUnplayed = Settings.LYRICS_HIDE_UNPLAYED.get();
        boolean visibilityChanged = false;
        if (hidePlayed || hideUnplayed) {
            for (int i = 0; i < lineRows.size(); i++) {
                final int newVis;
                if (hidePlayed && i < index) {
                    newVis = GONE;
                } else if (hideUnplayed && i > index) {
                    newVis = GONE;
                } else {
                    newVis = VISIBLE;
                }
                if (lineRows.get(i).getVisibility() != newVis) {
                    visibilityChanged = true;
                }
                lineRows.get(i).setVisibility(newVis);
            }
        }

        if (SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
            return;
        }

        final int target = lineRows.get(index).getTop() + lineViews.get(index).getTop()
                - scrollView.getHeight() / SCROLL_OFFSET_FRACTION;
        final int clamped = Math.max(0, target);
        if (visibilityChanged) {
            scrollView.scrollTo(0, clamped);
            lastScrollTarget = clamped;
        } else {
            final int dist = Math.abs(scrollView.getScrollY() - clamped);
            if (dist > scrollView.getHeight() * SCROLL_INSTANT_THRESHOLD_FACTOR) {
                scrollView.scrollTo(0, clamped);
                lastScrollTarget = clamped;
            } else if (clamped != lastScrollTarget) {
                scrollView.smoothScrollTo(0, clamped);
                lastScrollTarget = clamped;
            }
        }
    }

    private void updateWordSync(long positionMs) {
        boolean enabled = Settings.LYRICS_WORD_SYNC.get();
        if (enabled != wordSyncWasEnabled) {
            if (!enabled) {
                int count = Math.min(lineWordSpans.size(), lineViews.size());
                for (int i = 0; i < count; i++) {
                    lineViews.get(i).setTextColor(lineTextColor());
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

    private int computeOriginalTextStart(LyricsLine line, int index) {
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
        String translation = null;
        if (translatedLines != null && index < translatedLines.size()) {
            String t = translatedLines.get(index).trim();
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
        ViewPropertyAnimator a = lineView.animate();
        a.cancel();
        a.alpha(alpha)
                .setDuration(HIGHLIGHT_FADE_DURATION_MILLISECONDS)
                .start();
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
                active ? ACTIVE_BUTTON_FG_COLOR : lineTextColor());
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
     * Sets a button's text label and whether it is in the active (white background, dark icon)
     * state. A {@code null} or empty text collapses the button back to icon-only, but the active
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
            cachedUnsungColor = Color.argb(UNSUNG_ALPHA,
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
            boolean translatedFromGoogle, boolean romanizedFromGoogle) {
        String text = String.format(str(LYRICS_SOURCE_KEY), providerName);
        if (translated && translatedFromGoogle) {
            text += "\n" + str("morphe_music_lyrics_translated_by_google");
        }
        if (romanizedFromGoogle) {
            text += "\n" + str("morphe_music_lyrics_romanized_by_google");
        }
        return text;
    }
}
