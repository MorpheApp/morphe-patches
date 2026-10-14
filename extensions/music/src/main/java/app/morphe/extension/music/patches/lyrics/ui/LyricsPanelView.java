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
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.util.Pair;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.derive.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.derive.LyricsTranslator;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.storage.LyricsFileSaver;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.ViewAnimations;

/**
 * Third party lyrics, drawn over the content of the lyrics engagement panel.
 *
 * <p>Hides itself when there are no lyrics to show, which leaves the built-in
 * lyrics visible underneath.
 */
@SuppressLint("ClickableViewAccessibility")
public final class LyricsPanelView extends FrameLayout implements LyricsManager.Listener {

    private static final float INACTIVE_LINE_ALPHA = 0.35f;

    private static final float FOOTER_ALPHA = 0.6f;

    private static final long HIGHLIGHT_FADE_DURATION_MILLISECONDS = 200;

    static final long SECONDARY_REVEAL_MILLISECONDS = 250;

    /** Fade length when the panel appears over the built-in content. */
    private static final long OVERLAY_FADE_DURATION_MILLISECONDS = 150;

    /** How long auto scrolling stays off after the user touches the panel. */
    private static final long MANUAL_SCROLL_PAUSE_MILLISECONDS = 5000;

    private static final long OVERLAY_CACHE_TTL_MS = 300;

    private static final long MIN_TICK_INTERVAL_MS = 50;

    private static final long IDLE_TICK_INTERVAL_MS = 250;

    private static final int SCROLL_OFFSET_FRACTION = 3;
    private static final int SCROLL_INSTANT_THRESHOLD_FACTOR = 2;
    private static final int SCROLL_SMOOTH_THRESHOLD_FACTOR = 4;

    /** Own string, because the app string {@code lyrics_source} exists in English only. */
    private static final String LYRICS_SOURCE_KEY = "morphe_music_lyrics_source_label";

    /** Size of the source line under the lyrics. */
    private static final float FOOTER_TEXT_SIZE_SP = 16;

    private static final float BUTTON_TEXT_SIZE_SP = 14;

    private static final float PILL_ALPHA = 0.4f;

    private static final float BACKGROUND_TEXT_SIZE_FACTOR = 0.85f;
    private static final float SECTION_TAG_TEXT_SIZE_FACTOR = 0.45f;
    private static final float SECTION_DOT_SIZE_FACTOR = 0.36f;
    private static final float SECTION_DOT_GAP_FACTOR = 0.22f;

    private static final long SECTION_TAG_FADE_IN_MILLIS = 250;

    /**
     * The pill's own horizontal inset, which is also how far in the dot row starts so its first dot
     * lines up with the label's text.
     */
    private static final int SECTION_TAG_PADDING_HORIZONTAL = Dim.dp10;

    /** Background the app uses for the pill buttons under its own lyrics. */
    private static final String APP_BUTTON_BACKGROUND_COLOR = "ytm_color_white_at_10pct";

    /** Active (feature on) button background: pure white. */
    private static final int ACTIVE_BUTTON_BG_COLOR = 0xFFFFFFFF;
    /** How long a button takes to cross between its inactive and active colors. */
    private static final long BUTTON_STATE_FADE_MILLISECONDS = 150;
    private static final ArgbEvaluator BUTTON_COLOR_EVALUATOR = new ArgbEvaluator();

    /** Icons of the buttons the app draws under its own lyrics. */
    private static final String APP_TRANSLATE_ICON = "yt_outline_experimental_translate_vd_theme_24";

    /** Icon for the romanize button, showing the pronunciation above each line. */
    private static final String APP_ROMANIZE_ICON = "morphe_yt_romanize";

    private static final String REFRESH_ICON = "ic_mtrl_arrow_circle";

    /** Own icon, because the app ships no copy icon of its own. */
    private static final String COPY_ICON = "morphe_yt_copy_bold";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateTranslateLabelRunnable = this::updateTranslateLabel;
    private final Runnable updateRomanizeLabelRunnable = this::updateRomanizeLabel;

    private final ScrollView scrollView;
    private final LinearLayout linesContainer;
    private final TextView creditView;
    private final TextView footerView;
    @Nullable
    private TextView translateView;
    @Nullable
    private TextView romanizeView;
    /** Copy button, or {@code null} when hidden by settings. */
    @Nullable
    private TextView copyView;
    @Nullable
    private TextView refreshView;
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

    enum OnlyMode { NONE, TRANS, ROMA }

    private OnlyMode onlyMode = OnlyMode.NONE;

    private final List<TextView> lineViews = new ArrayList<>();

    /** Wrapper holding the optional romanization line above each line of lyrics. */
    private final List<View> lineRows = new ArrayList<>();

    /**
     * Section labels such as "Chorus", one per line that opens a new section, in line order.
     * Views are {@code null} where the line has no section, so this list indexes alongside
     * {@link #lineViews}.
     */
    private final List<TextView> sectionTags = new ArrayList<>();

    /**
     * The row of three dots that stands in for a section label over a long silence, one per
     * section label, indexing alongside {@link #sectionTags}. Entries are {@code null} where the
     * line has no label, and every row starts hidden.
     */
    private final List<LinearLayout> sectionDots = new ArrayList<>();

    /**
     * The slot a line without a section label opens up for the dots to play in, one per line that
     * has no label but is preceded by a long silence, indexing alongside {@link #lineViews}. It is
     * zero-height until its gap calls for it. Entries are {@code null} elsewhere.
     */
    private final List<FrameLayout> sectionDotSlots = new ArrayList<>();

    /** The height a section label takes, so a slot can open up to match one. */
    private int sectionTagHeightPx;

    /** The silences long enough to play the dots, as measured on the lines currently built. */
    private List<SectionDotsMotion.Gap> sectionDotsGaps = List.of();

    /** Line index of the label whose dots are being driven right now, or -1 when none are. */
    private int activeSectionDotsIndex = -1;

    private final List<List<LyricsSpanBuilder.WordTiming>> lineWordSpans = new ArrayList<>();
    private final List<Integer> lineOriginalStarts = new ArrayList<>();
    private final List<Boolean> lineIsBackground = new ArrayList<>();
    private final List<ForegroundColorSpan> lineUnsungSpans = new ArrayList<>();

    private static final ExecutorService LINE_BUILDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private final AtomicInteger buildGeneration = new AtomicInteger(0);

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

    private int lastOverlayIndex = -1;
    /** Whether hide-played/unplayed was active on the last {@link #applyLineOverlay} pass. */
    private boolean lastOverlayHideActive;

    private boolean seekPending;

    /** Floating ruler for temporary offset adjustment via horizontal swipe. */
    private final OffsetRulerView offsetRulerView;
    private final GestureDetector offsetGestureDetector;
    private boolean isOffsetAdjusting;
    private float offsetSwipeStartX;
    private int offsetSwipeStartMs;
    private final Runnable hideOffsetRunnable = this::hideOffsetRuler;

    private boolean sbRecording;
    private long sbRecordStartMs;
    @Nullable
    private String sbRecordVideoId;

    private final Runnable recordTickRunnable = new Runnable() {
        @Override
        public void run() {
            offsetRulerView.setRecordTimeMs(LyricsManager.getInstance().getVideoPositionMs());
            handler.postDelayed(this, 10);
        }
    };

    @Nullable
    private Lyrics lyrics;
    private Lyrics lastBuiltLyrics;

    private int highlightedIndex = -1;

    private final KaraokeHighlightState karaokeState = new KaraokeHighlightState();

