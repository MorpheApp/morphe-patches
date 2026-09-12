/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

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
import app.morphe.extension.music.patches.lyrics.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.music.patches.lyrics.LyricsPanelInstaller;
import app.morphe.extension.music.patches.lyrics.LyricsTranslator;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
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

    /** How often the highlighted line is re-evaluated while playing. */
    private static final long TICK_INTERVAL_MILLISECONDS = 32;

    private static final float INACTIVE_LINE_ALPHA = 0.45f;

    /** Applied on top of the secondary color, which alone is brighter than the app draws it. */
    private static final float FOOTER_ALPHA = 0.6f;

    /** Fade length when the highlight moves from one line to the next. */
    private static final long HIGHLIGHT_FADE_DURATION_MILLISECONDS = 200;

    /** Fade length when the panel appears over the built-in content. */
    private static final long OVERLAY_FADE_DURATION_MILLISECONDS = 150;

    /** How long auto scrolling stays off after the user touches the panel. */
    private static final long MANUAL_SCROLL_PAUSE_MILLISECONDS = 5000;

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

    /** Wrapper holding the optional romanization line above each lyrics line. */
    private final List<View> lineRows = new ArrayList<>();

    private final List<List<WordTiming>> lineWordSpans = new ArrayList<>();
    private final List<Integer> lineOriginalStarts = new ArrayList<>();

    private static final ExecutorService lineBuilderExecutor = Executors.newSingleThreadExecutor();

    private int buildGeneration;

    private int lastWordLineIndex = -1;

    /** Previous line whose last word is still animating after the active line switched. */
    private int pendingOldWordLineIndex = -1;

    private static final class WordTiming {
        final int start;
        final int end;
        final long startMs;
        final long endMs;
        @Nullable final String romaji;

        WordTiming(int start, int end, long startMs, long endMs, @Nullable String romaji) {
            this.start = start;
            this.end = end;
            this.startMs = startMs;
            this.endMs = endMs;
            this.romaji = romaji;
        }
    }

    private static final class RomajiSpan extends ReplacementSpan {
        private final String romaji;
        private final int color;
        private final float relativeSize;
        private final Paint romajiPaint = new Paint();
        private final Paint.FontMetrics romajiFm = new Paint.FontMetrics();

        RomajiSpan(String romaji, int color, float relativeSize) {
            this.romaji = romaji;
            this.color = color;
            this.relativeSize = relativeSize;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            final int width = (int) Math.ceil(paint.measureText(text, start, end));
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
            return width;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, Paint paint) {
            romajiPaint.set(paint);
            romajiPaint.setTextSize(paint.getTextSize() * relativeSize);
            romajiPaint.setColor(color);

            final float wordWidth = paint.measureText(text, start, end);
            final float romajiWidth = romajiPaint.measureText(romaji);
            final float romajiX = x + Math.max(0f, (wordWidth - romajiWidth) / 2f);

            romajiPaint.getFontMetrics(romajiFm);
            final Paint.FontMetrics wordFm = paint.getFontMetrics();
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
            this.wordTimings = timings != null ? timings : Collections.emptyList();
            this.positionMs = posMs;
            this.allSung = sung;
            this.unsungColor = unsungCol;
            this.sungColor = sungCol;
            this.originalTextStart = origStart;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (unsungColor == 0) {
                return;
            }
            final Layout layout = getLayout();
            if (layout == null) {
                return;
            }
            final CharSequence text = getText();
            final Paint tp = getPaint();
            final int origStart = originalTextStart;
            final int paddingLeft = getCompoundPaddingLeft();
            final int paddingTop = getExtendedPaddingTop();

            // Track sung extent per visual line for multi-line wrap support.
            final int lineCount = layout.getLineCount();
            final float[] lineMaxSungX = new float[lineCount];
            Arrays.fill(lineMaxSungX, -1f);
            int firstSungLine = -1;

            for (WordTiming timing : wordTimings) {
                final int s = timing.start + origStart;
                final int e = Math.min(timing.end + origStart, text.length());
                if (s >= text.length() || s >= e) {
                    continue;
                }
                float progress;
                if (allSung) {
                    progress = 1f;
                } else if (positionMs >= timing.endMs) {
                    progress = 1f;
                } else if (positionMs <= timing.startMs) {
                    progress = 0f;
                } else {
                    progress = (float) (positionMs - timing.startMs)
                            / (float) (timing.endMs - timing.startMs);
                }
                if (progress <= 0f) {
                    continue;
                }
                final int lineNum = layout.getLineForOffset(s);
                if (firstSungLine < 0) {
                    firstSungLine = lineNum;
                }
                final float x = layout.getPrimaryHorizontal(s);
                float wordWidth = layout.getPrimaryHorizontal(e) - x;
                if (wordWidth <= 0f && e > s) {
                    wordWidth = tp.measureText(text, s, e);
                }
                if (wordWidth > 0f) {
                    lineMaxSungX[lineNum] = Math.max(lineMaxSungX[lineNum],
                            x + wordWidth * progress);
                }
            }

            if (allSung && firstSungLine < 0 && origStart < text.length()) {
                final String textStr = text.toString();
                final int origEnd = textStr.indexOf('\n', origStart);
                final int lastChar = (origEnd >= 0 ? origEnd : textStr.length()) - 1;
                final int firstLine = layout.getLineForOffset(origStart);
                final int lastLine = layout.getLineForOffset(
                        Math.max(lastChar, origStart));
                for (int ln = firstLine; ln <= lastLine; ln++) {
                    lineMaxSungX[ln] = layout.getLineRight(ln);
                }
                firstSungLine = firstLine;
            }

            for (int ln = 0; ln < lineCount; ln++) {
                if (lineMaxSungX[ln] <= 0f) {
                    continue;
                }
                final int lineStart = layout.getLineStart(ln);
                final int lineEnd = layout.getLineEnd(ln);
                final float baseline = layout.getLineBaseline(ln);
                final int lineTop = layout.getLineTop(ln);
                final int lineBottom = layout.getLineBottom(ln);
                final float textLeft = layout.getLineLeft(ln);
                final float clipRight = allSung
                        ? layout.getLineRight(ln) : lineMaxSungX[ln];
                canvas.save();
                canvas.translate(paddingLeft, paddingTop);
                canvas.clipRect(textLeft, lineTop, clipRight, lineBottom);
                tp.setColor(sungColor);
                tp.setShader(null);
                canvas.drawText(text, lineStart, lineEnd, textLeft, baseline, tp);
                canvas.restore();
            }
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

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                updateHighlight();
                updateWordSync(LyricsManager.getInstance().getPositionMs());

                // The app restores its own panel content asynchronously, and switching
                // to another engagement panel gives no lyrics state change to react to,
                // so the wanted state is reapplied on every tick rather than on changes.
                syncOverlay();
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, TICK_INTERVAL_MILLISECONDS);
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
     */
    public void syncOverlay() {
        applyOverlayVisibility();
    }

    private void applyOverlayVisibility() {
        final long now = SystemClock.uptimeMillis();
        if (now - overlayCacheUptimeMs > 300) {
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

        final Context context = getContext();
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int foregroundColor = lineTextColor();
        final boolean tapToSeek = newLyrics.synced() && Settings.LYRICS_TAP_TO_SEEK.get();

        final int generation = buildGeneration;
        final Lyrics source = newLyrics;

        for (int i = 0; i < newLyrics.lines().size(); i++) {
            final LyricsLine line = newLyrics.lines().get(i);
            final int index = i;
            // Placeholder until the background pass fills in the real timings.
            lineWordSpans.add(new ArrayList<>());
            lineOriginalStarts.add(0);

            LyricsLineView lineView = new LyricsLineView(context);
            // Plain text first so the panel paints immediately; the karaoke spans are added on a
            // background thread (see the lineBuilderExecutor pass below).
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
                    final long videoSeekTime = LyricsManager.getInstance().toVideoTime(seekTime);
                    if (!VideoInformation.seekTo(videoSeekTime)) {
                        Logger.printDebug(() -> "Seek to lyrics line failed: " + videoSeekTime);
                    }
                    userScrollUntilUptimeMs = 0;
                });
            }

            lineView.setOnLongClickListener(v -> {
                String textToCopy = line.text();
                if (textToCopy == null || textToCopy.isEmpty()) {
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
        }

        lineBuilderExecutor.execute(() -> {
            final int lineCount = source.lines().size();
            final List<List<WordTiming>> allTimings = new ArrayList<>(lineCount);
            final List<Integer> allOrigStarts = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                allTimings.add(computeWordTimings(source.lines().get(i)));
                allOrigStarts.add(computeOriginalTextStart(source.lines().get(i), i));
            }
            handler.post(() -> {
                if (generation != buildGeneration || lyrics != source) {
                    return;
                }
                final int count = Math.min(allTimings.size(), lineViews.size());
                for (int i = 0; i < count; i++) {
                    lineWordSpans.set(i, allTimings.get(i));
                    lineOriginalStarts.set(i, allOrigStarts.get(i));
                    final TextView tv = lineViews.get(i);
                    if (i < source.lines().size()) {
                        tv.setText(buildLineText(source.lines().get(i), allTimings.get(i),
                                i, Long.MIN_VALUE, false));
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
     * smaller, dimmer style and colouring each word sung or unsung for the karaoke
     * highlight.
     *
     * <p>A fresh {@link SpannableString} is returned on every call so that
     * {@link android.widget.TextView#setText(CharSequence)} performs a full re-layout
     * and repaint. Mutating an existing Spannable in place was not reliably redrawn by
     * this TextView, which left the highlight invisible.
     *
     * @param positionMs Current playback position, used to decide which words are sung.
     * @param allSung   When true every word is treated as sung, used to reset a line.
     */
    private Spannable buildLineText(LyricsLine line, List<WordTiming> timings, int index,
            long positionMs, boolean allSung) {
        String original = line.text();
        final String originalTrimmed = original.trim();

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
        final StringBuilder builder = new StringBuilder();
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
            final SpannableString text = new SpannableString(builder.toString());
            applySpans(text, timings, originalStart, originalEnd,
                    romaStart, romaEnd, transStart, transEnd, usePerWord);
            return text;
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

        final SpannableString text = new SpannableString(builder.toString());
        applySpans(text, timings, originalStart, originalEnd,
                romaStart, romaEnd, transStart, transEnd, usePerWord);
        return text;
    }

    private static void applySpans(SpannableString text, List<WordTiming> timings,
            int originalStart, int originalEnd,
            int romaStart, int romaEnd, int transStart, int transEnd,
            boolean usePerWord) {
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
            text.setSpan(new ForegroundColorSpan(unsung), originalStart, originalEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (usePerWord) {
            final int romajiColor = secondaryTextColor();
            for (WordTiming timing : timings) {
                if (timing.romaji != null && !timing.romaji.isEmpty()) {
                    text.setSpan(new RomajiSpan(timing.romaji, romajiColor, ROMAJI_RELATIVE_SIZE),
                            originalStart + timing.start, originalStart + timing.end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
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
     * Copies the original lyrics to the clipboard. Translations and romanizations are
     * excluded even when displayed on screen.
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
            if (copyView != null) {
                setButtonLabel(copyView, str("morphe_music_lyrics_copied"), true);
                handler.postDelayed(() -> {
                    if (copyView != null) {
                        setButtonLabel(copyView, null, false);
                    }
                }, 1500);
            }
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
                if (copyView != null) {
                    setButtonLabel(copyView, str("morphe_music_lyrics_saved"), true);
                    handler.postDelayed(() -> {
                        if (copyView != null) {
                            setButtonLabel(copyView, null, false);
                        }
                    }, 1500);
                }
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

                final boolean romaOk = lines != null && LyricsMerge.hasText(lines);
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
        highlightedIndex = -1;
        lastWordLineIndex = -1;
        pendingOldWordLineIndex = -1;
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
                    && SystemClock.uptimeMillis() >= userScrollUntilUptimeMs) {
                final int target = lineRows.get(highlightedIndex).getTop()
                        + lineViews.get(highlightedIndex).getTop()
                        - scrollView.getHeight() / 3;
                final int clamped = Math.max(0, target);
                if (Math.abs(scrollView.getScrollY() - clamped) > scrollView.getHeight() / 4) {
                    scrollView.smoothScrollTo(0, clamped);
                }
            }
            return;
        }

        if (highlightedIndex >= 0 && highlightedIndex < lineViews.size()) {
            boolean keepFullOpacity = false;
            if (Settings.LYRICS_WORD_SYNC.get()
                    && highlightedIndex < lineWordSpans.size()) {
                final List<WordTiming> timings = lineWordSpans.get(highlightedIndex);
                if (!timings.isEmpty()) {
                    final long lastEnd = timings.get(timings.size() - 1).endMs;
                    if (pos < lastEnd) {
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
        for (int i = 0; i < lineRows.size(); i++) {
            if (hidePlayed && i < index) {
                lineRows.get(i).setVisibility(GONE);
            } else if (hideUnplayed && i > index) {
                lineRows.get(i).setVisibility(GONE);
            } else {
                lineRows.get(i).setVisibility(VISIBLE);
            }
        }

        if (SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
            return;
        }

        final int target = lineRows.get(index).getTop() + lineViews.get(index).getTop()
                - scrollView.getHeight() / 3;
        scrollView.smoothScrollTo(0, Math.max(0, target));
    }

    private void updateWordSync(long positionMs) {
        boolean enabled = Settings.LYRICS_WORD_SYNC.get();
        if (enabled != wordSyncWasEnabled) {
            if (!enabled) {
                int count = Math.min(lineWordSpans.size(), lineViews.size());
                for (int i = 0; i < count; i++) {
                    lineViews.get(i).setTextColor(lineTextColor());
                    CharSequence cs = lineViews.get(i).getText();
                    if (cs instanceof Spannable) {
                        Spannable sp = (Spannable) cs;
                        for (ForegroundColorSpan s : sp.getSpans(0, sp.length(),
                                ForegroundColorSpan.class)) {
                            if (s.getForegroundColor() == unsungWordColor()) {
                                sp.removeSpan(s);
                            }
                        }
                    }
                    applyWordColors(i, Long.MIN_VALUE, true);
                }
                pendingOldWordLineIndex = -1;
            }
            wordSyncWasEnabled = enabled;
        }
        int active = highlightedIndex;

        int count = Math.min(lineWordSpans.size(), lineViews.size());
        if (active < 0 || active >= count) {
            if (lastWordLineIndex >= 0) {
                applyWordColors(lastWordLineIndex, Long.MIN_VALUE, false);
                resetBgWordColors(lastWordLineIndex);
            }
            lastWordLineIndex = -1;
            pendingOldWordLineIndex = -1;
            return;
        }

        if (!enabled) {
            if (lastWordLineIndex >= 0 && lastWordLineIndex != active) {
                applyWordColors(lastWordLineIndex, Long.MIN_VALUE, false);
                resetBgWordColors(lastWordLineIndex);
            }
            applyWordColors(active, 0, true);
            applyBgWordColors(active, 0, true);
            lastWordLineIndex = -1;
            pendingOldWordLineIndex = -1;
            return;
        }

        if (pendingOldWordLineIndex >= 0 && pendingOldWordLineIndex < count) {
            final List<WordTiming> pendingTimings = lineWordSpans.get(pendingOldWordLineIndex);
            if (!pendingTimings.isEmpty()) {
                final long lastEnd = pendingTimings.get(pendingTimings.size() - 1).endMs;
                if (positionMs >= lastEnd) {
                    // Last word done — reset pending line now.
                    applyWordColors(pendingOldWordLineIndex, Long.MIN_VALUE, false);
                    resetBgWordColors(pendingOldWordLineIndex);
                    fadeTo(lineViews.get(pendingOldWordLineIndex), INACTIVE_LINE_ALPHA);
                    pendingOldWordLineIndex = -1;
                } else {
                    // Still playing — keep it fully highlighted.
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
                applyWordColors(lastWordLineIndex, Long.MIN_VALUE, false);
                resetBgWordColors(lastWordLineIndex);
            }
            lastWordLineIndex = active;
            applyWordColors(active, 0, true);
            applyBgWordColors(active, 0, true);
            return;
        }

        if (active != lastWordLineIndex) {
            if (lastWordLineIndex >= 0) {
                final List<WordTiming> oldTimings = lineWordSpans.get(lastWordLineIndex);
                if (!oldTimings.isEmpty()) {
                    final long lastEnd = oldTimings.get(oldTimings.size() - 1).endMs;
                    if (positionMs < lastEnd) {
                        // Delay reset — old line becomes pending.
                        pendingOldWordLineIndex = lastWordLineIndex;
                    } else {
                        applyWordColors(lastWordLineIndex, Long.MIN_VALUE, false);
                        resetBgWordColors(lastWordLineIndex);
                    }
                } else {
                    applyWordColors(lastWordLineIndex, Long.MIN_VALUE, false);
                    resetBgWordColors(lastWordLineIndex);
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

    private int computeOriginalTextStart(LyricsLine line, int index) {
        final String original = line.text();
        final String originalTrimmed = original.trim();
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
        final String above = swap ? translation : romanization;
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
        if (lineView instanceof LyricsLineView) {
            ((LyricsLineView) lineView).setHighlight(
                    timings, positionMs, allSung, unsungWordColor(), lineTextColor(), origStart);
        }
    }

    /** Eases the highlight between lines the way the built-in panel does. */
    private static void fadeTo(TextView lineView, float alpha) {
        lineView.animate().cancel();
        lineView.animate()
                .alpha(alpha)
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
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(Dim.dp20);
        background.setColor(active ? ACTIVE_BUTTON_BG_COLOR
                : ResourceUtils.getColor(APP_BUTTON_BACKGROUND_COLOR, 0x1AFFFFFF));
        button.setBackground(background);
        ViewAnimations.applyPressEffect(button);

        final int fg = active ? ACTIVE_BUTTON_FG_COLOR : lineTextColor();
        Drawable icon = button.getCompoundDrawablesRelative()[0];
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(fg);
            button.setCompoundDrawablesRelative(icon, null, null, null);
            // Reserve padding for the label only when one is actually shown, otherwise
            // the reserved space pushes the icon to the left of the pill.
            final CharSequence currentText = button.getText();
            button.setCompoundDrawablePadding(
                    currentText != null && currentText.length() > 0 ? Dim.dp8 : 0);
        }
        button.setTextColor(fg);
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
        // The karaoke highlight needs a colour that visibly differs from the sung
        // (primary) colour. Prefer the app's secondary text colour, but if that
        // resource is unavailable fall back to a dimmed primary so the effect is
        // always visible instead of collapsing to the sung colour.
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

    private static int unsungWordColor() {
        if (!colorCacheValid) {
            cachedLineColor = computeLineColor();
            cachedUnsungColor = Color.argb(0x66,
                    Color.red(cachedLineColor), Color.green(cachedLineColor), Color.blue(cachedLineColor));
            colorCacheValid = true;
        }
        return cachedUnsungColor;
    }

    /**
     * Color the app uses for lyrics text, falling back to the generic foreground color.
     */
    private static int lineTextColor() {
        if (!colorCacheValid) {
            cachedLineColor = computeLineColor();
            cachedUnsungColor = Color.argb(0x66,
                    Color.red(cachedLineColor), Color.green(cachedLineColor), Color.blue(cachedLineColor));
            colorCacheValid = true;
        }
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
            final String text = translated.get(i);
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
