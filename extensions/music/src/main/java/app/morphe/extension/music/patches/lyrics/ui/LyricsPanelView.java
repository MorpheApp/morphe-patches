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
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
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
import app.morphe.extension.music.patches.lyrics.LyricsPresentation;
import app.morphe.extension.music.patches.lyrics.derive.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.derive.LyricsTranslator;
import app.morphe.extension.music.patches.lyrics.derive.QuickLRCClient;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsBracketSplitter;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
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

    private static final String LYRICS_CONTRIBUTE_CREDIT_KEY =
            "morphe_music_lyrics_contribute_credit";

    private static final float BUTTON_TEXT_SIZE_SP = 14;

    static final float SECTION_DOT_SIZE_FACTOR = 0.36f;
    static final float SECTION_DOT_GAP_FACTOR = 0.22f;

    private static final long SECTION_TAG_FADE_IN_MILLIS = 250;

    // Derived from the lyrics text size, so the pill and dots keep their proportions together.
    static final float SECTION_TAG_PADDING_HORIZONTAL_FACTOR = 0.42f;
    private static final float SECTION_TAG_PADDING_VERTICAL_FACTOR = 0.17f;

    // The app's own lyrics-button resources, so the pills follow its palette and icons.
    private static final String APP_BUTTON_BACKGROUND_COLOR = "ytm_color_white_at_10pct";
    private static final int ACTIVE_BUTTON_BG_COLOR = 0xFFFFFFFF;
    static final long BUTTON_STATE_FADE_MILLISECONDS = 150;
    private static final String APP_TRANSLATE_ICON = "yt_outline_experimental_translate_vd_theme_24";

    /** Shows the pronunciation above each line, which the app's own romanize icon does. */
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
    /** The pill standing in for both buttons while the dual mode is up. */
    @Nullable
    private TextView dualPillView;
    private boolean dualPillActive;
    // Loaded once per pill and kept, because only their order ever changes. Loading them again
    // would drop whatever tint the button colors put on them, and a lookup that came back empty
    // would set that side of the pill to nothing.
    private Drawable dualPillTranslateIcon;
    private Drawable dualPillRomanizeIcon;
    /** Copy button, or {@code null} when hidden by settings. */
    @Nullable
    private TextView copyView;
    @Nullable
    private TextView refreshView;
    private final LinearLayout footerContainer;
    private final LinearLayout buttonRow;
    private final ProgressBar progressBar;
    private final TextView transcribePromptView;

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
    @Nullable
    private String romanizedLocalDictionary;
    /** The lyric carries a reading on its words, whether a whole-line one came back too. */
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
    private int transcribeRequestId;
    /** Set while a run is going, so the affordances can say so before the client knows it. */
    private boolean transcribeBusy;
    /** The aligned lyrics waiting to be applied, and the track they belong to. */
    @Nullable
    private Lyrics pendingAlignment;
    @Nullable
    private TrackInfo pendingAlignmentTrack;

    // Held so its label can follow the run rather than freezing at open time.
    @Nullable
    private TextView dialogAlignButton;

    enum OnlyMode { NONE, TRANS, ROMA, BOTH }

    private OnlyMode onlyMode = OnlyMode.NONE;

    private final List<TextView> lineViews = new ArrayList<>();

    /** The row each of {@link #lineViews} sits in; a background vocal shares its lead line's row. */
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
    private Lyrics lastRawInputLyrics;
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
            final int sz = size();
            if (line < 0 || line >= sz) {
                return true;
            }
            if (!lineWordSpans.get(line).isEmpty()) {
                return false;
            }
            for (int b = line + 1; b < sz && isSecondaryLineAt(b); b++) {
                if (!lineWordSpans.get(b).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public long firstStart(int line) {
            final int sz = size();
            long minStart = Long.MAX_VALUE;
            if (line >= 0 && line < sz && !lineWordSpans.get(line).isEmpty()) {
                minStart = lineWordSpans.get(line).get(0).startMs();
            }
            for (int b = line + 1; b < sz && isSecondaryLineAt(b); b++) {
                List<LyricsSpanBuilder.WordTiming> bt = lineWordSpans.get(b);
                if (!bt.isEmpty()) {
                    minStart = Math.min(minStart, bt.get(0).startMs());
                }
            }
            return minStart == Long.MAX_VALUE ? 0L : minStart;
        }

        @Override
        public long lastEnd(int line) {
            final int sz = size();
            long maxEnd = 0L;
            if (line >= 0 && line < sz && !lineWordSpans.get(line).isEmpty()) {
                List<LyricsSpanBuilder.WordTiming> timings = lineWordSpans.get(line);
                maxEnd = timings.get(timings.size() - 1).endMs();
            }
            for (int b = line + 1; b < sz && isSecondaryLineAt(b); b++) {
                List<LyricsSpanBuilder.WordTiming> bt = lineWordSpans.get(b);
                if (!bt.isEmpty()) {
                    maxEnd = Math.max(maxEnd, bt.get(bt.size() - 1).endMs());
                }
            }
            return maxEnd;
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

    private final Set<View> hiddenToolbarButtons = new HashSet<>();

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
        // Every mode paints word colors now, so only the toggle decides how fast the tick has to be.
        final boolean wordSyncTick = Settings.LYRICS_WORD_SYNC.get();
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
                }
                // Word colors have one driver, so every mode paints its span alike; left inside the branch above,
                // the word table was computed but never handed to a line view.
                updateWordSync(positionMs);
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

        final int horizontalPadding = Dim.dp24;
        final int verticalPadding = Dim.dp16;

        linesContainer = new LinearLayout(context);
        linesContainer.setOrientation(LinearLayout.VERTICAL);
        linesContainer.setClipChildren(false);
        linesContainer.setClipToPadding(false);
        linesContainer.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        footerView = new LyricsFit.FittingTextView(context);
        LyricsViewSupport.applyFooterStyle(footerView);
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
        LyricsViewSupport.applyFooterStyle(creditView);
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

        // The one thing left to try when a track has no lyrics and a service is configured that
        // could make some, so it takes the place of the empty panel rather than sitting in it.
        transcribePromptView = new TextView(context);
        LyricsViewSupport.applyFooterStyle(transcribePromptView);
        transcribePromptView.setGravity(Gravity.CENTER);
        transcribePromptView.setVisibility(GONE);
        transcribePromptView.setOnClickListener(view -> onTranscribePromptClicked());
        ViewAnimations.applyPressEffect(transcribePromptView);
        LayoutParams promptParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        promptParams.leftMargin = Dim.dp32;
        promptParams.rightMargin = Dim.dp32;
        // Clears the pinned button row, which is where the rest of the panel puts its controls.
        promptParams.bottomMargin = Dim.dp(200);
        addView(transcribePromptView, promptParams);

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
            finishOffsetAdjustment();
        }
        return isOffsetAdjusting || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isOffsetAdjusting) {
            offsetGestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                finishOffsetAdjustment();
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    /** The guard keeps a plain tap from writing what no drag ever changed. */
    private void finishOffsetAdjustment() {
        if (!isOffsetAdjusting) {
            return;
        }
        isOffsetAdjusting = false;
        LyricsManager.getInstance().persistTemporaryOffsetMs();
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
        LyricsManager manager = LyricsManager.getInstance();
        if (sbRecording) {
            final long endMs = manager.getVideoPositionMs();
            String videoId = sbRecordVideoId;
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
            Context context = getContext();
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
        String videoId = manager.skipSegmentsVideoId();
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
            LyricsManager.getInstance().persistTemporaryOffsetMs();
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
            // Bracketed background vocals get lines of their own here, for this panel only. The
            // same lyrics arriving again map to the same split, which the checks below rely on.
            if (newLyrics != null && !newLyrics.isEmpty()) {
                if (newLyrics == lastRawInputLyrics && lyrics != null) {
                    newLyrics = lyrics;
                } else if (newLyrics != lyrics) {
                    lastRawInputLyrics = newLyrics;
                    newLyrics = LyricsBracketSplitter.apply(newLyrics);
                }
            } else {
                lastRawInputLyrics = null;
            }
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
            LyricsManager.getInstance().persistTemporaryOffsetMs();
            stopSbRecording();
            hideOffsetRuler();
            isOffsetAdjusting = false;
            // The track already sits out by its stored offset, so the ruler comes back rather than
            // leaving the shift silent.
            if (LyricsManager.getInstance().getTemporaryOffsetMs() != 0) {
                showOffsetRuler();
            }
            translatedLines = null;
            romanizedLines = null;
            romanizedFromGoogle = false;
            translatedFromGoogle = false;
            translatedFromAI = false;
            romanizedFromAI = false;
            translatedAiModel = null;
            romanizedAiModel = null;
            romanizedLocalDictionary = null;
            perWordRomaji = false;
            // The id bump is what makes in-flight callbacks from the previous track drop out.
            translateRequestId++;
            romanizeRequestId++;
            translateInProgress = false;
            romanizeInProgress = false;
            // A transcription in flight belongs to the track that just left.
            transcribeRequestId++;
            transcribeBusy = false;
            QuickLRCClient.cancel();
            pendingAlignment = null;
            pendingAlignmentTrack = null;
            updateTranscribeAffordances();
            // Dual mode keeps the original underneath, so onlyMode resets while the flags stand.
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
                    if (refreshInProgress) {
                        refreshInProgress = false;
                        updateRefreshLabel();
                    }
                    if (state != LyricsManager.State.ERROR && showTranscribePrompt()) {
                        // There is nothing to show but a way to get some, so the panel stays
                        // open around it instead of handing the screen back to the app.
                        setOverlayVisible(true);
                        showOnlyRefreshButton();
                    } else {
                        hideTranscribePrompt();
                        setOverlayVisible(false);
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
        List<LyricsLine> left = a.lines();
        List<LyricsLine> right = b.lines();
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
        List<LyricsLine> newLines = newLyrics.lines();
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
            String songPart = line.songPart();
            final boolean opensSection = !line.isBG()
                    && songPart != null && !songPart.isEmpty()
                    && !songPart.equals(previousLeadSongPart);
            if (!line.isBG()) {
                previousLeadSongPart = songPart;
            }

            TextView builtTag = sectionTags.get(i);
            FrameLayout builtSlot = sectionDotSlots.get(i);
            if (opensSection && builtTag != null) {
                builtTag.setText(songPart);
                continue;
            }
            if (opensSection) {
                // The label takes over the slot that was standing in for it.
                if (builtSlot != null) {
                    asRow(lineRows.get(i)).removeView(builtSlot);
                    sectionDotSlots.set(i, null);
                    sectionDots.set(i, null);
                }
                TextView tag = createSectionTag(context, songPart);
                LinearLayout dotsRow = createSectionDots(context);
                boolean isRight = i < lineViews.size()
                        && (lineViews.get(i).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END;
                FrameLayout tagRow = new FrameLayout(context);
                tagRow.addView(tag, tagLayoutParams(isRight));
                tagRow.addView(dotsRow, dotsLayoutParams(context, isRight));
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
        List<SectionDotsMotion.Gap> newGaps = SectionDotsMotion.computeGaps(newLines);
        sectionDotsGaps = newGaps;
        ensureDotsSlotsExist(context, newGaps);
        return true;
    }

    /**
     * Gives a slot to every line a gap points at that has no label to stand in for it, so a silence
     * that starts showing up after the rows were built still has somewhere to play.
     */
    @SuppressWarnings("ConstantValue")
    private void ensureDotsSlotsExist(Context context, List<SectionDotsMotion.Gap> gaps) {
        for (SectionDotsMotion.Gap gap : gaps) {
            final int index = gap.tagLineIndex();
            if (index < 0 || index >= sectionDotSlots.size()
                    || sectionTags.get(index) != null || sectionDotSlots.get(index) != null) {
                continue;
            }
            ViewGroup existingRow = asRow(lineRows.get(index));
            if (existingRow == null) {
                continue;
            }
            LinearLayout dotsRow = createSectionDots(context);
            boolean isRight = index < lineViews.size()
                    && (lineViews.get(index).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END;
            FrameLayout slot = new FrameLayout(context);
            slot.addView(dotsRow, dotsLayoutParams(context, isRight));
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

    @SuppressWarnings({"deprecation", "SizeReplaceableByIsEmpty"})
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
        List<TextView> prevViews = sameContent
                ? new ArrayList<>(lineViews) : Collections.emptyList();
        int[] prevHeights = new int[prevCount];
        for (int i = 0; i < prevCount; i++) {
            prevHeights[i] = i < lineRows.size() ? lineRows.get(i).getHeight() : 0;
        }
        List<List<LyricsSpanBuilder.WordTiming>> prevSpans = sameContent
                ? new ArrayList<>(lineWordSpans) : Collections.emptyList();
        final int prevHighlighted = highlightedIndex;
        lastBuiltLyrics = newLyrics;
        clearLines();
        LyricsColors.invalidate();
        progressBar.setVisibility(GONE);
        hideTranscribePrompt();
        scrollView.setVisibility(VISIBLE);

        final boolean contentInBuild =
                onlyMode != OnlyMode.NONE || wholeFadeNextBuild;
        wholeFadeNextBuild = false;

        Context context = getContext();
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int foregroundColor = LyricsColors.lineTextColor();
        final boolean lineGestures = Settings.LYRICS_TAP_TO_SEEK.get();
        final boolean tapToSeek = newLyrics.synced() && lineGestures;
        Paint baseLinePaint = new Paint();
        baseLinePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize,
                getResources().getDisplayMetrics()));
        LyricsViewSupport.applyLyricsBoldPaint(baseLinePaint);
        baseLinePaint.setElegantTextHeight(true);

        final int generation = buildGeneration.get();
        OnlyMode onlySnap = onlyMode;
        builtOnlyMode = onlySnap;
        String previousLeadSongPart = null;
        // Needed up front: a line without a label only gets a slot if a silence calls for one.
        List<SectionDotsMotion.Gap> builtGaps = SectionDotsMotion.computeGaps(newLyrics.lines());
        boolean[] gapAnchors = new boolean[newLyrics.lines().size()];
        for (SectionDotsMotion.Gap gap : builtGaps) {
            final int anchor = gap.tagLineIndex();
            if (anchor >= 0 && anchor < gapAnchors.length) {
                gapAnchors[anchor] = true;
            }
        }
        // A duet puts its second singer on the opposite side of each line's natural direction.
        boolean isDuetSong = false;
        for (LyricsLine l : newLyrics.lines()) {
            if (l.isDuet()) {
                isDuetSong = true;
                break;
            }
        }
        final int lineTotalCount = newLyrics.lines().size();
        boolean[] isSecFlags = new boolean[lineTotalCount];
        for (int i = 0; i < lineTotalCount; i++) {
            isSecFlags[i] = isSecondaryLyricLine(i, newLyrics.lines());
        }

        boolean[] lineRightAligned = new boolean[lineTotalCount];
        int curParentForAlign = -1;
        for (int i = 0; i < lineTotalCount; i++) {
            if (!isSecFlags[i]) {
                curParentForAlign = i;
            }
            LyricsLine l = newLyrics.lines().get(i);
            boolean lineRtl = ScriptDetector.isLineRtl(l.text());
            boolean isDuetLine = l.isDuet()
                    || (isSecFlags[i] && curParentForAlign >= 0
                            && newLyrics.lines().get(curParentForAlign).isDuet());
            lineRightAligned[i] = isDuetSong
                    ? (lineRtl ? !isDuetLine : isDuetLine)
                    : lineRtl;
        }
        final int duetSideInset = Math.max(Dim.dp(48),
                (int) (context.getResources().getDisplayMetrics().widthPixels * 0.18f));

        LinearLayout currentParentRow = null;
        int currentParentIdx = -1;

        for (int i = 0; i < lineTotalCount; i++) {
            LyricsLine line = newLyrics.lines().get(i);
            final boolean isSec = isSecFlags[i] && currentParentRow != null;
            final boolean isRightAligned = lineRightAligned[i];
            LyricsLineView prevView = i < prevCount
                    && prevViews.get(i) instanceof LyricsLineView pv ? pv : null;
            final boolean reusePrevious = prevView != null && !contentInBuild;
            List<LyricsSpanBuilder.WordTiming> gapTimings = reusePrevious
                    && i < prevSpans.size()
                    ? prevSpans.get(i) : Collections.emptyList();
            lineWordSpans.add(reusePrevious ? gapTimings : new ArrayList<>());

            LyricsLineView lineView = new LyricsLineView(context);
            lineView.setSecondary(isSec);
            lineView.setIsRightAligned(isRightAligned);
            // Plain text first so the panel paints immediately; the karaoke spans are added on a
            // background thread (see the LINE_BUILDER_EXECUTOR pass below). A hide rebuild keeps
            // the surviving regions so its first frame matches the shrunken layout exactly, and a
            // same-content rebuild reuses the previous row so toggles never blink existing text.
            int gapOrigStart = 0;
            if (preserveHidePosition) {
                LyricsSpanBuilder.BuildResult result;
                if (LyricsPresentation.isFormattingEnabled()) {
                    // The previous spans are offsets in the filtered text, so the line is built
                    // again from the raw word timings and filtered once more.
                    List<LyricsSpanBuilder.WordTiming> rawTimings = LyricsSpanBuilder
                            .computeWordTimings(newLyrics.lines(), i, onlySnap != OnlyMode.NONE);
                    LyricsSpanBuilder.BuildResult raw = LyricsSpanBuilder.buildLineText(line,
                            rawTimings, i, perWordRomaji, romanizedLines, translatedLines, onlyMode,
                            baseLinePaint, isRightAligned);
                    LyricsSpanBuilder.PresentedLine presentation = LyricsSpanBuilder.present(raw,
                            LyricsViewSupport.wordSpansFor(raw, onlySnap != OnlyMode.NONE, rawTimings));
                    result = presentation.display();
                    gapTimings = presentation.words();
                    gapOrigStart = presentation.originalStart();
                    lineWordSpans.set(i, gapTimings);
                } else {
                    result = LyricsSpanBuilder.buildLineText(line, gapTimings, i,
                            perWordRomaji, romanizedLines, translatedLines, onlyMode,
                            baseLinePaint, isRightAligned);
                    gapOrigStart = result.originalStart();
                }
                lineView.setText(result.text());
                lineView.setTranslationBounds(result.transStart(), result.transEnd());
                lineView.setRomanizationBounds(result.romaStart(), result.romaEnd(),
                        result.readingSpans() != null && !result.readingSpans().isEmpty(),
                        result.showSyllableRomanization());
                lineView.setSmallRunHoldsWords(result.smallRunHoldsWords());
            } else if (reusePrevious) {
                lineView.setText(prevView.getText());
                lineView.setTranslationBounds(prevView.transStart, prevView.transEnd);
                lineView.setRomanizationBounds(prevView.romaStart, prevView.romaEnd,
                        prevView.perWordReading, prevView.showSyllableRomanization);
                lineView.setSmallRunHoldsWords(prevView.smallRunHoldsWords);
                lineView.transReveal = prevView.transReveal;
                lineView.romaReveal = prevView.romaReveal;
                gapOrigStart = prevView.originalTextStart;
            } else {
                String formatted = LyricsPresentation.formatText(LyricsSpanBuilder.displayLineText(line));
                String rawText = formatted.isEmpty() ? "♪" : formatted;
                boolean isRtl = ScriptDetector.isLineRtl(rawText);
                SpannableString initialText = new SpannableString(rawText);
                initialText.setSpan(new AlignmentSpan.Standard(LyricsSpanBuilder.resolveAlignment(isRtl, isRightAligned)),
                        0, initialText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                lineView.setText(initialText);
            }
            lineOriginalStarts.add(gapOrigStart);
            if (reusePrevious) {
                lineView.setHighlight(gapTimings, prevView.positionMs, prevView.allSung,
                        LyricsColors.unsungWordColor(), gapOrigStart);
            }
            final int effectiveLeadIdx = isSec ? currentParentIdx : i;
            final boolean initialActive = !newLyrics.synced()
                    || (reusePrevious && (effectiveLeadIdx == prevHighlighted || i == prevHighlighted));
            lineView.setActiveLine(initialActive, LyricsColors.secondaryTextColor());
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    LyricsViewSupport.lineTextSizeSp(isSec, textSize));
            lineView.setTextColor(foregroundColor);
            lineView.lineAlpha = initialActive ? 1f : (isSec ? 1f : INACTIVE_LINE_ALPHA);
            lineView.lineScale = initialActive
                    ? LyricsViewSupport.ACTIVE_LINE_SCALE
                    : LyricsViewSupport.INACTIVE_LINE_SCALE;
            if (!isSec) {
                lineView.setAlpha(1f);
                LyricsViewSupport.applyLineScaleTransform(lineView, lineView.lineScale);
            }
            lineView.contentReveal = contentInBuild ? 0f : 1f;
            lineView.setPadding(0, isSec ? Dim.dp(2) : Dim.dp(8), 0, isSec ? Dim.dp(2) : Dim.dp(8));
            lineView.setIncludeFontPadding(false);
            lineView.getPaint().setElegantTextHeight(true);
            lineView.setTypeface(null, Typeface.BOLD);
            LyricsViewSupport.applyLyricsBoldPaint(lineView.getPaint());
            lineView.setLetterSpacing(LyricsViewSupport.lineLetterSpacing(line.text(), textSize));

            if (newLyrics.synced()) {
                lineView.setTextColor(LyricsColors.unsungWordColor());
            }

            if (tapToSeek) {
                lineView.setOnClickListener(view -> {
                    final long videoLength = VideoInformation.getVideoLength();
                    LyricsManager manager = LyricsManager.getInstance();
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

            if (lineGestures) {
                lineView.setOnLongClickListener(v -> {
                    LyricsLineView lv = (LyricsLineView) v;
                    String textToCopy = lv.getCopyTextForTouch(lv.lastTouchY);
                    if (textToCopy == null || textToCopy.isEmpty()) {
                        textToCopy = onlyMode != OnlyMode.NONE
                                ? String.valueOf(lv.getText())
                                : (isSec ? LyricsBracketSplitter.stripOuterBrackets(line.text()) : line.text());
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
            }

            if (isRightAligned) {
                lineView.setGravity(Gravity.END | Gravity.TOP);
            } else {
                lineView.setGravity(Gravity.START | Gravity.TOP);
            }

            // A background vocal goes into its lead line's row, above the lead when sung ahead of it.
            if (isSec && currentParentRow != null) {
                final boolean bgBeforeMain = isBgBeforeMain(newLyrics.lines(), currentParentIdx, i);
                lineView.setParentLineIndex(currentParentIdx);
                lineView.setBgBeforeMain(bgBeforeMain);

                LinearLayout.LayoutParams bgParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (bgBeforeMain && currentParentIdx >= 0 && currentParentIdx < lineViews.size()) {
                    bgParams.bottomMargin = initialActive ? Dim.dp(4) : 0;
                    TextView parentMainView = lineViews.get(currentParentIdx);
                    final int mainIdxInRow = currentParentRow.indexOfChild(parentMainView);
                    currentParentRow.addView(lineView, Math.max(0, mainIdxInRow), bgParams);
                } else {
                    bgParams.topMargin = initialActive ? Dim.dp(4) : 0;
                    currentParentRow.addView(lineView, bgParams);
                }
                lineView.animateSecondaryExpansion(initialActive, true);

                lineViews.add(lineView);
                lineRows.add(currentParentRow);
                sectionTags.add(null);
                sectionDots.add(null);
                sectionDotSlots.add(null);
                lineUnsungSpans.add(null);
                lineIsBackground.add(true);
                continue;
            }

            currentParentIdx = i;
            LinearLayout lineRow = new LinearLayout(context);
            lineRow.setOrientation(LinearLayout.VERTICAL);
            lineRow.setClipChildren(false);
            lineRow.setClipToPadding(false);
            currentParentRow = lineRow;

            if (isDuetSong) {
                if (isRightAligned) {
                    lineRow.setPadding(duetSideInset, Dim.dp(2), 0, Dim.dp(2));
                } else {
                    lineRow.setPadding(0, Dim.dp(2), duetSideInset, Dim.dp(2));
                }
            } else {
                lineRow.setPadding(0, Dim.dp(2), 0, Dim.dp(2));
            }

            // The dots measure the silence on the same lines as the labels, so a label and the
            // dots standing in for it always land on the same line.
            String songPart = line.songPart();
            // Only a lead line gets here, so a background vocal never opens a section of its own.
            final boolean opensSection = songPart != null && !songPart.isEmpty()
                    && !songPart.equals(previousLeadSongPart);
            previousLeadSongPart = songPart;
            TextView sectionTag = createSectionTag(context,
                    opensSection ? songPart : null);
            LinearLayout sectionDotsRow = null;
            FrameLayout sectionDotSlot = null;
            if (sectionTag != null) {
                sectionDotsRow = createSectionDots(context);
                FrameLayout tagRow = new FrameLayout(context);
                tagRow.addView(sectionTag, tagLayoutParams(isRightAligned));
                tagRow.addView(sectionDotsRow, dotsLayoutParams(context, isRightAligned));
                lineRow.addView(tagRow, slotLayoutParams());
                TextView measuredTag = sectionTag;
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
                sectionDotSlot.addView(sectionDotsRow, dotsLayoutParams(context, isRightAligned));
                FrameLayout.LayoutParams slotParams = slotLayoutParams();
                // Starts fully closed: the dots row inside must not take space until a gap opens
                // it, and neither must the margin, which a zero-height child still occupies.
                slotParams.height = 0;
                slotParams.topMargin = 0;
                lineRow.addView(sectionDotSlot, slotParams);
            }

            LinearLayout.LayoutParams lineViewParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lineRow.addView(lineView, lineViewParams);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            // Above the footer, which stays below the lyrics.
            linesContainer.addView(lineRow, linesContainer.indexOfChild(footerContainer), rowParams);
            lineViews.add(lineView);
            lineRows.add(lineRow);
            sectionTags.add(sectionTag);
            sectionDots.add(sectionDotsRow);
            sectionDotSlots.add(sectionDotSlot);
            lineUnsungSpans.add(null);
            lineIsBackground.add(false);
        }

        sectionDotsGaps = builtGaps;

        LINE_BUILDER_EXECUTOR.execute(() -> {
            try {
                final int lineCount = newLyrics.lines().size();
                List<List<LyricsSpanBuilder.WordTiming>> allTimings = new ArrayList<>(lineCount);
                for (int i = 0; i < lineCount; i++) {
                    if (generation != buildGeneration.get() || lyrics != newLyrics) {
                        return;
                    }
                    allTimings.add(LyricsSpanBuilder.computeWordTimings(
                            newLyrics.lines(), i, onlySnap != OnlyMode.NONE));
                }
                handler.post(() -> {
                    if (generation != buildGeneration.get() || lyrics != newLyrics) {
                        return;
                    }
                    final boolean only = onlyMode != OnlyMode.NONE;
                    final int count = Math.min(allTimings.size(), lineViews.size());
                    int[] plainHeights = new int[count];
                    boolean[] freshTrans = new boolean[count];
                    boolean[] freshRoma = new boolean[count];
                    for (int i = 0; i < count; i++) {
                        final int h = lineRows.get(i).getHeight();
                        plainHeights[i] = h > 1 ? h : (i < prevHeights.length ? prevHeights[i] : h);
                        TextView prevRaw = i < prevViews.size() ? prevViews.get(i) : null;
                        LyricsLineView prevView = prevRaw instanceof LyricsLineView pv
                                ? pv : null;
                        freshTrans[i] = !(prevView != null && prevView.transStart >= 0
                                && prevView.transReveal >= 1f);
                        freshRoma[i] = !(prevView != null && prevView.hasRomanizationRegion()
                                && prevView.romaReveal >= 1f);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        linesContainer.suppressLayout(true);
                    }
                    try {
                        for (int i = 0; i < count; i++) {
                            // A pass already under way when the next build began lays
                            // its lines out against the new rows, so a word table built
                            // for one song lands on another's text and is dropped as
                            // out of range, leaving that line lit in one go.
                            if (generation != buildGeneration.get() || lyrics != newLyrics
                                    || i >= lineWordSpans.size()
                                    || i >= lineViews.size()
                                    || i >= lineOriginalStarts.size()) {
                                return;
                            }
                            TextView tv = lineViews.get(i);
                            if (i < newLyrics.lines().size()) {
                                LyricsSpanBuilder.BuildResult result =
                                        LyricsSpanBuilder.buildLineText(newLyrics.lines().get(i),
                                                allTimings.get(i), i,
                                                perWordRomaji, romanizedLines, translatedLines, onlyMode,
                                                baseLinePaint, lineRightAligned[i]);
                                LyricsSpanBuilder.PresentedLine presentation = LyricsSpanBuilder.present(result,
                                        LyricsViewSupport.wordSpansFor(result, only, allTimings.get(i)));
                                result = presentation.display();
                                lineOriginalStarts.set(i, presentation.originalStart());
                                tv.setText(result.text());
                                lineUnsungSpans.set(i, result.unsungSpan());
                                List<LyricsSpanBuilder.WordTiming> resolvedSpans = presentation.words();
                                lineWordSpans.set(i, resolvedSpans);
                                if (tv instanceof LyricsLineView lineView) {
                                    lineView.setTranslationBounds(result.transStart(), result.transEnd());
                                    lineView.setRomanizationBounds(result.romaStart(), result.romaEnd(),
                                            result.readingSpans() != null
                                                    && !result.readingSpans().isEmpty(),
                                            result.showSyllableRomanization());
                                    lineView.setSmallRunHoldsWords(result.smallRunHoldsWords());
                                    // The view lays syllable readings out from the words, so they
                                    // go in now for the row to be measured with them below.
                                    if (result.showSyllableRomanization()) {
                                        lineView.setHighlight(resolvedSpans, lineView.positionMs,
                                                lineView.allSung, LyricsColors.unsungWordColor(),
                                                presentation.originalStart());
                                    }
                                    if (!contentInBuild && !preserveHidePosition) {
                                        if (result.transStart() >= 0 && freshTrans[i]) {
                                            lineView.transReveal = 0f;
                                        }
                                        if ((result.romaStart() >= 0 || result.showSyllableRomanization())
                                                && freshRoma[i]) {
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
                    List<RowResize> resizes = new ArrayList<>();
                    if (!contentInBuild) {
                        for (int i = 0; i < count; i++) {
                            // A shared row is resized once, with its lead line.
                            if (i > 0 && lineRows.get(i) == lineRows.get(i - 1)) {
                                continue;
                            }
                            final int oldH = plainHeights[i];
                            if (oldH <= 1 || lineRows.get(i).getWidth() <= 0
                                    || !(lineViews.get(i) instanceof LyricsLineView view)) {
                                continue;
                            }
                            TextView tv = lineViews.get(i);
                            View row = lineRows.get(i);
                            row.measure(View.MeasureSpec.makeMeasureSpec(
                                            row.getWidth(), View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0,
                                            View.MeasureSpec.UNSPECIFIED));
                            final int newH = row.getMeasuredHeight();
                            if (Math.abs(newH - oldH) <= 1) {
                                continue;
                            }
                            Layout layout = tv.getLayout();
                            if (layout == null) {
                                continue;
                            }
                            final int leading = LyricsViewSupport.leadingRegionSize(layout,
                                    view.transStart, view.transEnd)
                                    + LyricsViewSupport.leadingRegionSize(layout, view.romaStart, view.romaEnd);
                            final int trailing = LyricsViewSupport.trailingRegionSize(layout,
                                    view.transStart, view.transEnd)
                                    + LyricsViewSupport.trailingRegionSize(layout, view.romaStart, view.romaEnd)
                                    + view.syllableRomanizationExtraHeight();
                            resizes.add(new RowResize(i, lineRows.get(i), tv, oldH, newH,
                                    leading, trailing));
                        }
                    }
                    Runnable revealTask = () -> {
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
                                if (view.hasRomanizationRegion() && freshRoma[i]) {
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
        boolean invitesContribution = newLyrics.synced()
                && QuickLRCClient.PROVIDER_NAME.equals(newLyrics.providerName());
        if ((songwriters != null && !songwriters.isEmpty()) || hasAbout || invitesContribution) {
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
            if (invitesContribution) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(str(LYRICS_CONTRIBUTE_CREDIT_KEY));
            }
            creditView.setText(sb.toString());
            creditView.setVisibility(Settings.LYRICS_HIDE_INFO.get() ? GONE : VISIBLE);
        } else {
            creditView.setVisibility(GONE);
        }
        footerContainer.setVisibility(VISIBLE);
        footerView.setVisibility(VISIBLE);
        restoreToolbarButtons();
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
                            TextView lineView = lineViews.get(index);
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
        if (dualPillActive) {
            // The pill stands in for both buttons right now, so a tap leaves the dual mode with
            // the two still on underneath.
            exitDualMode(true);
            return;
        }
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

                        // The track moved on while the translation was in flight.
                        if (lyrics != current) {
                            return;
                        }

                        translatedLines = LyricsViewSupport.hasTranslation(lines, current.lines()) ? lines : null;
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
        if (dualPillActive) {
            // A hold on the pill clears both sides, down to nothing on.
            exitDualMode(false);
            return;
        }
        try {
            if (translateView == null) {
                return;
            }
            if (onlyMode == OnlyMode.BOTH) {
                return;
            }
            if (onlyMode == OnlyMode.TRANS || Settings.LYRICS_TRANSLATE_ONLY.get()) {
                cancelTranslateOnly();
                return;
            }
            final boolean sameKeyActive = normalTranslateActive();
            final boolean otherActive = normalRomanizeActive();
            final boolean otherOnly =
                    onlyMode == OnlyMode.ROMA || Settings.LYRICS_ROMANIZE_ONLY.get();
            // A second hold on the side that is already alone is how that side is left; only the
            // hold that has the other side waiting builds the dual mode.
            if (sameKeyActive && !otherActive && !otherOnly) {
                return;
            }

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            // Only a side already in its own mode can be joined into the dual one; from the
            // normal mode a hold still narrows to that side alone, as it always did.
            if (otherOnly) {
                enterDualMode();
                showLyrics(current);
                if (translatedLines == null) {
                    requestTranslateOnly(current);
                }
                return;
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
            if (hadNormalRomanize) {
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
        // The other side is mid-build for the dual mode, so its data is what this runs alongside.
        if (normalRomanizeActive() && !Settings.LYRICS_ROMANIZE_ONLY.get()) {
            disableNormalRomanize();
            updateRomanizeLabel();
        }
        if (translatedLines != null) {
            onlyMode = Settings.LYRICS_ROMANIZE_ONLY.get() ? OnlyMode.BOTH : OnlyMode.TRANS;
            applyOnlyModeState();
            updateDualPill();
            showLyrics(current);
            updateTranslateLabel();
            if (onlyMode == OnlyMode.BOTH && romanizedLines == null && !perWordRomaji) {
                requestRomanizeOnly(current);
            }
            return;
        }
        cancelNormalTranslate();
        translateInProgress = true;
        final int requestId = ++translateRequestId;
        setButtonLabel(translateView, str("morphe_music_lyrics_translating"), true);

        LyricsTranslator.translate(track, current, current.providerName(),
                (lines, fromGoogle, fromAI, model) -> {
                    // A side the dual mode handed back lands as the small run, not as the line.
                    final boolean asOnly = Settings.LYRICS_TRANSLATE_ONLY.get();
                    if (requestId != translateRequestId || !asOnly && !translateInProgress) {
                        return;
                    }
                    if (lyrics != current) {
                        return;
                    }
                    translateInProgress = false;
                    translatedLines = LyricsViewSupport.hasTranslation(lines, current.lines()) ? lines : null;
                    translatedFromGoogle = translatedLines != null && fromGoogle;
                    translatedFromAI = translatedLines != null && fromAI;
                    if (translatedFromAI && model != null) {
                        translatedAiModel = model;
                    }
                    if (translatedLines == null) {
                        Utils.showToastShort(str("morphe_music_lyrics_translate_failed"));
                        fallBackFromDualMode(false);
                        return;
                    }
                    if (!asOnly) {
                        showLyrics(current);
                        flashButtonLabel(translateView,
                                str("morphe_music_lyrics_translate_hide"),
                                updateTranslateLabelRunnable, 3000);
                        return;
                    }
                    onlyMode = Settings.LYRICS_ROMANIZE_ONLY.get() ? OnlyMode.BOTH : OnlyMode.TRANS;
                    applyOnlyModeState();
                    updateDualPill();
                    showLyrics(current);
                    if (onlyMode == OnlyMode.BOTH) {
                        // The other side already flagged itself, so only this one was missing.
                        if (romanizedLines == null && !perWordRomaji) {
                            requestRomanizeOnly(current);
                        }
                        return;
                    }
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
        if (onlyMode == OnlyMode.TRANS || onlyMode == OnlyMode.BOTH) {
            onlyMode = Settings.LYRICS_ROMANIZE_ONLY.get() ? OnlyMode.ROMA : OnlyMode.NONE;
            applyOnlyModeState();
        }
        updateDualPill();
        updateFooter();
    }

    private void clearRomanizeOnlyState() {
        Settings.LYRICS_ROMANIZE_ONLY.save(false);
        cancelNormalRomanize();
        if (onlyMode == OnlyMode.ROMA || onlyMode == OnlyMode.BOTH) {
            onlyMode = Settings.LYRICS_TRANSLATE_ONLY.get() ? OnlyMode.TRANS : OnlyMode.NONE;
            applyOnlyModeState();
        }
        updateDualPill();
        updateFooter();
    }

    /**
     * Leaves the dual mode, the original coming back with whichever side survived as its small
     * run. Entering claims both flags before either side is known to have arrived, so both are
     * given back; reading the other flag instead would land on the normal mode whenever it had
     * already been cleared, costing the row the surviving side was holding.
     */
    private void fallBackFromDualMode(boolean romanizeFailed) {
        // A track change resets onlyMode but not the flags, so only they say what was claimed.
        final boolean dualClaimed = onlyMode == OnlyMode.BOTH
                || Settings.LYRICS_TRANSLATE_ONLY.get() && Settings.LYRICS_ROMANIZE_ONLY.get();
        if (!dualClaimed) {
            // Only this one was ever claimed, so it clears the way it always did.
            if (romanizeFailed) {
                clearRomanizeOnlyState();
            } else {
                clearTranslateOnlyState();
            }
            return;
        }
        final boolean haveTranslate = translatedLines != null;
        final boolean haveRomanize = romanizedLines != null || perWordRomaji;
        Settings.LYRICS_TRANSLATE_ONLY.save(false);
        Settings.LYRICS_ROMANIZE_ONLY.save(false);
        // Only the side that failed: cancelling the other one wipes the very lines that are the
        // reason for keeping it.
        if (romanizeFailed) {
            cancelNormalRomanize();
        } else {
            cancelNormalTranslate();
        }
        if (haveTranslate && haveRomanize) {
            // Both made it in the end; only the flag of the one that reported failure was stale.
            onlyMode = OnlyMode.BOTH;
            Settings.LYRICS_TRANSLATE_ONLY.save(true);
            Settings.LYRICS_ROMANIZE_ONLY.save(true);
        } else {
            // A side that never arrived leaves anything to promote: an only mode would drop the
            // original and give the survivor the whole line, a claim the user never made.
            onlyMode = OnlyMode.NONE;
        }
        applyOnlyModeState();
        updateDualPill();
        updateFooter();
        Lyrics current = lyrics;
        if (current != null) {
            showLyrics(current);
        }
        updateTranslateLabel();
        updateRomanizeLabel();
        // A survivor still fetching puts its loading text back.
        if (translateInProgress) {
            setButtonLabel(translateView, str("morphe_music_lyrics_translating"), true);
        }
        if (romanizeInProgress) {
            setButtonLabel(romanizeView, str("morphe_music_lyrics_romanizing"), true);
        }
    }

    /**
 * Enters the dual mode, where both only-flags stand at once and neither side clears the other.
 */
    private void enterDualMode() {
        Settings.LYRICS_TRANSLATE_ONLY.save(true);
        Settings.LYRICS_TRANSLATE.save(false);
        Settings.LYRICS_ROMANIZE_ONLY.save(true);
        Settings.LYRICS_ROMANIZE.save(false);
        onlyMode = OnlyMode.BOTH;
        applyOnlyModeState();
        updateDualPill();
    }

    /**
     * Leaves the dual mode. With {@code keepData} both stay on and the original comes back
     * underneath them; without it everything is dropped.
     */
    private void exitDualMode(boolean keepData) {
        if (onlyMode != OnlyMode.BOTH) {
            return;
        }
        Settings.LYRICS_TRANSLATE_ONLY.save(false);
        Settings.LYRICS_ROMANIZE_ONLY.save(false);
        if (keepData) {
            Settings.LYRICS_TRANSLATE.save(true);
            Settings.LYRICS_ROMANIZE.save(true);
        } else {
            Settings.LYRICS_TRANSLATE.save(false);
            Settings.LYRICS_ROMANIZE.save(false);
            cancelNormalTranslate();
            cancelNormalRomanize();
        }
        onlyMode = OnlyMode.NONE;
        applyOnlyModeState();
        updateDualPill();
        Lyrics current = lyrics;
        if (current != null) {
            showLyrics(current);
        }
        updateTranslateLabel();
        updateRomanizeLabel();
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
        romanizedLocalDictionary = null;
        updateFooter();
    }

    private void updateFooter() {
        Lyrics current = lyrics;
        if (current == null) {
            return;
        }
        footerView.setText(LyricsViewSupport.sourceText(current.providerName(),
                translatedLines != null, translatedFromGoogle, translatedFromAI, translatedAiModel,
                romanizedLines != null || perWordRomaji,
                romanizedFromGoogle, romanizedFromAI, romanizedAiModel, onlyMode,
                romanizedLocalDictionary));
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

    private void applyOnlyModeState() {
        captureOnlyModeScrollAnchor();
        karaokeState.resetContent();
        karaokeState.syncEnabled(Settings.LYRICS_WORD_SYNC.get());
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
        View row = lineRows.get(highlightedIndex);
        TextView child = lineViews.get(highlightedIndex);
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

    /**
     * What this line shows under the current only mode, and the original outside one. A line the
     * provider left untranslated keeps the original, the same text the panel renders for it.
     */
    private String textToCopyAt(LyricsLine line, int index) {
        if (onlyMode == OnlyMode.TRANS) {
            String translation = LyricsSpanBuilder.translationAt(line, index, translatedLines);
            return translation != null ? translation : line.text();
        }
        if (onlyMode == OnlyMode.ROMA) {
            String romanization =
                    LyricsSpanBuilder.romanizationForOnlyMode(line, index, romanizedLines);
            return romanization != null ? romanization : line.text();
        }
        if (onlyMode == OnlyMode.BOTH) {
            // The same call the render makes, so the copy cannot disagree with the main line.
            return LyricsSpanBuilder.dualLineFor(line, null, index,
                    LyricsSpanBuilder.translationAt(line, index, translatedLines),
                    line.text(), romanizedLines).text();
        }
        return line.text();
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
                // A copy takes what the panel is showing, so an only mode hands over the
                // translation or romanization the lines are rendered from.
                text.append(LyricsPresentation.formatText(textToCopyAt(lines.get(i), i)));
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
            Context appContext = getContext().getApplicationContext();
            Utils.runOnBackgroundThread(() -> {
                try {
                    String savedPath = LyricsFileSaver.save(appContext, track, current);
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
            final boolean on = onlyMode == OnlyMode.TRANS || onlyMode == OnlyMode.BOTH
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
                    (lines, fromGoogle, fromAI, model, perWord, localDictionary) -> {
                        if (requestId != romanizeRequestId || !romanizeInProgress) {
                            return;
                        }
                        romanizeInProgress = false;

                        // The track moved on while the romanization was in flight.
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
                        romanizedLocalDictionary = romaOk ? localDictionary : null;
                        // A reading on the words stands on its own: dropping it whenever the
                        // whole-line side came back empty would blank the line with nothing said.
                        perWordRomaji = perWord;
                        if (!romaOk && !perWord) {
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
            if (onlyMode == OnlyMode.BOTH) {
                return;
            }
            if (onlyMode == OnlyMode.ROMA || Settings.LYRICS_ROMANIZE_ONLY.get()) {
                cancelRomanizeOnly();
                return;
            }
            final boolean sameKeyActive = normalRomanizeActive();
            final boolean otherActive = normalTranslateActive();
            final boolean otherOnly =
                    onlyMode == OnlyMode.TRANS || Settings.LYRICS_TRANSLATE_ONLY.get();
            if (sameKeyActive && !otherActive && !otherOnly) {
                return;
            }

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            if (otherOnly) {
                enterDualMode();
                showLyrics(current);
                if (romanizedLines == null && !perWordRomaji) {
                    requestRomanizeOnly(current);
                }
                return;
            }

            final boolean hadNormalTranslate = normalTranslateActive();
            if (hadNormalTranslate) {
                disableNormalTranslate();
                updateTranslateLabel();
            }

            // In-flight normal romanization is dropped so it cannot apply after ONLY starts;
            // already-fetched lines stay for the ONLY render.
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
            if (hadNormalTranslate) {
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
        // The other side is mid-build for the dual mode, so its data is what this runs alongside.
        if (normalTranslateActive() && !Settings.LYRICS_TRANSLATE_ONLY.get()) {
            disableNormalTranslate();
            updateTranslateLabel();
        }
        if (romanizedLines != null || perWordRomaji) {
            onlyMode = Settings.LYRICS_TRANSLATE_ONLY.get() ? OnlyMode.BOTH : OnlyMode.ROMA;
            applyOnlyModeState();
            updateDualPill();
            showLyrics(current);
            updateRomanizeLabel();
            if (onlyMode == OnlyMode.BOTH && translatedLines == null) {
                requestTranslateOnly(current);
            }
            return;
        }
        cancelNormalRomanize();
        romanizeInProgress = true;
        final int requestId = ++romanizeRequestId;
        setButtonLabel(romanizeView, str("morphe_music_lyrics_romanizing"), true);

        LyricsRomanizer.romanize(track, current, current.providerName(),
                (lines, fromGoogle, fromAI, model, perWord, localDictionary) -> {
                    final boolean asOnly = Settings.LYRICS_ROMANIZE_ONLY.get();
                    if (requestId != romanizeRequestId || !asOnly && !romanizeInProgress) {
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
                    romanizedLocalDictionary = romaOk ? localDictionary : null;
                    perWordRomaji = perWord;
                    if (!romaOk && !perWord) {
                        Utils.showToastShort(str("morphe_music_lyrics_romanize_failed"));
                        fallBackFromDualMode(true);
                        return;
                    }
                    if (!asOnly) {
                        showLyrics(current);
                        if (romaOk) {
                            flashButtonLabel(romanizeView,
                                    str("morphe_music_lyrics_romanize_hide"),
                                    updateRomanizeLabelRunnable, 3000);
                        }
                        return;
                    }
                    onlyMode = Settings.LYRICS_TRANSLATE_ONLY.get() ? OnlyMode.BOTH : OnlyMode.ROMA;
                    applyOnlyModeState();
                    updateDualPill();
                    showLyrics(current);
                    if (onlyMode == OnlyMode.BOTH) {
                        // The other side already flagged itself, so only this one was missing.
                        if (translatedLines == null) {
                            requestTranslateOnly(current);
                        }
                        return;
                    }
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
            final boolean on = onlyMode == OnlyMode.ROMA || onlyMode == OnlyMode.BOTH
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
        try {
            openSearchDialog();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whether the panel should offer to make the lyrics itself.
     *
     * <p>Only where there is genuinely nothing on screen and a service is configured that could
     * make something. Without a key this leaves the panel exactly as it was - hidden, with the
     * app's own lyrics showing through - rather than offering something that cannot run.
     */
    private boolean showTranscribePrompt() {
        if (!QuickLRCClient.isConfigured()) {
            return false;
        }
        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        if (track == null) {
            return false;
        }
        transcribePromptView.setText(str("morphe_music_lyrics_transcribe_prompt"));
        transcribePromptView.setVisibility(VISIBLE);
        progressBar.setVisibility(GONE);
        return true;
    }

    private void hideTranscribePrompt() {
        if (transcribePromptView.getVisibility() != GONE) {
            transcribePromptView.setVisibility(GONE);
        }
    }

    private void showOnlyRefreshButton() {
        if (refreshView == null) {
            return;
        }
        for (int i = 0; i < buttonRow.getChildCount(); i++) {
            View button = buttonRow.getChildAt(i);
            if (button != refreshView && button.getVisibility() == VISIBLE) {
                button.setVisibility(GONE);
                hiddenToolbarButtons.add(button);
            }
        }
        buttonRow.setVisibility(VISIBLE);
    }

    /**
     * Shows only what {@link #showOnlyRefreshButton()} hid: the dual pill hides its own two on its
     * own schedule, so restoring the row wholesale would undo that.
     */
    private void restoreToolbarButtons() {
        for (View button : hiddenToolbarButtons) {
            button.setVisibility(VISIBLE);
        }
        hiddenToolbarButtons.clear();
    }

    // Also the cancel button while a run is going, so one tap has to know whether it starts or
    // stops. The search dialog's button runs through here too, which is what keeps the two
    // affordances from disagreeing about what is in flight.
    private void onTranscribePromptClicked() {
        if (QuickLRCClient.isRunning()) {
            cancelTranscription(true);
            return;
        }
        startTranscription(null);
    }

    private void cancelTranscription(boolean announce) {
        transcribeRequestId++;
        transcribeBusy = false;
        QuickLRCClient.cancel();
        pendingAlignment = null;
        pendingAlignmentTrack = null;
        if (announce) {
            Utils.showToastShort(str("morphe_music_lyrics_transcribe_cancelled"));
        }
        updateTranscribeAffordances();
    }

    @SuppressWarnings("ConstantValue")
    private void startTranscription(@Nullable String lyrics) {
        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        String videoId = VideoInformation.getVideoId();
        if (track == null || videoId == null || videoId.isEmpty()) {
            Utils.showToastShort(str("morphe_music_lyrics_transcribe_local_unsupported"));
            return;
        }
        if (!QuickLRCClient.isConfigured()) {
            return;
        }

        final int requestId = ++transcribeRequestId;
        transcribeBusy = true;
        updateTranscribeAffordances();

        QuickLRCClient.transcribe(videoId, lyrics == null ? "" : lyrics.trim(),
                () -> requestId == transcribeRequestId,
                (result, failure) -> {
                    if (requestId != transcribeRequestId) {
                        return;
                    }
                    transcribeBusy = false;
                    if (failure != null) {
                        if (failure != QuickLRCClient.Failure.CANCELLED) {
                            Utils.showToastShort(str(failure == QuickLRCClient.Failure.OUT_OF_CREDITS
                                    ? "morphe_music_lyrics_transcribe_out_of_credits"
                                    : "morphe_music_lyrics_transcribe_failed"));
                        }
                        updateTranscribeAffordances();
                        return;
                    }
                    if (result == null) {
                        Utils.showToastShort(str("morphe_music_lyrics_transcribe_failed"));
                        updateTranscribeAffordances();
                        return;
                    }
                    onTranscriptionReady(result, track);
                });
    }

    /**
     * A finished alignment waits for the user to take it, so nothing they were reading changes
     * under them because a network call came back. A transcription has no previous lyrics to
     * preserve, so it goes straight in.
     */
    private void onTranscriptionReady(Lyrics result, TrackInfo track) {
        Lyrics current = lyrics;
        if (current != null && !current.isEmpty() && current != Lyrics.NOT_FOUND) {
            pendingAlignment = result;
            pendingAlignmentTrack = track;
        } else {
            LyricsManager.getInstance().publishPreferred(result);
            hideTranscribePrompt();
        }
        updateTranscribeAffordances();
    }

    private void applyPendingAlignment() {
        Lyrics pending = pendingAlignment;
        TrackInfo track = pendingAlignmentTrack;
        pendingAlignment = null;
        pendingAlignmentTrack = null;
        if (pending == null) {
            return;
        }
        TrackInfo current = LyricsManager.getInstance().getCurrentTrack();
        if (track != null && current != null && !track.equals(current)) {
            // The track moved on while the answer was on its way, so it belongs to neither.
            Utils.showToastShort(str("morphe_music_lyrics_transcribe_cancelled"));
            updateTranscribeAffordances();
            return;
        }
        LyricsManager.getInstance().publishPreferred(pending);
        hideTranscribePrompt();
        updateTranscribeAffordances();
    }

    private void updateTranscribeAffordances() {
        final boolean running = transcribeBusy || QuickLRCClient.isRunning();
        if (transcribePromptView.getVisibility() == VISIBLE) {
            transcribePromptView.setText(str(running
                    ? "morphe_music_lyrics_transcribe_working"
                    : "morphe_music_lyrics_transcribe_prompt"));
        }
        // A pending result is offered in the search dialog; taking this tap instead made the
        // button read as dead.
        TextView alignButton = dialogAlignButton;
        if (alignButton != null) {
            alignButton.setText(pendingAlignment != null
                    ? str("morphe_music_lyrics_transcribe_apply_align")
                    : running
                            ? str("morphe_music_lyrics_transcribe_aligning")
                            : str("morphe_music_lyrics_transcribe_align"));
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
        Context context = getContext();
        if (context == null) {
            return;
        }

        AutoCompleteTextView titleInput = LyricsSearchUI.createSearchInput(context,
                str("morphe_music_lyrics_search_title_hint"));
        AutoCompleteTextView artistInput = LyricsSearchUI.createSearchInput(context,
                str("morphe_music_lyrics_search_artist_hint"));

        String rawTitle = manager.getCurrentRawTitle();
        String rawArtist = manager.getCurrentRawArtist();
        String defaultTitle = rawTitle != null && !rawTitle.trim().isEmpty()
                ? rawTitle.trim() : track.title();
        String defaultArtist = rawArtist != null && !rawArtist.trim().isEmpty()
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

        // Sits with the terms rather than in the button row, which this dialog leaves empty: it
        // reuses the same two fields rather than searching on its own. Read at open time, so the
        // button follows the key on disk rather than whatever was there when the class loaded.
        if (QuickLRCClient.isConfigured()) {
            TextView transcribeButton = new TextView(context);
            applyButtonStyle(transcribeButton, null);
            LinearLayout.LayoutParams transcribeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            transcribeParams.topMargin = Dim.dp16;
            content.addView(transcribeButton, transcribeParams);

            transcribeButton.setText(pendingAlignment != null
                    ? str("morphe_music_lyrics_transcribe_apply_align")
                    : QuickLRCClient.isRunning()
                            ? str("morphe_music_lyrics_transcribe_aligning")
                            : str("morphe_music_lyrics_transcribe_align"));
            dialogAlignButton = transcribeButton;

            transcribeButton.setOnClickListener(view -> {
            if (pendingAlignment != null) {
                applyPendingAlignment();
                return;
            }
            if (QuickLRCClient.isRunning()) {
                cancelTranscription(true);
                return;
            }
            Lyrics current = lyrics;
            String text = null;
            if (current != null && !current.isEmpty() && current != Lyrics.NOT_FOUND) {
                StringBuilder joined = createJoined(current);
                text = joined.toString();
            }
            if (text == null || text.trim().isEmpty()) {
                Utils.showToastShort(str("morphe_music_lyrics_transcribe_no_text"));
                text = null;
            }
            startTranscription(text);
            });
        }


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
        // Dropped first so nothing touches a detached view. Only a run still going is cut: a
        // result already back is kept, since reopening the dialog still offers it.
        dialog.setOnDismissListener(d -> {
            dialogAlignButton = null;
            if (transcribeBusy || QuickLRCClient.isRunning()) {
                cancelTranscription(false);
            }
        });
        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();

        // One round per dialog, fetched once it is on screen so the first keystroke never
        // waits for MusicBrainz.
        String titleSuggestion = titleInput.getText().toString();
        String artistSuggestion = artistInput.getText().toString();
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
                    LyricsSearchUI.setSuggestionAdapter(context, titleInput, titles);
                }
                if (!artists.isEmpty()) {
                    LyricsSearchUI.setSuggestionAdapter(context, artistInput, artists);
                }
            });
        });
    }

    @SuppressWarnings("unused")
    @NonNull
    private static StringBuilder createJoined(Lyrics current) {
        StringBuilder joined = new StringBuilder();
        int sentLines = 0;
        int skippedBg = 0;
        for (LyricsLine line : current.lines()) {
            if (line.isBG()) {
                skippedBg++;
                continue;
            }
            String lineText = line.text();
            if (lineText != null && !lineText.trim().isEmpty()) {
                joined.append(lineText).append('\n');
                sentLines++;
            }
        }
        return joined;
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
            View row = lineRows.get(growthAnchorIndex);
            TextView child = lineViews.get(growthAnchorIndex);
            scrollAnchorRowIndex = growthAnchorIndex;
            scrollAnchorBaseRowTop = row.getTop();
            scrollAnchorChildTop = child.getTop() + mainLineOffset(child);
            scrollAnchorScreenY = growthAnchorContainerY - scrollView.getScrollY();
            scrollAnchorBaseContentHeight = linesContainer.getHeight();
            userScrollUntilUptimeMs = Math.max(userScrollUntilUptimeMs,
                    SystemClock.uptimeMillis() + SECONDARY_REVEAL_MILLISECONDS + 100);
        }
        for (RowResize resize : resizes) {
            ViewGroup.LayoutParams lp = resize.row.getLayoutParams();
            lp.height = resize.from;
            resize.row.setLayoutParams(lp);
        }
        boolean[] canceled = {false};
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
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
                ViewGroup.LayoutParams lp = resize.row.getLayoutParams();
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
                        ViewGroup.LayoutParams lp = resize.row.getLayoutParams();
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
        Layout layout = view.getLayout();
        if (layout == null) {
            return 0;
        }
        return LyricsViewSupport.leadingRegionSize(layout, view.transStart, view.transEnd)
                + LyricsViewSupport.leadingRegionSize(layout, view.romaStart, view.romaEnd);
    }

    private void hideSecondaryRegions(boolean trans, Lyrics current) {
        final int generation = buildGeneration.get();
        boolean any = false;
        for (TextView lineView : lineViews) {
            if (!(lineView instanceof LyricsLineView view)) {
                continue;
            }
            if (trans ? view.transStart >= 0 : view.hasRomanizationRegion()) {
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
            if (trans ? view.transStart >= 0 : view.hasRomanizationRegion()) {
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
        View row = lineRows.get(index);
        TextView child = lineViews.get(index);
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
        List<RowResize> resizes = new ArrayList<>();
        for (int i = 0; i < lineViews.size() && i < lineRows.size(); i++) {
            // A shared row is resized once, with its lead line.
            if (i > 0 && lineRows.get(i) == lineRows.get(i - 1)) {
                continue;
            }
            View row = lineRows.get(i);
            final int oldH = row.getHeight();
            if (oldH <= 1 || !(lineViews.get(i) instanceof LyricsLineView view)) {
                continue;
            }
            TextView tv = lineViews.get(i);
            // Syllable readings are drawn by the view under the lyric, outside any text region.
            if (!trans && view.showSyllableRomanization && view.romaStart < 0) {
                final int extra = view.syllableRomanizationExtraHeight();
                final int targetH = oldH - extra;
                if (extra > 1 && targetH > 0) {
                    resizes.add(new RowResize(i, row, tv, oldH, targetH, 0, extra));
                }
                continue;
            }
            final int start = trans ? view.transStart : view.romaStart;
            final int end = trans ? view.transEnd : view.romaEnd;
            if (start < 0 || end <= start) {
                continue;
            }
            Layout layout = tv.getLayout();
            if (layout == null || end > layout.getText().length()) {
                continue;
            }
            final int targetLayoutH = start == 0
                    ? layout.getHeight()
                    - layout.getLineBottom(layout.getLineForOffset(end - 1))
                    : layout.getLineTop(layout.getLineForOffset(start));
            final int targetH = targetLayoutH + tv.getPaddingTop() + tv.getPaddingBottom()
                    + LyricsViewSupport.rowHeightOutsideText(row, tv);
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
        for (int i = 0; i < lineRows.size(); i++) {
            if (i == 0 || lineRows.get(i) != lineRows.get(i - 1)) {
                linesContainer.removeView(lineRows.get(i));
            }
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
                && isSecondaryLineAt(start + b); b++) {
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
        final int index = resolveLeadLineIndex(current.indexForPosition(pos, highlightedIndex));
        updateBgLinesVisibility(pos, current, index);

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
                LyricsViewSupport.fadeTo(lineViews.get(highlightedIndex), INACTIVE_LINE_ALPHA);
                if (!karaokeActive) {
                    lineViews.get(highlightedIndex).setTextColor(LyricsColors.unsungWordColor());
                }
                forEachBgLine(highlightedIndex, current, i -> {
                    LyricsViewSupport.fadeTo(lineViews.get(i), INACTIVE_LINE_ALPHA);
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

        final boolean withinWindow = isClusterStillActive(current, index, pos);
        markActiveLine(index, current, withinWindow);

        LyricsViewSupport.fadeTo(lineViews.get(index), withinWindow ? 1f : INACTIVE_LINE_ALPHA);
        if (!karaokeActive) {
            lineViews.get(index).setTextColor(withinWindow
                    ? LyricsColors.lineTextColor() : LyricsColors.unsungWordColor());
        }
        forEachBgLine(index, current, i -> {
            LyricsViewSupport.fadeTo(lineViews.get(i), 1f);
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
        FrameLayout slot = index < sectionDotSlots.size() ? sectionDotSlots.get(index) : null;
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
        float[] dotAlpha = state.dotAlpha();
        for (int i = 0; i < dotsRow.getChildCount() && i < dotAlpha.length; i++) {
            dotsRow.getChildAt(i).setAlpha(dotAlpha[i]);
        }
    }

    private void applySectionSlotMargin(int lineIndex, int marginPx) {
        if (lineIndex < 0 || lineIndex >= lineRows.size()) {
            return;
        }
        View row = lineRows.get(lineIndex);
        if (!(row instanceof ViewGroup group) || group.getChildCount() == 0) {
            return;
        }
        View slot = group.getChildAt(0);
        if (!(slot instanceof FrameLayout)) {
            return;
        }
        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) slot.getLayoutParams();
        if (params == null || params.topMargin == marginPx) {
            return;
        }
        params.topMargin = marginPx;
        slot.setLayoutParams(params);
    }

    /** Sets a slot's height, asking for a layout only when it actually moved. */
    private void applySectionSlotHeight(FrameLayout slot, int heightPx, int marginPx) {
        ViewGroup.MarginLayoutParams params =
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
    @NonNull
    private static LinearLayout createSectionDots(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setVisibility(GONE);
        final int size = LyricsViewSupport.scaledPixels(context, SECTION_DOT_SIZE_FACTOR);
        final int gap = LyricsViewSupport.scaledPixels(context, SECTION_DOT_GAP_FACTOR);
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
        ViewTreeObserver observer = linesContainer.getViewTreeObserver();
        if (pendingAnchorScroll != null) {
            observer.removeOnPreDrawListener(pendingAnchorScroll);
        }
        final int generation = buildGeneration.get();
        ViewTreeObserver.OnPreDrawListener listener =
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

        final boolean karaokeActive = Settings.LYRICS_WORD_SYNC.get();
        final long pos = LyricsManager.getInstance().getPositionMs();
        final int index = resolveLeadLineIndex(current.indexForPosition(pos, highlightedIndex));
        updateBgLinesVisibility(pos, current, index);
        if (index == highlightedIndex) {
            markActiveLine(index, current, true);
            applyLineOverlay(index);
            scrollAnchorIntoView(Math.max(index, 0), false);
            return;
        }

        if (highlightedIndex >= 0 && highlightedIndex < lineViews.size()) {
            // A line still singing its words keeps its brightness, or this tick's paint lands dimmed.
            final boolean keepFullOpacity = karaokeActive
                    && KaraokeHighlightState.stillLit(wordTimings, highlightedIndex, pos);
            markActiveLine(highlightedIndex, current, false);
            if (!keepFullOpacity) {
                LyricsViewSupport.fadeTo(lineViews.get(highlightedIndex), INACTIVE_LINE_ALPHA);
                if (!karaokeActive) {
                    lineViews.get(highlightedIndex).setTextColor(LyricsColors.unsungWordColor());
                }
                forEachBgLine(highlightedIndex, current, i -> {
                    LyricsViewSupport.fadeTo(lineViews.get(i), INACTIVE_LINE_ALPHA);
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

        LyricsViewSupport.fadeTo(lineViews.get(index), 1f);
        // Word sync keeps the build's unsung color: the dim base the sung words read against.
        if (!karaokeActive) {
            lineViews.get(index).setTextColor(LyricsColors.lineTextColor());
        }
        forEachBgLine(index, current, i -> {
            LyricsViewSupport.fadeTo(lineViews.get(i), 1f);
            if (!karaokeActive) {
                lineViews.get(i).setTextColor(LyricsColors.lineTextColor());
            }
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
                fadeLineAndBg(action.line());
            }
            case FADE -> fadeLineAndBg(action.line());
            case RESET_ACTIVE -> resetLineForWordSyncOff(action.line(), true);
            case RESET_INACTIVE -> resetLineForWordSyncOff(action.line(), false);
        }
    }

    private void fadeLineAndBg(int line) {
        if (line < 0 || line >= lineViews.size() || lyrics == null) {
            return;
        }
        LyricsViewSupport.fadeTo(lineViews.get(line), LyricsPanelView.INACTIVE_LINE_ALPHA);
        forEachBgLine(line, lyrics, i ->
                LyricsViewSupport.fadeTo(lineViews.get(i), LyricsPanelView.INACTIVE_LINE_ALPHA));
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
        for (int i = parentIndex + 1; i < lines.size() && isSecondaryLineAt(i); i++) {
            applyWordColors(i, positionMs, allSung);
        }
    }

    private void resetBgWordColors(int parentIndex) {
        if (lyrics == null) return;
        List<LyricsLine> lines = lyrics.lines();
        for (int i = parentIndex + 1; i < lines.size() && isSecondaryLineAt(i); i++) {
            applyWordColors(i, Long.MIN_VALUE, false);
        }
    }

    private void clearWordHighlight(int lineIndex) {
        applyWordColors(lineIndex, Long.MIN_VALUE, false);
        resetBgWordColors(lineIndex);
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
            // A line with no word timings, such as a background vocal sung in one go, lights whole.
            ((LyricsLineView) lineView).setHighlight(
                    timings, positionMs, allSung || (timings.isEmpty() && positionMs != Long.MIN_VALUE),
                    LyricsColors.unsungWordColor(), origStart);
        }
    }

    /**
     * Where the label sits inside its row: flush to the line's edge, so the text it draws lands
     * {@link #SECTION_TAG_PADDING_HORIZONTAL_FACTOR} of the lyrics text size in, a little further
     * in than the dot row below it starts.
     */
    private static FrameLayout.LayoutParams tagLayoutParams(boolean isRightAligned) {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                (isRightAligned ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
    }

    /** The row sits near the line's edge, unlike the pill whose padding holds its text further in. */
    private static FrameLayout.LayoutParams dotsLayoutParams(@NonNull Context context, boolean isRightAligned) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                (isRightAligned ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
        if (isRightAligned) {
            params.rightMargin = LyricsViewSupport.sectionDotInset(context);
        } else {
            params.leftMargin = LyricsViewSupport.sectionDotInset(context);
        }
        return params;
    }

    /**
     * How the label or slot is placed in its line: wrapped to what it holds, and set off the line
     * above by the same gap whether it is showing a label or making room for the dots.
     */
    private static FrameLayout.LayoutParams slotLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
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
                LyricsViewSupport.sectionTagTextSizeSp(), getResources().getDisplayMetrics());
        Paint metricsPaint = new Paint();
        metricsPaint.setTextSize(textPx);
        Paint.FontMetrics fontMetrics = metricsPaint.getFontMetrics();
        final int textHeight = Math.round(fontMetrics.descent - fontMetrics.ascent);
        return Math.max(0, textHeight + Dim.dp4 * 2);
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
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, LyricsViewSupport.sectionTagTextSizeSp());
        tag.setTextColor(Color.BLACK);
        tag.setSingleLine(true);
        tag.setGravity(Gravity.CENTER);
        tag.setIncludeFontPadding(false);
        tag.setBackground(new PillDrawable());
        tag.setPadding(LyricsViewSupport.sectionTagPaddingHorizontal(context),
                LyricsViewSupport.scaledPixels(context, SECTION_TAG_PADDING_VERTICAL_FACTOR),
                LyricsViewSupport.sectionTagPaddingHorizontal(context),
                LyricsViewSupport.scaledPixels(context, SECTION_TAG_PADDING_VERTICAL_FACTOR));
        tag.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tag.setVisibility(Settings.LYRICS_SHOW_SECTION.get() ? VISIBLE : GONE);
        return tag;
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
                TextView tag = sectionTags.get(i);
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
        // Every button is about to be thrown away, so the pill goes with them: leaving it up
        // would strand it in a row that no longer holds the two it stands in for.
        final boolean dualWasUp = onlyMode == OnlyMode.BOTH;
        if (dualWasUp) {
            exitDualMode(true);
        }
        dualPillActive = false;
        buttonRow.removeAllViews();
        hiddenToolbarButtons.clear();
        createToolbarButtons();
        updateTranslateLabel();
        updateRomanizeLabel();
        updateRefreshLabel();
        if (transcribePromptView.getVisibility() == VISIBLE) {
            showOnlyRefreshButton();
        }
    }

    /**
     * Re-renders the lines for a setting that only changes how the existing text is laid out,
     * such as which side leads. Rebuilding is what picks the new reading up, so without this the
     * change would only show on the next track.
     */
    public void applyLyricLayoutSettings() {
        Lyrics current = lyrics;
        if (current == null || current.isEmpty()) {
            return;
        }
        showLyrics(current);
        refreshDualPillIcons();
    }

    /**
     * Re-orders the pill's icons to lead with whichever side is the main one now. The lines were
     * just rebuilt around the new reading, so the pill has to follow or the two disagree. While
     * the label is up there is nothing to reorder; settleDualPill reads the new setting when it
     * brings the icons back.
     */
    @SuppressWarnings("SizeReplaceableByIsEmpty")
    private void refreshDualPillIcons() {
        TextView pill = dualPillView;
        if (!dualPillActive || pill == null || pill.getText().length() > 0) {
            return;
        }
        renderDualPill(pill, false);
    }

    /** Word sync decides the karaoke state and lays the line out, so the lines need rebuilding. */
    public void applyWordSyncSetting() {
        final boolean enabled = Settings.LYRICS_WORD_SYNC.get();
        final int count = Math.min(lineViews.size(), lyrics == null ? 0 : lyrics.lines().size());
        if (!enabled && lyrics != null) {
            // The tick would spot the change and clear every line, but only on the frame it
            // notices, and the rebuild below replaces the lines before then. Turning it off
            // without that leaves the last word's highlight painted on.
            for (int i = 0; i < count; i++) {
                final boolean isActive = i == highlightedIndex;
                resetLineForWordSyncOff(i, isActive);
                forEachBgLine(i, lyrics, bg -> resetLineForWordSyncOff(bg, isActive));
            }
            karaokeState.resetContent();
        }
        karaokeState.syncEnabled(enabled);
        applyLyricLayoutSettings();
    }

    public void applyTextSize() {
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int count = Math.min(lineViews.size(), lineIsBackground.size());
        for (int i = 0; i < count; i++) {
            final boolean isBackground = Boolean.TRUE.equals(lineIsBackground.get(i));
            lineViews.get(i).setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    LyricsViewSupport.lineTextSizeSp(isBackground, textSize));
        }
        final int tagSize = LyricsViewSupport.sectionTagTextSizeSp();
        for (TextView tag : sectionTags) {
            if (tag != null) {
                tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, tagSize);
                // Tracks the text size too, so a stale inset would not move with the text.
                final int insetH = LyricsViewSupport.sectionTagPaddingHorizontal(tag.getContext());
                final int insetV = LyricsViewSupport.scaledPixels(tag.getContext(),
                        SECTION_TAG_PADDING_VERTICAL_FACTOR);
                tag.setPadding(insetH, insetV, insetH, insetV);
            }
        }
        for (LinearLayout dotsRow : sectionDots) {
            LyricsViewSupport.applySectionDotSize(dotsRow);
            if (dotsRow != null) {
                ViewGroup.LayoutParams params = dotsRow.getLayoutParams();
                if (params instanceof FrameLayout.LayoutParams frameParams) {
                    frameParams.leftMargin = LyricsViewSupport.sectionDotInset(dotsRow.getContext());
                    dotsRow.setLayoutParams(frameParams);
                }
            }
        }
        sectionTagHeightPx = 0;
        for (TextView tag : sectionTags) {
            if (tag == null || tag.getVisibility() != VISIBLE) {
                continue;
            }
            TextView measuredTag = tag;
            measuredTag.post(() -> {
                if (measuredTag.getHeight() > 0) {
                    sectionTagHeightPx = measuredTag.getHeight();
                }
            });
        }
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    public void applyHideInfo() {
        CharSequence info = creditView.getText();
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

    /**
     * Folds the two buttons into a pill of their own for the dual mode. It is a real button
     * rather than one of the two stretched, so it carries both icons itself and leaves no width
     * or margin behind to undo when the mode ends.
     */
    private void mergeButtonsIntoPill() {
        TextView translate = translateView;
        TextView romanize = romanizeView;
        if (translate == null || romanize == null || dualPillActive) {
            return;
        }
        // A gesture cut short can leave either button mid-fade; start from whole.
        translate.setVisibility(VISIBLE);
        translate.setAlpha(1f);
        translate.setScaleX(1f);
        translate.setScaleY(1f);
        romanize.setVisibility(VISIBLE);
        romanize.setAlpha(1f);
        romanize.setScaleX(1f);
        romanize.setScaleY(1f);

        // Claimed before it is on screen so a tap during the cross-fade is read as the pill's.
        TextView pill = createDualPill();
        dualPillView = pill;
        dualPillActive = true;
        // The slot the translation button holds, which is where the pill ends up: the gap of the
        // button it stands in for, or it would sit flush against the copy button.
        final int at = buttonRow.indexOfChild(translate);

        // Both fade together, so the merge reads the same whichever side it was entered from.
        LyricsViewSupport.fadeDualButtonOut(translate);
        LyricsViewSupport.fadeDualButtonOut(romanize);
        handler.postDelayed(() -> {
            if (!dualPillActive) {
                return;
            }
            translate.setVisibility(GONE);
            romanize.setVisibility(GONE);
            LyricsViewSupport.resetDualButton(translate);
            LyricsViewSupport.resetDualButton(romanize);
            // Added only once the two are gone rather than alongside them: a pill sharing the
            // row with the buttons it is replacing shifts sideways the moment they stop taking
            // up space, and no amount of easing on the pill hides that.
            LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            pillParams.setMarginStart(Dim.dp12);
            buttonRow.addView(pill, at < 0 ? buttonRow.getChildCount() : at, pillParams);
            pill.setAlpha(0f);
            pill.setScaleX(0.8f);
            pill.animate().alpha(1f).scaleX(1f)
                    .setDuration(BUTTON_STATE_FADE_MILLISECONDS).start();
            flashButtonLabel(pill, str("morphe_music_lyrics_dual_only"),
                    this::settleDualPill, 3000);
        }, BUTTON_STATE_FADE_MILLISECONDS);
    }

    /** A button icon ready to be set on a view: tinted and sized to match the other buttons. */
    @Nullable
    private Drawable tintedIcon(@Nullable String iconName) {
        if (iconName == null || iconName.isEmpty()) {
            return null;
        }
        Drawable icon = ResourceUtils.getDrawable(iconName);
        if (icon == null) {
            Logger.printDebug(() -> "Missing icon: " + iconName);
            return null;
        }
        icon = icon.mutate();
        icon.setTint(LyricsColors.lineTextColor());
        icon.setBounds(0, 0, Dim.dp24, Dim.dp24);
        return icon;
    }

    /**
     * The pill carries both icons itself rather than borrowing one of the two buttons, so it
     * starts with none: whatever it shows has to come from renderDualPill, which is the single
     * place that decides between the label and the icons.
     */
    private TextView createDualPill() {
        TextView pill = new TextView(getContext());
        applyButtonStyle(pill, null);
        pill.setOnClickListener(view -> exitDualMode(true));
        pill.setOnLongClickListener(view -> {
            exitDualMode(false);
            return true;
        });
        renderDualPill(pill, false);
        return pill;
    }

    /**
     * The pill is either the name of the mode or the two icons it stands for, never both. The
     * main side leads, the way it leads the line the pill stands for, so it goes on the left.
     * Swapping the drawables rather than hiding them keeps the label from ever sharing the pill
     * with an icon that was left behind.
     */
    private void renderDualPill(TextView pill, boolean labelling) {
        if (labelling) {
            pill.setCompoundDrawablesRelative(null, null, null, null);
            pill.setCompoundDrawablePadding(0);
            return;
        }
        if (dualPillTranslateIcon == null) {
            dualPillTranslateIcon = tintedIcon(APP_TRANSLATE_ICON);
        }
        if (dualPillRomanizeIcon == null) {
            dualPillRomanizeIcon = tintedIcon(APP_ROMANIZE_ICON);
        }
        final boolean mainIsRoma = !Settings.LYRICS_SWAP_TRANS_ROMA.get();
        pill.setCompoundDrawablesRelative(
                mainIsRoma ? dualPillRomanizeIcon : dualPillTranslateIcon, null,
                mainIsRoma ? dualPillTranslateIcon : dualPillRomanizeIcon, null);
        pill.setCompoundDrawablePadding(Dim.dp8);
    }

    private void settleDualPill() {
        TextView pill = dualPillView;
        if (!dualPillActive || pill == null) {
            return;
        }
        // The mode is still on, so the pill keeps the active look once the label steps aside.
        setButtonLabel(pill, null, true);
    }

    
    private void restoreButtonsFromPill() {
        TextView translate = translateView;
        TextView romanize = romanizeView;
        TextView pill = dualPillView;
        dualPillActive = false;
        dualPillView = null;
        dualPillTranslateIcon = null;
        dualPillRomanizeIcon = null;
        if (pill == null || translate == null || romanize == null) {
            return;
        }
        if (pill.getParent() == null) {
            // Left before it was ever added, so the buttons are still on screen and mid-fade.
            LyricsViewSupport.resetDualButton(translate);
            LyricsViewSupport.resetDualButton(romanize);
            return;
        }
        // The pill leaves first; both buttons return at once rather than one leading.
        pill.animate().alpha(0f).scaleX(0.8f)
                .setDuration(BUTTON_STATE_FADE_MILLISECONDS)
                .withEndAction(() -> {
                    pill.animate().cancel();
                    buttonRow.removeView(pill);
                    translate.setVisibility(VISIBLE);
                    romanize.setVisibility(VISIBLE);
                    setButtonLabel(translate, null, false);
                    setButtonLabel(romanize, null, false);
                    LyricsViewSupport.fadeDualButtonIn(translate);
                    LyricsViewSupport.fadeDualButtonIn(romanize);
                }).start();
    }

    
    private void updateDualPill() {
        if (onlyMode == OnlyMode.BOTH) {
            if (!dualPillActive) {
                mergeButtonsIntoPill();
            }
        } else if (dualPillActive) {
            restoreButtonsFromPill();
        }
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

        Drawable icon = tintedIcon(iconName);
        if (icon == null) {
            return;
        }
        button.setCompoundDrawablesRelative(icon, null, null, null);
        // No text yet (icon-only default): without padding the icon stays centred.
        button.setCompoundDrawablePadding(0);
    }

    /**
     * Hides or brings back both icons the pill carries. They are hidden rather than dropped so
     * the pair stays intact for when the label clears again.
     */
    @SuppressWarnings("SizeReplaceableByIsEmpty")
    private void applyButtonAppearance(TextView button, boolean active) {
        CharSequence currentText = button.getText();
        final boolean labelling = currentText != null && currentText.length() > 0;
        if (button == dualPillView) {
            // The pill names the mode before it stands for the two, so the icons follow the
            // label in and back out with it. It owns its padding as well.
            renderDualPill(button, labelling);
        } else if (button.getCompoundDrawablesRelative()[0] != null) {
            // Padding is reserved only while a label shows, else it pushes the icon left.
            button.setCompoundDrawablePadding(labelling ? Dim.dp8 : 0);
        }

        LyricsViewSupport.fadeButtonColors(button,
                active ? ACTIVE_BUTTON_BG_COLOR
                        : ResourceUtils.getColor(APP_BUTTON_BACKGROUND_COLOR, 0x1AFFFFFF),
                active ? ThemeUtils.getAppBackgroundColor() : LyricsColors.lineTextColor());
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

    // Background vocals: each sits in its lead line's row and only opens while it is sung.

    /** A lead line and its background vocals stay lit this much longer, unless the next line comes first. */
    private static final long RETIMING_MAX_EXTENSION_MS = 1300L;
    /** Before a silence this long, the line goes out shortly after it ends instead. */
    private static final long LYRICS_GAP_MIN_MS = 5000L;
    private static final long LYRICS_GAP_HOLD_MS = 300L;
    /** A line lights this far ahead of its start, to meet the voice rather than trail it. */
    private static final long HIGHLIGHT_LOOK_AHEAD_MS = 190L;
    /** A background vocal stays open this long after its last word. */
    private static final long BG_LINGER_MS = 250L;
    /** How long a line with no end of its own lasts, at most before the next line and otherwise. */
    private static final long UNENDED_LINE_MAX_MS = 4500L;
    private static final long UNENDED_LAST_LINE_MS = 5000L;
    /** A line sung again this soon after itself is an echo, so a background vocal. */
    private static final long ECHO_MAX_GAP_MS = 3500L;
    /** How far from its lead line a background vocal may start and still belong to it. */
    private static final long BG_MAX_LEAD_MS = 2000L;
    private static final long BG_MAX_LAG_MS = 30000L;
    private static final long BG_MAX_AFTER_LEAD_END_MS = 4000L;
    private static final long BG_MAX_AFTER_LEAD_START_MS = 15000L;

    /**
     * Whether a line is a background vocal of the lead line above it: one the source marks as such,
     * one written whole in brackets, or an echo of the line before it. It has to start close
     * enough to its lead to belong to it.
     */
    private static boolean isSecondaryLyricLine(int index, List<LyricsLine> lines) {
        if (index <= 0 || index >= lines.size()) {
            return false;
        }
        LyricsLine line = lines.get(index);
        boolean candidate = line.isBG() || LyricsBracketSplitter.isBracketedVocal(line.text());
        if (!candidate && !LyricsBracketSplitter.hasTagBrackets(line.text())) {
            LyricsLine prev = lines.get(index - 1);
            final long gap = line.startTimeMs() - prev.startTimeMs();
            if (gap >= 0 && gap <= ECHO_MAX_GAP_MS) {
                String cleanCurr = LRCParser.cleanTextForComparison(line.text());
                candidate = !cleanCurr.isEmpty()
                        && cleanCurr.equals(LRCParser.cleanTextForComparison(prev.text()));
            }
        }
        if (!candidate) {
            return false;
        }

        int parentIdx = index - 1;
        while (parentIdx >= 0) {
            LyricsLine p = lines.get(parentIdx);
            if (!p.isBG() && !LyricsBracketSplitter.isBracketedVocal(p.text())) {
                break;
            }
            parentIdx--;
        }
        if (parentIdx < 0) {
            return false;
        }

        if (line.isBG() && !LyricsBracketSplitter.hasTagBrackets(line.text())) {
            return true;
        }

        LyricsLine parentLine = lines.get(parentIdx);
        if (line.startTimeMs() != LyricsLine.NO_TIME && parentLine.startTimeMs() != LyricsLine.NO_TIME) {
            final long timeDiff = line.startTimeMs() - parentLine.startTimeMs();
            if (timeDiff < -BG_MAX_LEAD_MS || timeDiff > BG_MAX_LAG_MS) {
                return false;
            }
            if (parentLine.endTimeMs() != LyricsLine.NO_TIME
                    && parentLine.endTimeMs() > parentLine.startTimeMs()) {
                return line.startTimeMs() <= Math.max(
                        parentLine.endTimeMs() + BG_MAX_AFTER_LEAD_END_MS,
                        parentLine.startTimeMs() + BG_MAX_AFTER_LEAD_START_MS);
            }
        }
        return true;
    }

    /** Whether the built line at this index is a background vocal in its lead line's row. */
    private boolean isSecondaryLineAt(int index) {
        return index > 0 && index < lineIsBackground.size()
                && Boolean.TRUE.equals(lineIsBackground.get(index));
    }

    /** A background vocal is never highlighted on its own: its lead line is. */
    private int resolveLeadLineIndex(int index) {
        int res = index;
        while (res > 0 && isSecondaryLineAt(res)) {
            res--;
        }
        return res;
    }

    private int findParentLeadIndex(int bgIndex) {
        if (bgIndex < lineViews.size()
                && lineViews.get(bgIndex) instanceof LyricsLineView view
                && view.getParentLineIndex() >= 0) {
            return view.getParentLineIndex();
        }
        return resolveLeadLineIndex(bgIndex);
    }

    /** A background vocal sung ahead of its lead line sits above it. */
    private static boolean isBgBeforeMain(List<LyricsLine> lines, int parentIdx, int bgIdx) {
        if (parentIdx < 0 || parentIdx >= lines.size() || bgIdx < 0 || bgIdx >= lines.size()) {
            return false;
        }
        LyricsLine mainLine = lines.get(parentIdx);
        LyricsLine bgLine = lines.get(bgIdx);
        final long mainStart = !mainLine.words().isEmpty()
                ? mainLine.words().get(0).startMs() : mainLine.startTimeMs();
        final long bgStart = !bgLine.words().isEmpty()
                ? bgLine.words().get(0).startMs() : bgLine.startTimeMs();
        return mainStart >= 0 && bgStart >= 0 && bgStart < mainStart;
    }

    /** When a line is done being sung: its last word, its own end, or a while after it starts. */
    private long computeLineActualEndMs(List<LyricsLine> lines, int idx) {
        LyricsLine line = lines.get(idx);
        if (idx < lineWordSpans.size() && !lineWordSpans.get(idx).isEmpty()) {
            List<LyricsSpanBuilder.WordTiming> spans = lineWordSpans.get(idx);
            final long wordEnd = spans.get(spans.size() - 1).endMs();
            if (wordEnd > line.startTimeMs()) {
                return wordEnd;
            }
        }
        if (line.hasWords()) {
            final long wordEnd = line.words().get(line.words().size() - 1).endMs();
            if (wordEnd > line.startTimeMs()) {
                return wordEnd;
            }
        }
        if (line.endTimeMs() > line.startTimeMs()) {
            return line.endTimeMs();
        }
        for (int j = idx + 1; j < lines.size(); j++) {
            if (!isSecondaryLineAt(j) && lines.get(j).startTimeMs() > line.startTimeMs()) {
                return Math.min(line.startTimeMs() + UNENDED_LINE_MAX_MS, lines.get(j).startTimeMs());
            }
        }
        return line.startTimeMs() + UNENDED_LAST_LINE_MS;
    }

    /** When a lead line and the background vocals after it go out. */
    private long computeAdjustedClusterEndMs(Lyrics current, int leadIdx) {
        List<LyricsLine> lines = current.lines();
        long clusterBaseEnd = computeLineActualEndMs(lines, leadIdx);
        int clusterEndIdx = leadIdx;
        while (clusterEndIdx + 1 < lines.size() && isSecondaryLineAt(clusterEndIdx + 1)) {
            clusterEndIdx++;
            clusterBaseEnd = Math.max(clusterBaseEnd, computeLineActualEndMs(lines, clusterEndIdx));
        }
        for (int j = clusterEndIdx + 1; j < lines.size(); j++) {
            if (!isSecondaryLineAt(j)) {
                final long gap = lines.get(j).startTimeMs() - clusterBaseEnd;
                if (gap >= LYRICS_GAP_MIN_MS) {
                    return clusterBaseEnd + LYRICS_GAP_HOLD_MS;
                }
                return clusterBaseEnd + Math.max(0L, Math.min(RETIMING_MAX_EXTENSION_MS, gap));
            }
        }
        return clusterBaseEnd + RETIMING_MAX_EXTENSION_MS;
    }

    private boolean isClusterStillActive(Lyrics current, int leadIdx, long posMs) {
        if (leadIdx < 0 || leadIdx >= current.lines().size()) {
            return false;
        }
        LyricsLine lead = current.lines().get(leadIdx);
        return posMs >= lead.startTimeMs() - HIGHLIGHT_LOOK_AHEAD_MS
                && posMs <= computeAdjustedClusterEndMs(current, leadIdx);
    }

    /** Open while its lead line is the active one, and while it is sung itself. */
    private boolean shouldBgLineBeVisible(Lyrics current, int activeLeadIdx, int bgIdx, long posMs) {
        if (!current.synced()) {
            return true;
        }
        if (activeLeadIdx >= 0 && findParentLeadIndex(bgIdx) == activeLeadIdx) {
            return true;
        }
        if (Settings.LYRICS_WORD_SYNC.get()
                && bgIdx < lineWordSpans.size()
                && !lineWordSpans.get(bgIdx).isEmpty()) {
            List<LyricsSpanBuilder.WordTiming> bt = lineWordSpans.get(bgIdx);
            if (posMs >= bt.get(0).startMs() - HIGHLIGHT_LOOK_AHEAD_MS
                    && posMs <= bt.get(bt.size() - 1).endMs() + BG_LINGER_MS) {
                return true;
            }
        }
        LyricsLine bgLine = current.lines().get(bgIdx);
        return bgLine.startTimeMs() != LyricsLine.NO_TIME && bgLine.endTimeMs() > bgLine.startTimeMs()
                && posMs >= bgLine.startTimeMs() - HIGHLIGHT_LOOK_AHEAD_MS
                && posMs <= bgLine.endTimeMs() + BG_LINGER_MS;
    }

    private void updateBgLinesVisibility(long posMs, Lyrics current, int activeLeadIdx) {
        final int count = Math.min(lineViews.size(), current.lines().size());
        for (int i = 0; i < count; i++) {
            if (lineViews.get(i) instanceof LyricsLineView view && view.isSecondary()) {
                final boolean visible = shouldBgLineBeVisible(current, activeLeadIdx, i, posMs);
                view.setActiveLine(visible, LyricsColors.secondaryTextColor());
                view.animateSecondaryExpansion(visible, false);
            }
        }
    }
}