    private final KaraokeHighlightState.Lines wordTimings = new KaraokeHighlightState.Lines() {
        @Override
        public int size() {
            return Math.min(lineWordSpans.size(), lineViews.size());
        }

        @Override
        public boolean wordsEmpty(int line) {
            return lineWordSpans.get(line).isEmpty();
        }

        @Override
        public long firstStart(int line) {
            return lineWordSpans.get(line).get(0).startMs();
        }

        @Override
        public long lastEnd(int line) {
            final List<LyricsSpanBuilder.WordTiming> timings = lineWordSpans.get(line);
            return timings.get(timings.size() - 1).endMs();
        }
    };

    private final List<KaraokeHighlightState.Action> karaokeActions = new ArrayList<>();

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
                final long positionMs = LyricsManager.getInstance().getPositionMs();
                if (onlyMode != OnlyMode.NONE) {
                    updateOnlyHighlight();
                } else {
                    updateHighlight();
                    updateWordSync(positionMs);
                }
                updateSectionDots(positionMs);

                // The app restores its own panel content asynchronously, and switching
                // to another engagement panel gives no lyrics state change to react to,
                // so the wanted state is reapplied on every tick rather than on changes.
                // The cached answer is kept here, since ticks carry no news of their own.
                applyOverlayVisibility();
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not update lyrics panel view", ex);
            }
            long interval = computeTickInterval();
            if (lyrics == null || lyrics.isEmpty()) {
                interval = Math.max(interval, IDLE_TICK_INTERVAL_MS);
            }
            handler.postDelayed(this, interval);
        }
    };

    public LyricsPanelView(Context context) {
        super(context);

        final int horizontalPadding = Dim.dp32;
        final int verticalPadding = Dim.dp16;

        linesContainer = new LinearLayout(context);
        linesContainer.setOrientation(LinearLayout.VERTICAL);
        linesContainer.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        footerView = new LyricsFit.FittingTextView(context);
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

        createToolbarButtons();

        // The source line lives in a container of its own, so that lyrics lines can be
        // inserted before it without depending on how many views it holds.
        footerContainer = new LinearLayout(context);
        footerContainer.setOrientation(LinearLayout.VERTICAL);
        // The bottom padding keeps the last lines clear of the pinned buttons.
        footerContainer.setPadding(0, Dim.dp16, 0, Dim.dp(200));

        creditView = new LyricsFit.FittingTextView(context);
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
        offsetRulerView.setOnClickListener(view -> onOffsetLabelClicked());

        offsetGestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onScroll(@Nullable MotionEvent e1, @Nullable MotionEvent e2,
                                            float distanceX, float distanceY) {
                        if (e1 == null || e2 == null) {
                            return false;
                        }
                        if (isOffsetAdjusting) {
                            float dx = e2.getX() - offsetSwipeStartX;
                            int deltaMs = Math.round(-dx / getResources().getDisplayMetrics().density) * 10;
                            int newMs = Math.max(-30000, Math.min(30000, offsetSwipeStartMs + deltaMs));
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
        if (sbRecording || LyricsManager.getInstance().getTemporaryOffsetMs() != 0) {
            return;
        }
        handler.postDelayed(hideOffsetRunnable, 2000);
    }

    private void onOffsetLabelClicked() {
        final LyricsManager manager = LyricsManager.getInstance();
        if (sbRecording) {
            final long endMs = manager.getVideoPositionMs();
            final String videoId = sbRecordVideoId;
            stopSbRecording();
            scheduleHideOffsetRuler();
            if (videoId == null || !Settings.LYRICS_SB_MATCHING.get()) {
                return;
            }
            long startMs = sbRecordStartMs;
            long stopMs = endMs;
            if (stopMs < startMs) {
                final long swap = startMs;
                startMs = stopMs;
                stopMs = swap;
            }
            final Context context = getContext();
            if (context == null) {
                return;
            }
            SponsorBlockMusicSubmitDialog.show(context, videoId, startMs, stopMs,
                    offsetRulerView::setOffsetMs, this::scheduleHideOffsetRuler);
            return;
        }
        if (!Settings.LYRICS_SB_MATCHING.get()) {
            return;
        }
        final String videoId = manager.skipSegmentsVideoId();
        if (videoId == null) {
            Utils.showToastShort(str("morphe_music_lyrics_sb_no_video"));
            return;
        }
        sbRecording = true;
        sbRecordStartMs = manager.getVideoPositionMs();
        sbRecordVideoId = videoId;
        handler.removeCallbacks(hideOffsetRunnable);
        handler.removeCallbacks(recordTickRunnable);
        offsetRulerView.setRecordTimeMs(sbRecordStartMs);
        handler.post(recordTickRunnable);
    }

    private void stopSbRecording() {
        if (!sbRecording) {
            return;
        }
        sbRecording = false;
        sbRecordVideoId = null;
        handler.removeCallbacks(recordTickRunnable);
        offsetRulerView.setRecordTimeMs(null);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            LyricsManager.getInstance().addListener(this);
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        } catch (Exception ex) {
            Logger.printException(() -> "onAttachedToWindow failure", ex);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        try {
            if (pendingAnchorScroll != null) {
                linesContainer.getViewTreeObserver().removeOnPreDrawListener(pendingAnchorScroll);
                pendingAnchorScroll = null;
            }
            setKeepScreenOn(false);
            LyricsManager.getInstance().removeListener(this);
            handler.removeCallbacksAndMessages(null);
            stopSbRecording();
            LyricsManager.getInstance().resetTemporaryOffsetMs();
            offsetRulerView.setVisibility(GONE);
            if (!LyricsPanelInstaller.isOtherPanelForeground()) {
                restoreHiddenSiblings();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onDetachedFromWindow failure", ex);
        }
    }

    @Override
    public void onLyricsChanged(LyricsManager.State state, @Nullable Lyrics newLyrics) {
        try {
            // Refresh keeps the current rendering (including scroll and translations)
            // until a replacement arrives.
            if (state == LyricsManager.State.LOADING && newLyrics == lyrics
                    && newLyrics != null && !newLyrics.isEmpty()) {
                return;
            }
if (state == LyricsManager.State.LOADED && newLyrics == lyrics
                    && newLyrics != null && !newLyrics.isEmpty()) {
                if (refreshInProgress) {
                    refreshInProgress = false;
                    handler.removeCallbacks(refreshLabelResetRunnable);
                    updateRefreshLabel();
                }
                return;
            }
            if (state == LyricsManager.State.LOADED && newLyrics != null
                    && lyrics != null && newLyrics != lyrics && !newLyrics.isEmpty()
                    && sameLinesExceptSongPart(lyrics, newLyrics)
                    && applySectionLabelsInPlace(newLyrics)) {
                return;
            }

            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (track != lastKnownTrack) {
                lastKnownTrack = track;
                refreshInProgress = false;
                handler.removeCallbacks(refreshLabelResetRunnable);
                updateRefreshLabel();
            }
            lyrics = newLyrics;
            highlightedIndex = -1;
            resetSectionDots();
            karaokeState.resetPending();
            seekPending = false;
            userScrollUntilUptimeMs = 0;
            handler.removeCallbacks(updateTranslateLabelRunnable);
            handler.removeCallbacks(updateRomanizeLabelRunnable);
            LyricsManager.getInstance().resetTemporaryOffsetMs();
            stopSbRecording();
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
                    if (newLyrics == null || newLyrics.isEmpty()) {
                        showLoading();
                    } else {
                        showLyrics(newLyrics);
                    }
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
                            flashButtonLabel(refreshView,
                                    str("morphe_music_lyrics_refreshed"),
                                    refreshLabelResetRunnable, 1500);
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
            if (newLyrics != null && !newLyrics.isEmpty()) {
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLyricsChanged failure", ex);
        }
    }

    private static boolean sameLinesExceptSongPart(@NonNull Lyrics a, @NonNull Lyrics b) {
        final List<LyricsLine> left = a.lines();
        final List<LyricsLine> right = b.lines();
        if (left.size() != right.size() || a.synced() != b.synced()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            LyricsLine x = left.get(i);
            LyricsLine y = right.get(i);
            if (x.startTimeMs() != y.startTimeMs() || x.endTimeMs() != y.endTimeMs()
                    || !Objects.equals(x.text(), y.text())
                    || !Objects.equals(x.words(), y.words())
                    || !Objects.equals(x.agentId(), y.agentId())
                    || x.isDuet() != y.isDuet() || x.isBG() != y.isBG()) {
                return false;
            }
        }
        return true;
    }

    private boolean applySectionLabelsInPlace(@NonNull Lyrics newLyrics) {
        final List<LyricsLine> newLines = newLyrics.lines();
        if (lineRows.size() != newLines.size() || lineViews.size() != newLines.size()
                || sectionTags.size() != newLines.size()
                || sectionDots.size() != newLines.size()
                || sectionDotSlots.size() != newLines.size()) {
            return false;
        }

        // Any label dimmed by a gap is put back, since the gaps driving it are replaced below.
        resetSectionDots();

        Context context = getContext();
        String previousLeadSongPart = null;
        for (int i = 0; i < newLines.size(); i++) {
            LyricsLine line = newLines.get(i);
            // The same rule buildLyrics applies, which it has to stay in step with.
            final String songPart = line.songPart();
            final boolean opensSection = !line.isBG()
                    && songPart != null && !songPart.isEmpty()
                    && !songPart.equals(previousLeadSongPart);
            if (!line.isBG()) {
                previousLeadSongPart = songPart;
            }

            final TextView builtTag = sectionTags.get(i);
            final FrameLayout builtSlot = sectionDotSlots.get(i);
            if (opensSection && builtTag != null) {
                // Already carries the label this line opened; only its text can have changed.
                builtTag.setText(songPart);
                continue;
            }
            if (opensSection) {
                // Gaining a label: drop the slot that was standing in for it and show the label.
                if (builtSlot != null) {
                    asRow(lineRows.get(i)).removeView(builtSlot);
                    sectionDotSlots.set(i, null);
                    sectionDots.set(i, null);
                }
                TextView tag = createSectionTag(context, songPart);
                LinearLayout dotsRow = createSectionDots(context);
                FrameLayout tagRow = new FrameLayout(context);
                tagRow.addView(tag, tagLayoutParams());
                tagRow.addView(dotsRow, dotsLayoutParams());
                asRow(lineRows.get(i)).addView(tagRow, 0, slotLayoutParams());
                sectionTags.set(i, tag);
                sectionDots.set(i, dotsRow);
                tagRow.setAlpha(0f);
                tagRow.animate().alpha(1f).setDuration(SECTION_TAG_FADE_IN_MILLIS).start();
            } else if (builtTag != null) {
                // Losing a label: drop the row it was built with, then let the slot below take its
                // turn if a silence still calls for one.
                asRow(lineRows.get(i)).removeViewAt(0);
                sectionTags.set(i, null);
                sectionDots.set(i, null);
            }
        }

        lyrics = newLyrics;
        lastBuiltLyrics = newLyrics;
        final List<SectionDotsMotion.Gap> newGaps = SectionDotsMotion.computeGaps(newLines);
        sectionDotsGaps = newGaps;
        ensureDotsSlotsExist(context, newGaps);
        return true;
    }

    /**
     * Gives a slot to every line a gap points at that has no label to stand in for it, so a silence
     * that starts showing up after the rows were built still has somewhere to play.
     */
    private void ensureDotsSlotsExist(Context context, List<SectionDotsMotion.Gap> gaps) {
        for (SectionDotsMotion.Gap gap : gaps) {
            final int index = gap.tagLineIndex();
            if (index < 0 || index >= sectionDotSlots.size()
                    || sectionTags.get(index) != null || sectionDotSlots.get(index) != null) {
                continue;
            }
            final ViewGroup existingRow = asRow(lineRows.get(index));
            if (existingRow == null) {
                continue;
            }
            LinearLayout dotsRow = createSectionDots(context);
            FrameLayout slot = new FrameLayout(context);
            slot.addView(dotsRow, dotsLayoutParams());
            FrameLayout.LayoutParams params = slotLayoutParams();
            params.height = 0;
            params.topMargin = 0;
            existingRow.addView(slot, 0, params);
            sectionDotSlots.set(index, slot);
            sectionDots.set(index, dotsRow);
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
        try {
            buildLyrics(newLyrics);
        } catch (Throwable ex) {
            if (Settings.DEBUG.get()) {
                Logger.printException(() -> "Debug: buildLyrics failure", ex);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void buildLyrics(Lyrics newLyrics) {
        if (onlyMode != displayedOnlyMode && !contentOutPending && !lineViews.isEmpty()) {
            contentOutPending = true;
            wholeFadeNextBuild = true;
            displayedOnlyMode = onlyMode;
            final int outGeneration = buildGeneration.get();
            for (TextView lineView : lineViews) {
                if (lineView instanceof LyricsLineView view) {
                    view.animateContentOut();
                }
            }
            handler.postDelayed(() -> {
                contentOutPending = false;
                if (outGeneration == buildGeneration.get() && lyrics != null) {
                    showLyrics(lyrics);
                }
            }, SECONDARY_REVEAL_MILLISECONDS);
            return;
        }
        displayedOnlyMode = onlyMode;
        growthAnchorValid = false;
        final boolean preserveHidePosition = pendingHideAnchorIndex >= 0;
        final int preserveHideIndex = pendingHideAnchorIndex;
        final float preserveScreenY = pendingHideAnchorScreenY;
        clearPendingHideAnchor();
        final boolean sameContent = !lineRows.isEmpty() && lastBuiltLyrics == newLyrics;
        final int prevCount = sameContent
                ? Math.min(lineViews.size(), newLyrics.lines().size()) : 0;
        final List<TextView> prevViews = sameContent
                ? new ArrayList<>(lineViews) : Collections.emptyList();
        final int[] prevHeights = new int[prevCount];
        for (int i = 0; i < prevCount; i++) {
            prevHeights[i] = i < lineRows.size() ? lineRows.get(i).getHeight() : 0;
        }
        final List<List<LyricsSpanBuilder.WordTiming>> prevSpans = sameContent
                ? new ArrayList<>(lineWordSpans) : Collections.emptyList();
        final int prevHighlighted = highlightedIndex;
        lastBuiltLyrics = newLyrics;
        clearLines();
        LyricsColors.invalidate();
        progressBar.setVisibility(GONE);
        scrollView.setVisibility(VISIBLE);

        final boolean contentInBuild =
                onlyMode != OnlyMode.NONE || wholeFadeNextBuild;
        wholeFadeNextBuild = false;

        Context context = getContext();
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int foregroundColor = LyricsColors.lineTextColor();
        final boolean tapToSeek = newLyrics.synced() && Settings.LYRICS_TAP_TO_SEEK.get();
        final Paint baseLinePaint = new Paint();
        baseLinePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize,
                getResources().getDisplayMetrics()));
        baseLinePaint.setTypeface(Typeface.DEFAULT_BOLD);
        baseLinePaint.setElegantTextHeight(true);

        final int generation = buildGeneration.get();
        final OnlyMode onlySnap = onlyMode;
        builtOnlyMode = onlySnap;
        final List<LyricsLine> romanizedSnap = romanizedLines;
        final List<String> translatedSnap = translatedLines;
        final boolean perWordRomajiSnap = perWordRomaji;
        String previousLeadSongPart = null;
        // Needed up front: a line without a label only gets a slot if a silence calls for one.
        final List<SectionDotsMotion.Gap> builtGaps = SectionDotsMotion.computeGaps(newLyrics.lines());
        final boolean[] gapAnchors = new boolean[newLyrics.lines().size()];
        for (SectionDotsMotion.Gap gap : builtGaps) {
            final int anchor = gap.tagLineIndex();
            if (anchor >= 0 && anchor < gapAnchors.length) {
                gapAnchors[anchor] = true;
            }
        }

        for (int i = 0; i < newLyrics.lines().size(); i++) {
            LyricsLine line = newLyrics.lines().get(i);
            final LyricsLineView prevView = i < prevCount
                    && prevViews.get(i) instanceof LyricsLineView pv ? pv : null;
            final boolean reusePrevious = prevView != null && !contentInBuild;
            final List<LyricsSpanBuilder.WordTiming> gapTimings = reusePrevious
                    && i < prevSpans.size()
                    ? prevSpans.get(i) : Collections.emptyList();
            lineWordSpans.add(reusePrevious ? gapTimings : new ArrayList<>());

            LyricsLineView lineView = new LyricsLineView(context);
            // Plain text first so the panel paints immediately; the karaoke spans are added on a
            // background thread (see the LINE_BUILDER_EXECUTOR pass below). A hide rebuild keeps
            // the surviving regions so its first frame matches the shrunken layout exactly, and a
            // same-content rebuild reuses the previous row so toggles never blink existing text.
            int gapOrigStart = 0;
            if (preserveHidePosition) {
                LyricsSpanBuilder.BuildResult result =
                        LyricsSpanBuilder.buildLineText(line, gapTimings, i,
                                perWordRomaji, romanizedLines, translatedLines, onlyMode,
                                baseLinePaint);
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
                        LyricsColors.unsungWordColor(), gapOrigStart);
            }
            lineView.setActiveLine(onlySnap == OnlyMode.NONE
                    && reusePrevious && prevView.activeLine, LyricsColors.secondaryTextColor());
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, lineTextSizeSp(line.isBG(), textSize));
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
                lineView.setTextColor(LyricsColors.unsungWordColor());
            }

            if (tapToSeek) {
                lineView.setOnClickListener(view -> {
                    final long videoLength = VideoInformation.getVideoLength();
                    final LyricsManager manager = LyricsManager.getInstance();
                    long target = manager.toVideoTimeMs(line.startTimeMs()
                            + Settings.LYRICS_OFFSET_MS.get()
                            + manager.getTemporaryOffsetMs());
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

            // A background vocal sits under its lead line and carries the same label, so only a
            // lead line opens a section. The dots measure the silence on the same lines, so a
            // label and the dots standing in for it always land on the same line.
            final String songPart = line.songPart();
            final boolean opensSection = !line.isBG()
                    && songPart != null && !songPart.isEmpty()
                    && !songPart.equals(previousLeadSongPart);
            if (!line.isBG()) {
                previousLeadSongPart = songPart;
            }
            TextView sectionTag = createSectionTag(context,
                    opensSection ? songPart : null);
            LinearLayout sectionDotsRow = null;
            FrameLayout sectionDotSlot = null;
            if (sectionTag != null) {
                sectionDotsRow = createSectionDots(context);
                FrameLayout tagRow = new FrameLayout(context);
                tagRow.addView(sectionTag, tagLayoutParams());
                tagRow.addView(sectionDotsRow, dotsLayoutParams());
                lineRow.addView(tagRow, slotLayoutParams());
                final TextView measuredTag = sectionTag;
                measuredTag.post(() -> {
                    if (measuredTag.getHeight() > 0) {
                        sectionTagHeightPx = measuredTag.getHeight();
                    }
                });
            } else if (i < gapAnchors.length && gapAnchors[i]) {
                // No label here, but a silence runs up to this line; the slot it opens gives the
                // dots somewhere to play that matches what a label would have taken.
                sectionDotsRow = createSectionDots(context);
                sectionDotSlot = new FrameLayout(context);
                sectionDotSlot.addView(sectionDotsRow, dotsLayoutParams());
                FrameLayout.LayoutParams slotParams = slotLayoutParams();
                // Starts fully closed: the dots row inside must not take space until a gap opens
                // it, and neither must the margin, which a zero-height child still occupies.
                slotParams.height = 0;
                slotParams.topMargin = 0;
                lineRow.addView(sectionDotSlot, slotParams);
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
            sectionTags.add(sectionTag);
            sectionDots.add(sectionDotsRow);
            sectionDotSlots.add(sectionDotSlot);
            lineUnsungSpans.add(null);
            lineIsBackground.add(line.isBG());
        }

        sectionDotsGaps = builtGaps;

        LINE_BUILDER_EXECUTOR.execute(() -> {
            try {
                final int lineCount = newLyrics.lines().size();
                List<List<LyricsSpanBuilder.WordTiming>> allTimings = new ArrayList<>(lineCount);
                List<Integer> allOrigStarts = new ArrayList<>(lineCount);
                for (int i = 0; i < lineCount; i++) {
                    if (generation != buildGeneration.get() || lyrics != newLyrics) {
                        return;
                    }
                    allTimings.add(LyricsSpanBuilder.computeWordTimings(newLyrics.lines(), i));
                    allOrigStarts.add(computeOriginalTextStart(newLyrics.lines().get(i), i,
                            onlySnap, romanizedSnap, translatedSnap, perWordRomajiSnap));
                }
                handler.post(() -> {
                    if (generation != buildGeneration.get() || lyrics != newLyrics) {
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        linesContainer.suppressLayout(true);
                    }
                    try {
                        for (int i = 0; i < count; i++) {
                            lineWordSpans.set(i, only ? Collections.emptyList() : allTimings.get(i));
                            lineOriginalStarts.set(i, only ? 0 : allOrigStarts.get(i));
                            TextView tv = lineViews.get(i);
                            if (i < newLyrics.lines().size()) {
                                LyricsSpanBuilder.BuildResult result =
                                        LyricsSpanBuilder.buildLineText(newLyrics.lines().get(i),
                                                allTimings.get(i), i,
                                                perWordRomaji, romanizedLines, translatedLines, onlyMode,
                                                baseLinePaint);
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            linesContainer.suppressLayout(false);
                        }
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
                            final View row = lineRows.get(i);
                            row.measure(View.MeasureSpec.makeMeasureSpec(
                                            row.getWidth(), View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0,
                                            View.MeasureSpec.UNSPECIFIED));
                            final int newH = row.getMeasuredHeight();
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
                    if (generation != buildGeneration.get() || lyrics != newLyrics) {
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
        String about = newLyrics.about();
        boolean hasAbout = about != null && !about.isEmpty();
        if ((songwriters != null && !songwriters.isEmpty()) || hasAbout) {
            StringBuilder sb = new StringBuilder();
            if (songwriters != null) {
                for (int i = 0; i < songwriters.size(); i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(songwriters.get(i));
                }
            }
            if (hasAbout) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(about);
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
            final int preserveGeneration = buildGeneration.get();
            linesContainer.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            if (preserveGeneration != buildGeneration.get()
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
                            final int index = restoreAnchorIndex(preserveIndex);
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
            final int clampGeneration = buildGeneration.get();
            linesContainer.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            linesContainer.getViewTreeObserver()
                                    .removeOnPreDrawListener(this);
                            if (clampGeneration != buildGeneration.get()) {
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

    private int restoreAnchorIndex(int preserveIndex) {
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
        return index;
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
                            flashButtonLabel(translateView,
                                    str("morphe_music_lyrics_translate_hide"),
                                    updateTranslateLabelRunnable, 3000);
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
                applyOnlyModeState();
                showLyrics(current);
                flashButtonLabel(translateView,
                        str("morphe_music_lyrics_translate_only"),
                        updateTranslateLabelRunnable, 3000);
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
            applyOnlyModeState();
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
                    applyOnlyModeState();
                    showLyrics(current);
                    flashButtonLabel(translateView,
                            str("morphe_music_lyrics_translate_only"),
                            updateTranslateLabelRunnable, 3000);
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
            applyOnlyModeState();
        }
        updateFooter();
    }

    private void clearRomanizeOnlyState() {
        Settings.LYRICS_ROMANIZE_ONLY.save(false);
        cancelNormalRomanize();
        if (onlyMode == OnlyMode.ROMA) {
            onlyMode = OnlyMode.NONE;
            applyOnlyModeState();
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
        karaokeState.resetContent();
        karaokeState.syncEnabled(Settings.LYRICS_WORD_SYNC.get());
    }

    private void applyOnlyModeState() {
        captureOnlyModeScrollAnchor();
        resetOnlyModeKaraokeState();
        clearActiveLineState();
    }

    private void clearActiveLineState() {
        final int color = LyricsColors.secondaryTextColor();
        for (TextView lineView : lineViews) {
            if (lineView instanceof LyricsLineView view) {
                view.setActiveLine(false, color);
            }
        }
    }

    private void clearPendingHideAnchor() {
        pendingHideAnchorIndex = -1;
        pendingHideAnchorScreenY = 0f;
    }

    private void captureOnlyModeScrollAnchor() {
        clearPendingHideAnchor();
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
            if (current == null) {
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
                            flashButtonLabel(romanizeView,
                                    str("morphe_music_lyrics_romanize_hide"),
                                    updateRomanizeLabelRunnable, 3000);
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
                applyOnlyModeState();
                showLyrics(current);
                flashButtonLabel(romanizeView,
                        str("morphe_music_lyrics_romanize_only"),
                        updateRomanizeLabelRunnable, 3000);
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
            applyOnlyModeState();
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
                    applyOnlyModeState();
                    showLyrics(current);
                    flashButtonLabel(romanizeView,
                            str("morphe_music_lyrics_romanize_only"),
                            updateRomanizeLabelRunnable, 3000);
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
        if (refreshInProgress || LyricsManager.getInstance().getCurrentTrack() == null) {
            return;
        }
        refreshInProgress = true;
        setButtonLabel(refreshView, str("morphe_music_lyrics_refreshing"), true);
        LyricsManager.getInstance().fetchNextCandidate();
    }

    private void onRefreshLongPressed() {
        showSearchDialog();
    }

    private void showSearchDialog() {
        try {
            openSearchDialog();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Lets the user type the song and artist they want lyrics for. The terms open prefilled
     * with what was searched before, or with the track's untouched metadata - the cleaned
     * track folds fullwidth punctuation to ASCII - and what the search finds replaces the
     * whole candidate queue.
     */
    @SuppressWarnings("deprecation")
    private void openSearchDialog() {
        if (refreshView == null) {
            return;
        }
        LyricsManager manager = LyricsManager.getInstance();
        TrackInfo track = manager.getCurrentTrack();
        if (track == null) {
            return;
        }
        if (!manager.hasSearchProviders()) {
            Utils.showToastShort(str("morphe_music_lyrics_search_no_providers"));
            return;
        }
        final Context context = getContext();
        if (context == null) {
            return;
        }

        AutoCompleteTextView titleInput = LyricsSearchUi.createSearchInput(context,
                str("morphe_music_lyrics_search_title_hint"));
        AutoCompleteTextView artistInput = LyricsSearchUi.createSearchInput(context,
                str("morphe_music_lyrics_search_artist_hint"));

        final String rawTitle = manager.getCurrentRawTitle();
        final String rawArtist = manager.getCurrentRawArtist();
        final String defaultTitle = rawTitle != null && !rawTitle.trim().isEmpty()
                ? rawTitle.trim() : track.title();
        final String defaultArtist = rawArtist != null && !rawArtist.trim().isEmpty()
                ? rawArtist.trim() : track.artist();
        String[] remembered = manager.rememberedSearchTerms();
        String initialTitle = remembered != null && remembered[0] != null
                ? remembered[0] : defaultTitle;
        String initialArtist = remembered != null && remembered[1] != null
                ? remembered[1] : defaultArtist;
        titleInput.setText(initialTitle);
        titleInput.setSelection(initialTitle.length());
        artistInput.setText(initialArtist);
        artistInput.setSelection(initialArtist.length());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(titleInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        artistParams.topMargin = Dim.dp8;
        content.addView(artistInput, artistParams);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_search_dialog_title"),
                null,
                null,
                null,
                () -> {
                    String title = titleInput.getText().toString().trim();
                    String artist = artistInput.getText().toString().trim();
                    if (title.isEmpty() || artist.isEmpty()) {
                        Utils.showToastShort(str("morphe_music_lyrics_search_empty"));
                        return;
                    }
                    refreshInProgress = true;
                    setButtonLabel(refreshView, str("morphe_music_lyrics_refreshing"), true);
                    boolean defaultTerms = title.equals(defaultTitle)
                            && artist.equals(defaultArtist);
                    manager.searchWithCustomQuery(title, artist, defaultTerms);
                },
                () -> { },
                null,
                null,
                false
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;
        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();

        // One round of suggestions per dialog, fetched after it is on screen so the keyboard
        // and the first keystroke never wait for MusicBrainz.
        final String titleSuggestion = titleInput.getText().toString();
        final String artistSuggestion = artistInput.getText().toString();
        Utils.runOnBackgroundThread(() -> {
            List<String> titles = LyricsRequests.MusicBrainzClient.suggestTitles(titleSuggestion);
            List<String> artists = LyricsRequests.MusicBrainzClient.suggestArtists(artistSuggestion);
            Utils.runOnMainThread(() -> {
                if (titles.isEmpty() && artists.isEmpty()) {
                    return;
                }
                if (!dialog.isShowing()) {
                    return;
                }
                if (!titles.isEmpty()) {
                    LyricsSearchUi.setSuggestionAdapter(context, titleInput, titles);
                }
                if (!artists.isEmpty()) {
                    LyricsSearchUi.setSuggestionAdapter(context, artistInput, artists);
                }
            });
        });
    }

    private void updateRefreshLabel() {
        if (refreshView != null) {
            setButtonLabel(refreshView, null, false);
        }
    }

    private record RowResize(int rowIndex, View row, View child, int from, int to, int leading,
                             int trailing) {
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
        final int generation = buildGeneration.get();
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
            if (generation == buildGeneration.get()) {
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
            if (generation != buildGeneration.get() || lyrics != current) {
                return;
            }
            captureHideScrollAnchor();
            shrinkRegionRows(trans, () -> {
                if (generation != buildGeneration.get() || lyrics != current) {
                    return;
                }
                showLyrics(current);
            });
        }, SECONDARY_REVEAL_MILLISECONDS);
    }

    private void captureHideScrollAnchor() {
        clearPendingHideAnchor();
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

    private static int rowHeightOutsideText(View row, View text) {
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
            final int targetH = targetLayoutH + tv.getPaddingTop() + tv.getPaddingBottom()
                    + rowHeightOutsideText(row, tv);
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
        buildGeneration.incrementAndGet();
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
        sectionTags.clear();
        sectionDots.clear();
        sectionDotSlots.clear();
        sectionDotsGaps = List.of();
        activeSectionDotsIndex = -1;
        lineWordSpans.clear();
        lineOriginalStarts.clear();
        lineUnsungSpans.clear();
        lineIsBackground.clear();
        highlightedIndex = -1;
        karaokeState.resetContent();
        lastOverlayIndex = -1;
        lastOverlayHideActive = false;
        lastScrollTarget = -1;
        seekPending = false;
        scrollAnchorRowIndex = -1;
        if (pendingAnchorScroll != null) {
            linesContainer.getViewTreeObserver().removeOnPreDrawListener(pendingAnchorScroll);
            pendingAnchorScroll = null;
        }
        clearPendingHideAnchor();
    }

    private void forEachBgLine(int start, Lyrics current, IntConsumer action) {
        for (int b = 1; start + b < lineViews.size()
                && start + b < current.lines().size()
                && current.lines().get(start + b).isBG(); b++) {
            action.accept(start + b);
        }
    }

    private void markActiveLine(int index, Lyrics current, boolean active) {
        if (index < 0 || current == null) {
            return;
        }
        final int color = LyricsColors.secondaryTextColor();
        if (index < lineViews.size() && lineViews.get(index) instanceof LyricsLineView view) {
            view.setActiveLine(active, color);
        }
        forEachBgLine(index, current, i -> {
            if (lineViews.get(i) instanceof LyricsLineView view) {
                view.setActiveLine(active, color);
            }
        });
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
            markActiveLine(index, current, true);
            if (!karaokeActive && index >= 0 && index < lineViews.size()) {
                lineViews.get(index).setTextColor(LyricsColors.lineTextColor());
                forEachBgLine(index, current,
                        i -> lineViews.get(i).setTextColor(LyricsColors.lineTextColor()));
            }
            applyLineOverlay(index);
            scrollAnchorIntoView(Math.max(index, 0), false);
            return;
        }

        if (highlightedIndex >= 0 && highlightedIndex < lineViews.size()) {
            final boolean keepFullOpacity = karaokeActive
                    && KaraokeHighlightState.stillLit(wordTimings, highlightedIndex, pos);
            markActiveLine(highlightedIndex, current, false);
            if (!keepFullOpacity) {
                fadeTo(lineViews.get(highlightedIndex), INACTIVE_LINE_ALPHA);
                if (!karaokeActive) {
                    lineViews.get(highlightedIndex).setTextColor(LyricsColors.unsungWordColor());
                }
                forEachBgLine(highlightedIndex, current, i -> {
                    fadeTo(lineViews.get(i), INACTIVE_LINE_ALPHA);
                    if (!karaokeActive) {
                        lineViews.get(i).setTextColor(LyricsColors.unsungWordColor());
                    }
                });
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

        markActiveLine(index, current, true);

        fadeTo(lineViews.get(index), 1f);
        if (!karaokeActive) {
            lineViews.get(index).setTextColor(LyricsColors.lineTextColor());
        }
        forEachBgLine(index, current, i -> {
            fadeTo(lineViews.get(i), 1f);
            if (!karaokeActive) {
                lineViews.get(i).setTextColor(LyricsColors.lineTextColor());
            }
        });

        if (SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
            return;
        }

        scrollAnchorIntoView(index, true);
    }

    /**
     * Plays the dots over a silence long enough to read as an instrumental break, in place of the
     * section label of the section that comes next.
     *
     * <p>Everything here is derived from the playback position rather than accumulated, so a seek
     * into the middle of a gap simply lands on that gap's frame and a dropped tick cannot leave the
     * label or the dots stranded.
     */
    private void updateSectionDots(long positionMs) {
        List<SectionDotsMotion.Gap> gaps = sectionDotsGaps;
        if (gaps.isEmpty() || onlyMode != OnlyMode.NONE
                || !Settings.LYRICS_SHOW_SECTION.get()) {
            resetSectionDots();
            return;
        }
        SectionDotsMotion.Gap gap = null;
        for (SectionDotsMotion.Gap candidate : gaps) {
            if (positionMs >= candidate.gapStartMs() && positionMs < candidate.gapEndMs()) {
                gap = candidate;
                break;
            }
        }
        if (gap == null) {
            resetSectionDots();
            return;
        }
        final int index = gap.tagLineIndex();
        if (index < 0 || index >= sectionTags.size() || index >= sectionDots.size()
                || index >= sectionDotSlots.size()) {
            // The gaps are measured on the lines these lists were built from, so this cannot
            // normally happen; a label left half-swapped would be worse than no motion.
            resetSectionDots();
            return;
        }
        if (activeSectionDotsIndex != index) {
            resetSectionDots();
            activeSectionDotsIndex = index;
        }
        applySectionDotsState(SectionDotsMotion.stateAt(
                gap.gapStartMs(), gap.gapEndMs(), positionMs), index);
    }

    @NonNull
    private static ViewGroup asRow(View row) {
        return (ViewGroup) row;
    }

    /** Puts one label and its dot row where {@code state} says they belong. */
    private void applySectionDotsState(SectionDotsMotion.State state, int index) {
        TextView tag = sectionTags.get(index);
        if (tag != null) {
            tag.setAlpha(state.pillAlpha());
        }
        final FrameLayout slot = index < sectionDotSlots.size() ? sectionDotSlots.get(index) : null;
        if (slot != null) {
            // A line with no label gives the dots its space by opening up rather than by handing
            // over a label, so the space is driven from the same curve the fade is.
            applySectionSlotHeight(slot, Math.round(state.expand() * targetSectionSlotHeight()),
                    Math.round(state.expand() * Dim.dp24));
        }
        LinearLayout dotsRow = sectionDots.get(index);
        if (dotsRow == null) {
            return;
        }
        dotsRow.setVisibility(state.dotsVisible() ? VISIBLE : GONE);
        if (!state.dotsVisible()) {
            return;
        }
        dotsRow.setAlpha(state.groupAlpha());
        final float[] dotAlpha = state.dotAlpha();
        for (int i = 0; i < dotsRow.getChildCount() && i < dotAlpha.length; i++) {
            dotsRow.getChildAt(i).setAlpha(dotAlpha[i]);
        }
    }

    private void applySectionSlotMargin(int lineIndex, int marginPx) {
        if (lineIndex < 0 || lineIndex >= lineRows.size()) {
            return;
        }
        final View row = lineRows.get(lineIndex);
        if (!(row instanceof ViewGroup group) || group.getChildCount() == 0) {
            return;
        }
        final View slot = group.getChildAt(0);
        if (!(slot instanceof FrameLayout)) {
            return;
        }
        final ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) slot.getLayoutParams();
        if (params == null || params.topMargin == marginPx) {
            return;
        }
        params.topMargin = marginPx;
        slot.setLayoutParams(params);
    }

    /** Sets a slot's height, asking for a layout only when it actually moved. */
    private void applySectionSlotHeight(FrameLayout slot, int heightPx, int marginPx) {
        final ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) slot.getLayoutParams();
        if (params == null
                || (params.height == heightPx && params.topMargin == marginPx)) {
            return;
        }
        params.height = heightPx;
        params.topMargin = marginPx;
        slot.setLayoutParams(params);
    }

    /** Restores the label and hides the dots of whichever line was animating. */
    private void resetSectionDots() {
        final int index = activeSectionDotsIndex;
        if (index < 0) {
            return;
        }
        activeSectionDotsIndex = -1;
        if (index < sectionTags.size()) {
            TextView tag = sectionTags.get(index);
            if (tag != null) {
                tag.setAlpha(1f);
            }
        }
        if (index < sectionDotSlots.size()) {
            FrameLayout slot = sectionDotSlots.get(index);
            if (slot != null) {
                applySectionSlotHeight(slot, 0, 0);
            }
        }
        if (index < sectionDots.size()) {
            LinearLayout dotsRow = sectionDots.get(index);
            if (dotsRow != null) {
                dotsRow.setVisibility(GONE);
                dotsRow.setAlpha(1f);
            }
        }
    }

    /**
     * The row of dots that stands in for a section label over a silence: three white discs in the
     * label's own material, so the swap reads as one shape opening into another.
     *
     * @return The row, hidden until a gap drives it.
     */
    @Nullable
    private static LinearLayout createSectionDots(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setVisibility(GONE);
        final int size = scaledPixels(context, SECTION_DOT_SIZE_FACTOR);
        final int gap = scaledPixels(context, SECTION_DOT_GAP_FACTOR);
        for (int i = 0; i < SectionDotsMotion.DOT_COUNT; i++) {
            View dot = new View(context);
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(Color.WHITE);
            dot.setBackground(disc);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(size, size);
            if (i > 0) {
                dotParams.setMarginStart(gap);
            }
            row.addView(dot, dotParams);
        }
        return row;
    }

    private static void applySectionDotSize(@Nullable LinearLayout row) {
        if (row == null) {
            return;
        }
        final int size = scaledPixels(row.getContext(), SECTION_DOT_SIZE_FACTOR);
        final int gap = scaledPixels(row.getContext(), SECTION_DOT_GAP_FACTOR);
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
        final int generation = buildGeneration.get();
        final ViewTreeObserver.OnPreDrawListener listener =
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        linesContainer.getViewTreeObserver().removeOnPreDrawListener(this);
                        if (pendingAnchorScroll == this) {
                            pendingAnchorScroll = null;
                        }
                        if (generation == buildGeneration.get()) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                linesContainer.suppressLayout(true);
            }
            try {
                for (int i = 0; i < lineRows.size(); i++) {
                    if (lineRows.get(i).getVisibility() != VISIBLE) {
                        lineRows.get(i).setVisibility(VISIBLE);
                    }
                }
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    linesContainer.suppressLayout(false);
                }
            }
            lastOverlayIndex = index;
            return;
        }

        if (!modeChanged && index == lastOverlayIndex) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            linesContainer.suppressLayout(true);
        }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                linesContainer.suppressLayout(false);
            }
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
            markActiveLine(index, current, true);
            applyLineOverlay(index);
            scrollAnchorIntoView(Math.max(index, 0), false);
            return;
        }

        if (highlightedIndex >= 0 && highlightedIndex < lineViews.size()) {
            markActiveLine(highlightedIndex, current, false);
            fadeTo(lineViews.get(highlightedIndex), INACTIVE_LINE_ALPHA);
            lineViews.get(highlightedIndex).setTextColor(LyricsColors.unsungWordColor());
            forEachBgLine(highlightedIndex, current, i -> {
                fadeTo(lineViews.get(i), INACTIVE_LINE_ALPHA);
                lineViews.get(i).setTextColor(LyricsColors.unsungWordColor());
            });
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

        markActiveLine(index, current, true);

        fadeTo(lineViews.get(index), 1f);
        lineViews.get(index).setTextColor(LyricsColors.lineTextColor());
        forEachBgLine(index, current, i -> {
            fadeTo(lineViews.get(i), 1f);
            lineViews.get(i).setTextColor(LyricsColors.lineTextColor());
        });

        if (SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
            return;
        }
        scrollAnchorIntoView(index, true);
    }

    private void updateWordSync(long positionMs) {
        try {
            karaokeState.advance(positionMs, highlightedIndex,
                    Settings.LYRICS_WORD_SYNC.get(), wordTimings, karaokeActions);
            for (KaraokeHighlightState.Action action : karaokeActions) {
                executeWordSync(action);
            }
        } finally {
            karaokeActions.clear();
        }
    }

    private void executeWordSync(KaraokeHighlightState.Action action) {
        switch (action.kind()) {
            case PAINT -> {
                applyWordColors(action.line(), action.posMs(), action.allSung());
                applyBgWordColors(action.line(), action.posMs(), action.allSung());
            }
            case CLEAR -> clearWordHighlight(action.line());
            case CLEAR_FADE -> {
                clearWordHighlight(action.line());
                fadeTo(lineViews.get(action.line()), INACTIVE_LINE_ALPHA);
            }
            case FADE -> fadeTo(lineViews.get(action.line()), INACTIVE_LINE_ALPHA);
            case RESET_ACTIVE -> resetLineForWordSyncOff(action.line(), true);
            case RESET_INACTIVE -> resetLineForWordSyncOff(action.line(), false);
        }
    }

    private void resetLineForWordSyncOff(int line, boolean activeLine) {
        lineViews.get(line).setTextColor(
                activeLine ? LyricsColors.lineTextColor() : LyricsColors.unsungWordColor());
        ForegroundColorSpan cached = line < lineUnsungSpans.size()
                ? lineUnsungSpans.get(line) : null;
        if (cached != null && lineViews.get(line).getText() instanceof Spannable) {
            ((Spannable) lineViews.get(line).getText()).removeSpan(cached);
            lineUnsungSpans.set(line, null);
        }
        if (lineViews.get(line) instanceof LyricsLineView lineView) {
            lineView.setHighlight(Collections.emptyList(), 0, false, 0, -1);
        }
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
        final boolean usePerWord = perWordRomajiSnap && line.hasWords() && LyricsSpanBuilder.lineHasWordRomaji(line);
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

        List<LyricsSpanBuilder.WordTiming> timings = lineWordSpans.get(index);
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
                    timings, positionMs, allSung, LyricsColors.unsungWordColor(), origStart);
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
        footer.setTextColor(LyricsColors.secondaryTextColor());
        // The secondary color alone is brighter than the app draws this line, which
        // sits dimmer than even the inactive lyrics above it.
        footer.setAlpha(FOOTER_ALPHA);
    }

    /**
     * Where the label sits inside its row: flush to the line's left edge, so the text it draws lands
     * {@link #SECTION_TAG_PADDING_HORIZONTAL} in, right where the dot row below it starts.
     */
    private static FrameLayout.LayoutParams tagLayoutParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
    }

    /**
     * Where the dot row sits inside a label or slot. The margin lines its first dot up with the
     * label's text, which the pill's own padding sets that far in from the pill's outer edge.
     */
    private static FrameLayout.LayoutParams dotsLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        params.leftMargin = SECTION_TAG_PADDING_HORIZONTAL;
        return params;
    }

    /**
     * How the label or slot is placed in its line: wrapped to what it holds, and set off the line
     * above by the same gap whether it is showing a label or making room for the dots.
     */
    private static FrameLayout.LayoutParams slotLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = Dim.dp24;
        return params;
    }

    /**
     * The height a slot opens to, taken from a label once one has been measured and otherwise
     * worked out from the label's own text size and padding.
     */
    private int targetSectionSlotHeight() {
        if (sectionTagHeightPx > 0) {
            return sectionTagHeightPx;
        }
        final float textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                sectionTagTextSizeSp(), getResources().getDisplayMetrics());
        final Paint metricsPaint = new Paint();
        metricsPaint.setTextSize(textPx);
        final Paint.FontMetrics fontMetrics = metricsPaint.getFontMetrics();
        final int textHeight = Math.round(fontMetrics.descent - fontMetrics.ascent);
        return Math.max(0, textHeight + Dim.dp4 * 2);
    }

    private static int lineTextSizeSp(boolean isBackground, int textSize) {
        return isBackground
                ? Math.round(textSize * BACKGROUND_TEXT_SIZE_FACTOR)
                : textSize;
    }

    private static int sectionTagTextSizeSp() {
        return Math.round(Settings.LYRICS_TEXT_SIZE.get() * SECTION_TAG_TEXT_SIZE_FACTOR);
    }

    private static int scaledPixels(@NonNull Context context, float factor) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                Settings.LYRICS_TEXT_SIZE.get() * factor,
                context.getResources().getDisplayMetrics()));
    }

    /**
     * Builds the small label that introduces a section, drawn as a white pill above the line it
     * belongs to.
     *
     * @return {@code null} when the line opens no section. The label is always built when there
     *         is one, so flipping the setting on can reveal it without rebuilding the lyrics;
     *         whether it is shown is decided by its visibility.
     */
    @Nullable
    private static TextView createSectionTag(Context context, @Nullable String songPart) {
        if (songPart == null || songPart.isEmpty()) {
            return null;
        }
        TextView tag = new TextView(context);
        tag.setText(songPart);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, sectionTagTextSizeSp());
        tag.setTextColor(Color.BLACK);
        tag.setSingleLine(true);
        tag.setGravity(Gravity.CENTER);
        tag.setIncludeFontPadding(false);
        tag.setBackground(new PillDrawable());
        tag.setPadding(SECTION_TAG_PADDING_HORIZONTAL, Dim.dp4, SECTION_TAG_PADDING_HORIZONTAL, Dim.dp4);
        tag.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tag.setVisibility(Settings.LYRICS_SHOW_SECTION.get() ? VISIBLE : GONE);
        return tag;
    }

    private static final class PillDrawable extends GradientDrawable {

        PillDrawable() {
            setColor(Color.argb(Math.round(255 * PILL_ALPHA), 255, 255, 255));
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            setCornerRadius(bounds.height() / 2f);
        }
    }

    /** Shows or hides every section label without rebuilding the lines. */
    public void applyShowSection() {
        final int visibility = Settings.LYRICS_SHOW_SECTION.get() ? VISIBLE : GONE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            linesContainer.suppressLayout(true);
        }
        try {
            // A label caught mid-swap would otherwise come back without its opacity, and dots
            // belonging to a hidden label would keep breathing.
            resetSectionDots();
            final boolean showing = visibility == VISIBLE;
            for (int i = 0; i < sectionTags.size(); i++) {
                final TextView tag = sectionTags.get(i);
                if (tag == null) {
                    continue;
                }
                tag.setAlpha(1f);
                if (tag.getVisibility() != visibility) {
                    tag.setVisibility(visibility);
                }
                applySectionSlotMargin(i, showing ? Dim.dp24 : 0);
            }
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                linesContainer.suppressLayout(false);
            }
        }
    }

    private void createToolbarButtons() {
        copyView = createToolbarButton(buttonRow, Settings.LYRICS_SHOW_COPY_BUTTON.get(),
                COPY_ICON,
                view -> onCopyClicked(),
                view -> {
                    onCopyLongPressed();
                    return true;
                },
                false);

        translateView = createToolbarButton(buttonRow, Settings.LYRICS_SHOW_TRANSLATE_BUTTON.get(),
                APP_TRANSLATE_ICON,
                view -> onTranslateClicked(),
                view -> {
                    onTranslateLongPressed();
                    return true;
                },
                true);

        romanizeView = createToolbarButton(buttonRow, Settings.LYRICS_SHOW_ROMANIZE_BUTTON.get(),
                APP_ROMANIZE_ICON,
                view -> onRomanizeClicked(),
                view -> {
                    onRomanizeLongPressed();
                    return true;
                },
                true);

        refreshView = createToolbarButton(buttonRow, Settings.LYRICS_SHOW_REFRESH_BUTTON.get(),
                REFRESH_ICON,
                view -> onRefreshClicked(),
                view -> {
                    onRefreshLongPressed();
                    return true;
                },
                true);
    }

    public void applyToolbarButtonSettings() {
        buttonRow.removeAllViews();
        createToolbarButtons();
        updateTranslateLabel();
        updateRomanizeLabel();
        updateRefreshLabel();
    }

    public void applyTextSize() {
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int count = Math.min(lineViews.size(), lineIsBackground.size());
        for (int i = 0; i < count; i++) {
            final boolean isBackground = Boolean.TRUE.equals(lineIsBackground.get(i));
            lineViews.get(i).setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    lineTextSizeSp(isBackground, textSize));
        }
        final int tagSize = sectionTagTextSizeSp();
        for (TextView tag : sectionTags) {
            if (tag != null) {
                tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, tagSize);
            }
        }
        for (LinearLayout dotsRow : sectionDots) {
            applySectionDotSize(dotsRow);
        }
        sectionTagHeightPx = 0;
        for (TextView tag : sectionTags) {
            if (tag == null || tag.getVisibility() != VISIBLE) {
                continue;
            }
            final TextView measuredTag = tag;
            measuredTag.post(() -> {
                if (measuredTag.getHeight() > 0) {
                    sectionTagHeightPx = measuredTag.getHeight();
                }
            });
        }
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    public void applyHideInfo() {
        final CharSequence info = creditView.getText();
        final boolean visible = !Settings.LYRICS_HIDE_INFO.get()
                && info != null && info.length() > 0;
        creditView.setVisibility(visible ? VISIBLE : GONE);
    }

    public void applyLineOverlaySettings() {
        applyLineOverlay(highlightedIndex);
    }

    /**
     * Styles the button as a pill, the shape the app uses for the buttons under its
     * own lyrics, with the background taken from the app palette so it follows the theme.
     *
     * @param iconName Drawable name for the button icon, or {@code null} for a text only button.
     */
    private TextView createToolbarButton(LinearLayout parent, boolean enabled,
                                         @Nullable String iconName,
                                         View.OnClickListener onClick,
                                         View.OnLongClickListener onLongClick,
                                         boolean startMargin) {
        if (!enabled) {
            return null;
        }
        TextView button = new TextView(parent.getContext());
        applyButtonStyle(button, iconName);
        button.setOnClickListener(onClick);
        button.setOnLongClickListener(onLongClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (startMargin) {
            params.setMarginStart(Dim.dp12);
        }
        parent.addView(button, params);
        return button;
    }

    private void applyButtonStyle(TextView button, @Nullable String iconName) {
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SIZE_SP);
        button.setTextColor(LyricsColors.lineTextColor());
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
        icon.setTint(LyricsColors.lineTextColor());
        final int iconSize = Dim.dp24;
        icon.setBounds(0, 0, iconSize, iconSize);
        button.setCompoundDrawablesRelative(icon, null, null, null);
        // No text yet (icon-only default): without padding the icon stays centred.
        button.setCompoundDrawablePadding(0);
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
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
                active ? ThemeUtils.getAppBackgroundColor() : LyricsColors.lineTextColor());
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

    private void flashButtonLabel(@Nullable TextView button, @Nullable String text,
                                  Runnable reset, long durationMs) {
        setButtonLabel(button, text, true);
        handler.removeCallbacks(reset);
        handler.postDelayed(reset, durationMs);
    }

    private static boolean hasTranslation(@Nullable List<String> translated, List<LyricsLine> originals) {
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

    private static final class OffsetRulerView extends View {
        private static final int RANGE_MS = 30000;

        private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int currentOffsetMs;

        @Nullable
        private Long recordTimeMs;

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

        void setRecordTimeMs(@Nullable Long ms) {
            recordTimeMs = ms;
            invalidate();
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

            String offsetText = (currentOffsetMs >= 0 ? "+" : "") + currentOffsetMs + "ms";
            String text = recordTimeMs == null
                    ? offsetText
                    : SponsorBlockMusicSubmitDialog.formatTimeMs(recordTimeMs) + " " + offsetText;
            valuePaint.setTextSize(13 * density);
            canvas.drawText(text, w / 2f, h / 2f + 5 * density, valuePaint);
        }

    }
}
