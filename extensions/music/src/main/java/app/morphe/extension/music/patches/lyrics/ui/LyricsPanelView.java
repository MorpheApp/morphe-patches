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
import android.text.method.LinkMovementMethod;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Pair;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.LyricsPresentation;
import app.morphe.extension.music.patches.lyrics.derive.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.derive.LyricsTranslator;
import app.morphe.extension.music.patches.lyrics.derive.ProcessingProviders;
import app.morphe.extension.music.patches.lyrics.derive.QuickLRCClient;
import app.morphe.extension.music.patches.lyrics.model.CreditInfo;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsBracketSplitter;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.ui.GapLineView.GapInterval;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.romanizers.RimeDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.RubyGenerator;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.session.LockScreenLyrics;
import app.morphe.extension.music.patches.lyrics.session.MiniplayerLyrics;
import app.morphe.extension.music.patches.lyrics.storage.LyricsFileSaver;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
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

    private static final float INACTIVE_LINE_ALPHA = LyricsViewSupport.INACTIVE_LINE_ALPHA;
    /** Synced lyrics keep the active line this far down the screen, as a share of its height. */
    private static final float SCROLL_ANCHOR_RATIO = 0.16f;
    /** The scroll engine's lead time, in the place of the time to the next line. */
    private static final long DEFAULT_SCROLL_LOOK_AHEAD_MS = 350L;
    private static final long JUMP_BUTTON_FADE_MS = 200L;

    static final long SECONDARY_REVEAL_MILLISECONDS = 250;
    /** Lets rebuilt rows land in place without gliding while their regions reveal. */
    private static final long REBUILD_SUPPRESS_MS = SECONDARY_REVEAL_MILLISECONDS + 150;

    /** Fade length when the panel appears over the built-in content. */
    private static final long OVERLAY_FADE_DURATION_MILLISECONDS = 150;

    /** How long auto scrolling stays off after the user touches the panel. */
    private static final long MANUAL_SCROLL_PAUSE_MILLISECONDS = 5000;

    private static final long OVERLAY_CACHE_TTL_MS = 300;

    private static final long MIN_TICK_INTERVAL_MS = 50;

    private static final long IDLE_TICK_INTERVAL_MS = 250;

    private static final int SCROLL_INSTANT_THRESHOLD_FACTOR = 2;
    private static final int SCROLL_SMOOTH_THRESHOLD_FACTOR = 4;

    private static final String LYRICS_CONTRIBUTE_CREDIT_KEY =
            "morphe_music_lyrics_contribute_credit";

    private static final float BUTTON_TEXT_SIZE_SP = 14;

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

    private final DynamicBackgroundView dynamicBackgroundView;
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
    private final TextView jumpToCurrentView;

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
    /** The reading shown is the source's own, so no local reading may fill its gaps. */
    private boolean romanizedFromEmbedded;
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
    private int rubyRequestId;
    /** The ruby language last applied to the lyrics, so a change re-runs and a no-op does not. */
    @Nullable
    private String appliedRubyLanguage;
    /** The lyrics as loaded from the source, before ruby enrichment, so re-applying starts clean. */
    @Nullable
    private Lyrics rawLyrics;
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

    enum OnlyMode {NONE, TRANS, ROMA, BOTH}

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
     * Agent name labels such as "Addison Rae", one per line where the lead agent changes, in line
     * order. Uses the same capsule styling as section tags but aligns to the opposite side. Only
     * created when the agent has a display name in {@code agentNames}; never shows raw IDs like v1, v2.
     * Views are {@code null} where the line has no agent label, indexing alongside {@link #lineViews}.
     */
    private final List<TextView> agentTags = new ArrayList<>();

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

    private final LyricsScrollEngine scrollEngine;
    private final List<GapInterval> gapIntervals = new ArrayList<>();
    private final List<GapLineView> gapLineViews = new ArrayList<>();
    private final SparseArray<GapLineView> gapLineViewsByLine = new SparseArray<>();
    private List<LyricsRetiming.RetimedEntry> retimedLyrics = Collections.emptyList();
    private long[] retimedAdjustedEndMax = new long[0];

    private boolean isBrowsing;
    private boolean isProgrammaticScrolling;
    private final int touchSlop;
    private float touchDownX;
    private float touchDownY;
    private boolean isDraggingScroll;
    private int highlightedIndex = -1;
    private int primaryActiveIndex = -1;
    private int previousPrimaryActiveIndex = -1;
    private long lastLivePosMs = 0L;
    private final Set<Integer> activeIndicesSet = new TreeSet<>();
    private final Set<Integer> previousActiveSet = new HashSet<>();
    private final Runnable resumeFollowRunnable = this::onResumeFollow;
    private boolean initialBuildComplete = false;
    private long pendingSeekPosMs = -1L;
    private long pendingSeekUptimeMs = 0L;
    private int pendingSeekMainIndex = -1;
    private boolean wordSyncWasEnabled = true;

    private boolean creditLineActive = false;
    private float creditLookCurAlpha = 0.15f;
    private float creditLookCurScale = 0.93f;
    private float creditLookToAlpha = -1f;
    private float creditLookToScale = -1f;
    @Nullable
    private ValueAnimator creditLookAnimator = null;

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
            // DEFAULT = 60fps, predictable on all displays
            interval = 16;
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
                updateHighlight();
                // The position the highlight just used, which holds a tapped line's time
                // until the seek lands.
                updateWordSync(lastLivePosMs > 0
                        ? lastLivePosMs
                        : LyricsManager.getInstance().getPositionMs());
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

        dynamicBackgroundView = new DynamicBackgroundView(context);
        dynamicBackgroundView.setVisibility(Settings.LYRICS_DYNAMIC_BACKGROUND.get() ? VISIBLE : GONE);
        addView(dynamicBackgroundView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        final int horizontalPadding = Dim.dp24;
        final int verticalPadding = Dim.dp16;

        linesContainer = new LinearLayout(context);
        linesContainer.setOrientation(LinearLayout.VERTICAL);
        linesContainer.setClipChildren(false);
        linesContainer.setClipToPadding(false);
        linesContainer.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        footerView = new LyricsFit.FittingTextView(context);
        LyricsViewSupport.applyFooterStyle(footerView);
        footerView.setMovementMethod(LinkMovementMethod.getInstance());
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
        // Tapping the credit of a re-timed lyric puts the earlier one back.
        creditView.setOnClickListener(v -> LyricsManager.getInstance().revertCurrentPatch());
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

        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        // Rows gliding in from outside the scroll view stay drawn on the way.
        setClipChildren(false);
        setClipToPadding(false);

        scrollView = new ScrollView(context) {
            @Override
            protected float getTopFadingEdgeStrength() {
                return 0.0f;
            }
        };
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setVerticalFadingEdgeEnabled(false);
        scrollView.setFadingEdgeLength(0);
        scrollView.setClipChildren(false);
        scrollView.setClipToPadding(false);
        scrollView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) {
                updateLinesContainerPadding();
            }
        });
        scrollView.addView(linesContainer, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        addView(scrollView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        scrollEngine = new LyricsScrollEngine(scrollView, linesContainer, footerContainer,
                new LyricsScrollEngine.Callback() {
                    @Override
                    public boolean isUserScrolling() {
                        return isBrowsing || isDraggingScroll
                                || SystemClock.uptimeMillis() < userScrollUntilUptimeMs;
                    }

                    @Override
                    public void setProgrammaticScrolling(boolean isProgrammatic) {
                        isProgrammaticScrolling = isProgrammatic;
                    }

                    @Override
                    public void onRecomputeAndScroll(int referenceIndex) {
                        recomputeAndScroll(referenceIndex);
                    }

                    @Override
                    public int computeReflowScrollTarget(@Nullable View targetRow) {
                        if (scrollView.getHeight() <= 0) return -1;
                        if (targetRow instanceof GapLineView gv) {
                            return (gv.getInterval().beforeLineIndex() == 0)
                                    ? 0
                                    : Math.max(0, gv.getTop() + gv.getPendingLayoutDelta() - getScrollAnchorPx());
                        }
                        if (targetRow == footerContainer || creditLineActive) {
                            return computeBlockScrollTargetY(lineRows.size());
                        }
                        int idx = (targetRow != null) ? lineRows.indexOf(targetRow) : -1;
                        if (idx < 0) idx = primaryActiveIndex;
                        return (idx >= 0) ? computeBlockScrollTargetY(idx) : -1;
                    }
                });
        scrollEngine.setSpringScrollEnabled(Settings.LYRICS_SPRING_SCROLL.get());
        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!isProgrammaticScrolling) {
                startBrowsing();
            }
        });

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
        jumpToCurrentView = new TextView(context);
        applyButtonStyle(jumpToCurrentView, "yt_outline_experimental_chevron_down_vd_theme_24");
        jumpToCurrentView.setText(str("morphe_music_lyrics_current"));
        jumpToCurrentView.setCompoundDrawablePadding(Dim.dp(6));
        jumpToCurrentView.setElevation(Dim.dp(8));
        jumpToCurrentView.setOnClickListener(v -> onResumeFollow());
        LayoutParams jumpParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        jumpParams.bottomMargin = Dim.dp(96);
        jumpParams.rightMargin = Dim.dp16;
        addView(jumpToCurrentView, jumpParams);
        jumpToCurrentView.bringToFront();

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

    /** Room below the footer, so the last lines can scroll up to the anchor too. */
    private void updateLinesContainerPadding() {
        final int top = Dim.dp16;
        final int bottom = Dim.dp(32);
        if (linesContainer.getPaddingTop() != top || linesContainer.getPaddingBottom() != bottom) {
            linesContainer.setPadding(Dim.dp24, top, Dim.dp24, bottom);
        }
        final int viewportH = scrollView.getHeight();
        if (viewportH > 0) {
            final int footerBottom = Math.max(Dim.dp(240), viewportH);
            if (footerContainer.getPaddingBottom() != footerBottom) {
                footerContainer.setPadding(0, Dim.dp24, 0, footerBottom);
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h > 0) {
            updateLinesContainerPadding();
        }
    }

    private static boolean isTouchInsideView(View v, MotionEvent event, int extraPadding) {
        if (v.getVisibility() != VISIBLE) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        return x >= (v.getLeft() - extraPadding) && x <= (v.getRight() + extraPadding)
                && y >= (v.getTop() - extraPadding) && y <= (v.getBottom() + extraPadding);
    }

    /** Touches on the jump button are its alone, and never start a scroll or an offset drag. */
    private boolean isTouchOnJumpButton(MotionEvent event) {
        return isTouchInsideView(jumpToCurrentView, event, Dim.dp16);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!isTouchOnJumpButton(event)) {
            trackScrollDrag(event);
        }
        return super.dispatchTouchEvent(event);
    }

    private void trackScrollDrag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                touchDownX = event.getX();
                touchDownY = event.getY();
                isDraggingScroll = false;
                scrollEngine.reset();
            }
            case MotionEvent.ACTION_MOVE -> {
                final float dx = Math.abs(event.getX() - touchDownX);
                final float dy = Math.abs(event.getY() - touchDownY);
                if (dy > touchSlop && dy > dx * 1.2f) {
                    isDraggingScroll = true;
                    startBrowsing();
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDraggingScroll = false;
        }
    }

    /**
     * The user scrolls the lyrics by hand: auto scrolling backs off for a while, and every line
     * shows alike with a button to jump back.
     */
    private void startBrowsing() {
        scrollEngine.reset();
        userScrollUntilUptimeMs = SystemClock.uptimeMillis() + MANUAL_SCROLL_PAUSE_MILLISECONDS;
        lastScrollTarget = -1;
        if (!isBrowsing) {
            isBrowsing = true;
            showJumpToCurrentButton();
            updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1, false);
        }
        handler.removeCallbacks(resumeFollowRunnable);
        handler.postDelayed(resumeFollowRunnable, MANUAL_SCROLL_PAUSE_MILLISECONDS);
    }

    private void handleOffsetGesture(MotionEvent event) {
        offsetGestureDetector.onTouchEvent(event);
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            finishOffsetAdjustment();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (isTouchOnJumpButton(event)) {
            return false;
        }
        handleOffsetGesture(event);
        return isOffsetAdjusting || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isOffsetAdjusting) {
            handleOffsetGesture(event);
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
            toastError(str("morphe_music_lyrics_sb_no_video"));
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
            scrollEngine.attach();
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
            scrollEngine.cleanup();
            LyricsManager.getInstance().removeListener(this);
            handler.removeCallbacksAndMessages(null);
            stopSbRecording();
            LyricsManager.getInstance().persistTemporaryOffsetMs();
            offsetRulerView.setVisibility(GONE);
            if (!LyricsPanelInstaller.isOtherPanelForeground()) {
                restoreHiddenSiblings();
            }
            LyricsPanelInstaller.onPanelDetached(this);
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
                    if (Settings.LYRICS_SPLIT_BACKGROUND.get()) {
                        newLyrics = LyricsBracketSplitter.apply(newLyrics);
                    }
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
            primaryActiveIndex = -1;
            previousPrimaryActiveIndex = -1;
            activeIndicesSet.clear();
            previousActiveSet.clear();
            lastOverlayIndex = -1;
            lastScrollTarget = -1;
            lastLivePosMs = 0L;
            seekPending = true;
            userScrollUntilUptimeMs = 0;
            isBrowsing = false;
            handler.removeCallbacks(resumeFollowRunnable);
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
            // An in-flight callback from the previous track drops out on its id.
            invalidateTranslate();
            invalidateRomanize();
            clearTranslatedData();
            clearRomanizedData();
            // A transcription in flight belongs to the track that just left.
            transcribeRequestId++;
            transcribeBusy = false;
            rubyRequestId++;
            QuickLRCClient.cancel();
            pendingAlignment = null;
            pendingAlignmentTrack = null;
            updateTranscribeAffordances();
            // Dual mode keeps the original underneath, so onlyMode resets while the flags stand.
            onlyMode = OnlyMode.NONE;
            updateFooter();
            updateSessionMainLine();

            switch (state) {
                case LOADING:
                    dynamicBackgroundView.setVideoId(VideoInformation.getVideoId());
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
                        dynamicBackgroundView.setVideoId(VideoInformation.getVideoId());
                        rawLyrics = newLyrics;
                        // Ruby follows the romanization, so readings are only made when one is on.
                        if (Settings.LYRICS_ROMANIZE.get() || Settings.LYRICS_ROMANIZE_ONLY.get()) {
                            applyRuby();
                            newLyrics = lyrics;
                        } else {
                            showLyrics(newLyrics);
                        }
                        setOverlayVisible(true);
                        if (Settings.LYRICS_TRANSLATE_ONLY.get()) {
                            requestTranslateOnly(newLyrics);
                        } else if (Settings.LYRICS_TRANSLATE.get()) {
                            onTranslateClicked();
                        }
                        if (Settings.LYRICS_ROMANIZE_ONLY.get()) {
                            requestRomanizeOnly(newLyrics);
                        } else if (Settings.LYRICS_ROMANIZE.get()
                                && !LyricsRequests.isRomanizationOff()) {
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
        if (left.size() != right.size() || a.synced() != b.synced()
                || !Objects.equals(a.agentNames(), b.agentNames())) {
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
                || agentTags.size() != newLines.size()) {
            return false;
        }

        for (TextView agentTag : agentTags) {
            if (agentTag != null) {
                return false;
            }
        }

        Context context = getContext();
        String previousLeadSongPart = null;
        for (int i = 0; i < newLines.size(); i++) {
            LyricsLine line = newLines.get(i);
            String songPart = line.songPart();
            final boolean opensSection = !line.isBG()
                    && songPart != null && !songPart.isEmpty()
                    && !songPart.equals(previousLeadSongPart);
            if (!line.isBG()) {
                previousLeadSongPart = songPart;
            }

            TextView builtTag = sectionTags.get(i);
            if (opensSection && builtTag != null) {
                builtTag.setText(songPart);
                continue;
            }
            if (opensSection) {
                TextView tag = createSectionTag(context, songPart);
                boolean isRight = i < lineViews.size()
                        && (lineViews.get(i).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END;
                FrameLayout tagRow = new FrameLayout(context);
                tagRow.addView(tag, tagLayoutParams(isRight));
                asRow(lineRows.get(i)).addView(tagRow, 0, slotLayoutParams());
                sectionTags.set(i, tag);
                tagRow.setAlpha(0f);
                tagRow.animate().alpha(1f).setDuration(SECTION_TAG_FADE_IN_MILLIS).start();
            } else if (builtTag != null) {
                asRow(lineRows.get(i)).removeViewAt(0);
                sectionTags.set(i, null);
            }
        }
        lyrics = newLyrics;
        lastBuiltLyrics = newLyrics;
        return true;
    }

    @NonNull
    private static ViewGroup asRow(View row) {
        return (ViewGroup) row;
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
            LyricsPanelInstaller.updateKeepScreenOnInTab(this);
        }

        if (cachedOtherPanelOpen) {
            dismissForOtherPanel();
            return;
        }

        final boolean visible = overlayVisible && cachedLyricsPanelOpen;
        final boolean wasVisible = getVisibility() == VISIBLE;
        setVisibility(visible ? VISIBLE : GONE);

        if (visible && !wasVisible) {
            animate().cancel();
            setAlpha(0f);
            animate().alpha(1f).setDuration(OVERLAY_FADE_DURATION_MILLISECONDS).start();
            onPanelReopened();
        } else if (!visible && wasVisible) {
            lastScrollTarget = -1;
            hideJumpToCurrentButton(true);
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

    void dismissForOtherPanel() {
        restoreHiddenSiblings();
        if (getParent() instanceof ViewGroup parent) {
            parent.removeView(this);
        }
        setVisibility(GONE);
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

    private void onPanelReopened() {
        exitBrowsing();
        seekPending = true;
        long nowPos = LyricsManager.getInstance().getPositionMs();
        lastLivePosMs = nowPos;
        if (lyrics != null && lyrics.synced()) {
            GapInterval activeGi = getActiveGapInterval(nowPos);
            boolean inGapBreak = (activeGi != null && nowPos < activeGi.endMs());
            Set<Integer> activeSet = inGapBreak ? Collections.emptySet() : computeActiveLines(nowPos);
            int active = inGapBreak ? -1 : computePrimaryActiveIndex(activeSet, nowPos);
            if (active >= 0) {
                primaryActiveIndex = active;
                highlightedIndex = active;
                activeIndicesSet.clear();
                activeIndicesSet.addAll(activeSet);
            }
        }
        post(() -> {
            if (lyrics != null && !lineViews.isEmpty() && getVisibility() == VISIBLE) {
                jumpToPosition(nowPos);
            }
        });
    }

    /** Scrolls at once to the line, or the instrumental break, at this position. */
    private void jumpToPosition(long positionMs) {
        Lyrics current = lyrics;
        if (current == null) {
            return;
        }
        lastScrollTarget = -1;
        seekPending = true;
        GapInterval gap = getActiveGapInterval(positionMs);
        if (gap != null && positionMs < gap.endMs()) {
            GapLineView gv = gapLineViewsByLine.get(gap.beforeLineIndex());
            if (gv != null) {
                scrollToGapView(gv, true);
            }
        } else if (primaryActiveIndex >= 0) {
            scrollToActiveLine(primaryActiveIndex, true);
        } else if (current.synced() && !current.lines().isEmpty()) {
            scrollToActiveLine(0, true);
        }
        updateHighlight();
        updateWordSync(positionMs);
    }

    public void applyCurrentButtonSetting() {
        if (!Settings.LYRICS_SHOW_CURRENT_BUTTON.get()) {
            hideJumpToCurrentButton(true);
        } else if (isBrowsing) {
            showJumpToCurrentButton();
        }
    }

    private void showLyrics(Lyrics newLyrics) {
        try {
            buildLyrics(newLyrics);
        } catch (Throwable ex) {
            Logger.printExceptionIfDebug(() -> "Debug: buildLyrics failure", ex);
        }
    }

    /**
     * Whether the new lyrics only change what the built lines show around their text, such as a
     * translation turned on, so the lines can be refilled where they are instead of rebuilt.
     */
    private boolean canUpdateLinesInPlace(@Nullable Lyrics oldLyrics, Lyrics newLyrics) {
        if (!Settings.LYRICS_SPRING_SCROLL.get()
                || oldLyrics == null
                || lineViews.isEmpty()
                || lineRows.isEmpty()
                || lineViews.size() != newLyrics.lines().size()
                || oldLyrics.lines().size() != newLyrics.lines().size()
                || oldLyrics.synced() != newLyrics.synced()
                || onlyMode != displayedOnlyMode
                || wholeFadeNextBuild
                || contentOutPending
                || pendingHideAnchorIndex >= 0) {
            return false;
        }
        if (oldLyrics == newLyrics) {
            return true;
        }
        List<LyricsLine> left = oldLyrics.lines();
        List<LyricsLine> right = newLyrics.lines();
        for (int i = 0; i < left.size(); i++) {
            LyricsLine x = left.get(i);
            LyricsLine y = right.get(i);
            if (x.startTimeMs() != y.startTimeMs()
                    || x.endTimeMs() != y.endTimeMs()
                    || !Objects.equals(x.text(), y.text())
                    || x.isDuet() != y.isDuet()
                    || x.isBG() != y.isBG()) {
                return false;
            }
        }
        return true;
    }

    private void updateLinesInPlace(Lyrics newLyrics) {
        lastBuiltLyrics = newLyrics;
        initialBuildComplete = true;
        LyricsColors.invalidate();
        progressBar.setVisibility(GONE);
        hideTranscribePrompt();
        scrollView.setVisibility(VISIBLE);
        updateLinesContainerPadding();
        isBrowsing = false;
        userScrollUntilUptimeMs = 0L;
        handler.removeCallbacks(resumeFollowRunnable);

        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final Paint baseLinePaint = new Paint();
        baseLinePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize,
                getResources().getDisplayMetrics()));
        LyricsViewSupport.applyLyricsBoldPaint(baseLinePaint);
        baseLinePaint.setElegantTextHeight(true);

        final int generation = buildGeneration.incrementAndGet();
        final OnlyMode onlySnap = onlyMode;
        builtOnlyMode = onlySnap;

        final int lineTotalCount = newLyrics.lines().size();
        final boolean[] isSecFlags = secondaryLineFlags(newLyrics.lines());
        final boolean[] lineRightAligned = rightAlignedLines(newLyrics.lines(), isSecFlags);
        int curParentForAlign = -1;
        for (int i = 0; i < lineTotalCount; i++) {
            if (!isSecFlags[i]) {
                curParentForAlign = i;
            }
            LyricsLine l = newLyrics.lines().get(i);
            if (i < lineViews.size() && lineViews.get(i) instanceof LyricsLineView lv) {
                lv.setIsRightAligned(lineRightAligned[i]);
                lv.setIsCentered(Settings.LYRICS_FORCE_CENTER.get()
                        || (isSecFlags[i] && curParentForAlign >= 0
                            ? newLyrics.lines().get(curParentForAlign).isCentered()
                            : l.isCentered()));
                lv.setGravity((lineRightAligned[i] ? Gravity.END : Gravity.START) | Gravity.TOP);
            }
        }

        currentSourceUrl = newLyrics.sourceUrl();
        updateFooter();
        restoreToolbarButtons();
        buttonRow.setVisibility(VISIBLE);
        updateTranslateLabel();
        updateRomanizeLabel();

        LINE_BUILDER_EXECUTOR.execute(() -> {
            try {
                final int lineCount = newLyrics.lines().size();
                List<List<LyricsSpanBuilder.WordTiming>> allTimings = new ArrayList<>(lineCount);
                for (int i = 0; i < lineCount; i++) {
                    if (generation != buildGeneration.get() || lyrics == null) {
                        return;
                    }
                    // As the full build splits them, or the in-place lines sweep differently.
                    allTimings.add(LyricsSpanBuilder.computeWordTimings(
                            newLyrics.lines(), i, onlySnap != OnlyMode.NONE || Settings.LYRICS_WORD_SYNC.get()));
                }
                handler.post(() -> {
                    if (generation != buildGeneration.get() || lyrics == null) {
                        return;
                    }
                    final boolean only = onlyMode != OnlyMode.NONE;
                    final int count = Math.min(allTimings.size(), lineViews.size());
                    boolean[] freshTrans = new boolean[count];
                    boolean[] freshRoma = new boolean[count];
                    for (int i = 0; i < count; i++) {
                        LyricsLineView prevView = lineViews.get(i) instanceof LyricsLineView pv ? pv : null;
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
                            if (generation != buildGeneration.get() || lyrics == null
                                    || i >= lineWordSpans.size()
                                    || i >= lineViews.size()
                                    || i >= lineOriginalStarts.size()) {
                                return;
                            }
                            TextView tv = lineViews.get(i);
                            if (i < newLyrics.lines().size()) {
                                long savedPosMs = (tv instanceof LyricsLineView lv0) ? lv0.positionMs : Long.MIN_VALUE;
                                boolean savedAllSung = (tv instanceof LyricsLineView lv1) && lv1.allSung;
                                LyricsSpanBuilder.BuildResult result =
                                        LyricsSpanBuilder.buildLineText(newLyrics.lines().get(i),
                                                allTimings.get(i), i,
                                                perWordRomaji, romanizedLines, translatedLines, onlyMode,
                                                baseLinePaint, lineRightAligned[i], romanizedFromEmbedded);
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
                                    lineView.setRubyActive(rubyDrawn());
                                    lineView.setSmallRunHoldsWords(result.smallRunHoldsWords());
                                    lineView.setHighlight(resolvedSpans, savedPosMs,
                                            savedAllSung, LyricsColors.unsungWordColor(),
                                            presentation.originalStart());
                                    if (result.transStart() >= 0) {
                                        if (freshTrans[i]) {
                                            lineView.transReveal = 0f;
                                            lineView.startRegionIn(true);
                                        } else {
                                            lineView.transReveal = 1f;
                                        }
                                    } else {
                                        lineView.transReveal = 1f;
                                    }
                                    if (result.romaStart() >= 0 || result.showSyllableRomanization()) {
                                        if (freshRoma[i]) {
                                            lineView.romaReveal = 0f;
                                            lineView.startRegionIn(false);
                                        } else {
                                            lineView.romaReveal = 1f;
                                        }
                                    } else {
                                        lineView.romaReveal = 1f;
                                    }
                                    lineView.requestLayout();
                                    lineView.invalidate();
                                }
                            }
                        }
                    } finally {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            linesContainer.suppressLayout(false);
                        }
                    }
                    linesContainer.requestLayout();
                    long curPos = LyricsManager.getInstance().getPositionMs();
                    lastLivePosMs = curPos;
                    updateHighlight();
                    updateWordSync(curPos);
                    updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1, false);
                });
            } catch (Exception ex) {
                Logger.printException(() -> "updateLinesInPlace failure", ex);
            }
        });
    }

    @SuppressWarnings({"deprecation", "SizeReplaceableByIsEmpty"})
    private void buildLyrics(Lyrics newLyrics) {
        if (onlyMode != displayedOnlyMode && !contentOutPending && !lineViews.isEmpty()) {
            displayedOnlyMode = onlyMode;
            fadeOutAndRebuild(newLyrics);
            return;
        }
        if (canUpdateLinesInPlace(lastBuiltLyrics, newLyrics)) {
            updateLinesInPlace(newLyrics);
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
        final int prevPrimaryActive = primaryActiveIndex;
        final int prevPrevPrimaryActive = previousPrimaryActiveIndex;
        final Set<Integer> prevActiveSet = sameContent
                ? new TreeSet<>(activeIndicesSet) : Collections.emptySet();
        final Set<Integer> prevPreviousActiveSet = sameContent
                ? new HashSet<>(previousActiveSet) : Collections.emptySet();
        final boolean prevIsBrowsing = sameContent && isBrowsing;
        final long prevUserScrollUntil = sameContent ? userScrollUntilUptimeMs : 0L;
        final int prevScrollTarget = sameContent ? lastScrollTarget : -1;
        lastBuiltLyrics = newLyrics;
        clearLines();
        initialBuildComplete = sameContent || preserveHidePosition;
        if (sameContent || preserveHidePosition) {
            isBrowsing = prevIsBrowsing;
            userScrollUntilUptimeMs = prevUserScrollUntil;
            primaryActiveIndex = prevPrimaryActive;
            previousPrimaryActiveIndex = prevPrevPrimaryActive;
            highlightedIndex = prevHighlighted;
            activeIndicesSet.addAll(prevActiveSet);
            previousActiveSet.addAll(prevPreviousActiveSet);
            lastScrollTarget = prevScrollTarget;
            scrollEngine.suppress(REBUILD_SUPPRESS_MS);
        }
        LyricsColors.invalidate();
        progressBar.setVisibility(GONE);
        hideTranscribePrompt();
        scrollView.setVisibility(VISIBLE);
        updateLinesContainerPadding();

        final boolean contentInBuild =
                onlyMode != OnlyMode.NONE || wholeFadeNextBuild;
        wholeFadeNextBuild = false;

        Context context = getContext();
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int foregroundColor = LyricsColors.lineTextColor();
        final boolean lineGestures = Settings.LYRICS_TAP_TO_SEEK.get();
        final boolean tapToSeek = newLyrics.synced() && lineGestures;
        final boolean isSynced = newLyrics.synced();
        Paint baseLinePaint = new Paint();
        baseLinePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize,
                getResources().getDisplayMetrics()));
        LyricsViewSupport.applyLyricsBoldPaint(baseLinePaint);
        baseLinePaint.setElegantTextHeight(true);

        final int generation = buildGeneration.get();
        OnlyMode onlySnap = onlyMode;
        builtOnlyMode = onlySnap;
        String previousLeadSongPart = null;
        String previousLeadAgentId = null;
        final int lineTotalCount = newLyrics.lines().size();
        final boolean[] isSecFlags = secondaryLineFlags(newLyrics.lines());
        final boolean[] lineRightAligned = rightAlignedLines(newLyrics.lines(), isSecFlags);
        boolean isDuetSong = false;
        for (LyricsLine l : newLyrics.lines()) {
            if (l.isDuet()) {
                isDuetSong = true;
                break;
            }
        }
        final int duetSideInset = Math.max(Dim.dp(24),
                (int) (context.getResources().getDisplayMetrics().widthPixels * 0.09f));

        if (isSynced) {
            gapIntervals.addAll(buildGapIntervals(newLyrics.lines(), isSecFlags, lineRightAligned));
        }

        // The highlight of a line before a break with its own dots does not reach into the break.
        Set<Integer> manualGaps = new HashSet<>();
        for (GapInterval gi : gapIntervals) {
            if (gi.beforeLineIndex() > 0) {
                manualGaps.add(gi.beforeLineIndex() - 1);
            }
        }
        retimedLyrics = LyricsRetiming.retimeLyrics(newLyrics.lines(), manualGaps, isSecFlags);
        retimedAdjustedEndMax = LyricsRetiming.buildAdjustedEndMaxByIndex(retimedLyrics);

        LinearLayout currentParentRow = null;
        int currentParentIdx = -1;
        int curParentForAlign = -1;

        for (int i = 0; i < lineTotalCount; i++) {
            if (!isSecFlags[i]) {
                curParentForAlign = i;
            }
            LyricsLine line = newLyrics.lines().get(i);
            final boolean isSec = isSecFlags[i] && currentParentRow != null;
            final boolean isRightAligned = lineRightAligned[i];

            if (isSynced && !isSec) {
                for (GapInterval gi : gapIntervals) {
                    if (gi.beforeLineIndex() == i) {
                        GapLineView gapView = new GapLineView(context, gi);
                        gapView.setScrollEngine(scrollEngine);
                        gapLineViews.add(gapView);
                        gapLineViewsByLine.put(i, gapView);
                        if (tapToSeek) {
                            final long rawGapTime = gi.startMs();
                            gapView.setOnClickListener(view -> {
                                seekToLyricsTime(rawGapTime, -1);
                                scrollToGapView(gapView, false);
                            });
                        }
                        linesContainer.addView(gapView, linesContainer.indexOfChild(footerContainer),
                                new LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT, 0));
                        break;
                    }
                }
            }

            LyricsLineView prevView = i < prevCount
                    && prevViews.get(i) instanceof LyricsLineView pv ? pv : null;
            final boolean reusePrevious = prevView != null && !contentInBuild;
            List<LyricsSpanBuilder.WordTiming> gapTimings = reusePrevious
                    && i < prevSpans.size()
                    ? prevSpans.get(i) : Collections.emptyList();
            lineWordSpans.add(reusePrevious ? gapTimings : new ArrayList<>());

            LyricsLineView lineView = new LyricsLineView(context);
            lineView.setScrollEngine(scrollEngine);
            lineView.setSecondary(isSec);
            lineView.setIsRightAligned(isRightAligned);
            // A vocal shares its lead line's row and centres with it.
            lineView.setIsCentered(Settings.LYRICS_FORCE_CENTER.get()
                    || (isSec && curParentForAlign >= 0
                        ? newLyrics.lines().get(curParentForAlign).isCentered()
                        : line.isCentered()));
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
                            .computeWordTimings(newLyrics.lines(), i, onlySnap != OnlyMode.NONE || Settings.LYRICS_WORD_SYNC.get());
                    LyricsSpanBuilder.BuildResult raw = LyricsSpanBuilder.buildLineText(line,
                            rawTimings, i, perWordRomaji, romanizedLines, translatedLines, onlyMode,
                            baseLinePaint, isRightAligned, romanizedFromEmbedded);
                    LyricsSpanBuilder.PresentedLine presentation = LyricsSpanBuilder.present(raw,
                            LyricsViewSupport.wordSpansFor(raw, onlySnap != OnlyMode.NONE, rawTimings));
                    result = presentation.display();
                    gapTimings = presentation.words();
                    gapOrigStart = presentation.originalStart();
                    lineWordSpans.set(i, gapTimings);
                } else {
                    result = LyricsSpanBuilder.buildLineText(line, gapTimings, i,
                            perWordRomaji, romanizedLines, translatedLines, onlyMode,
                            baseLinePaint, isRightAligned, romanizedFromEmbedded);
                    gapOrigStart = result.originalStart();
                }
                lineView.setText(result.text());
                lineView.setTranslationBounds(result.transStart(), result.transEnd());
                lineView.setRomanizationBounds(result.romaStart(), result.romaEnd(),
                        result.readingSpans() != null && !result.readingSpans().isEmpty(),
                        result.showSyllableRomanization());
                lineView.setRubyActive(rubyDrawn());
                lineView.setSmallRunHoldsWords(result.smallRunHoldsWords());
            } else if (reusePrevious) {
                lineView.setText(prevView.getText());
                lineView.setTranslationBounds(prevView.transStart, prevView.transEnd);
                lineView.setRomanizationBounds(prevView.romaStart, prevView.romaEnd,
                        prevView.perWordReading, prevView.showSyllableRomanization);
                lineView.setRubyActive(rubyDrawn());
                lineView.setSmallRunHoldsWords(prevView.smallRunHoldsWords);
                lineView.transReveal = prevView.transReveal;
                lineView.romaReveal = prevView.romaReveal;
                gapOrigStart = prevView.originalTextStart;
            } else {
                String formatted = LyricsPresentation.formatText(LyricsSpanBuilder.displayLineText(line));
                String rawText = formatted.isEmpty() ? "♪" : formatted;
                boolean isRtl = ScriptDetector.isLineRtl(rawText);
                SpannableString initialText = new SpannableString(rawText);
                initialText.setSpan(new AlignmentSpan.Standard(LyricsSpanBuilder.resolveAlignment(
                                isRtl, isRightAligned, lineView.isCentered())),
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
                    || (reusePrevious && (prevActiveSet.contains(i)
                        || prevActiveSet.contains(effectiveLeadIdx)
                        || effectiveLeadIdx == prevHighlighted
                        || i == prevHighlighted));
            lineView.setActiveLine(initialActive);
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    LyricsViewSupport.lineTextSizeSp(isSec, textSize));
            lineView.setTextColor(foregroundColor);
            lineView.updateLineLook(initialActive, initialActive ? 0 : 2, 0L, !isBrowsing, isSynced, true);
            lineView.contentReveal = contentInBuild ? 0f : 1f;
            lineView.setPadding(0, isSec ? Dim.dp(2) : Dim.dp(8), 0, isSec ? Dim.dp(2) : Dim.dp(8));
            lineView.setIncludeFontPadding(false);
            lineView.getPaint().setElegantTextHeight(true);
            lineView.setTypeface(Typeface.DEFAULT_BOLD);
            LyricsViewSupport.applyLyricsBoldPaint(lineView.getPaint());
            lineView.setFontVariationSettings(null);
            lineView.setLetterSpacing(LyricsViewSupport.lineLetterSpacing(line.text(), textSize));

            if (newLyrics.synced()) {
                lineView.setTextColor(LyricsColors.unsungWordColor());
            }

            if (tapToSeek) {
                final int lineIndex = i;
                final int mainIndex = isSec ? currentParentIdx : i;
                lineView.setOnClickListener(view -> {
                    seekToLyricsTime(line.startTimeMs(), mainIndex);
                    if (mainIndex >= 0) {
                        previousPrimaryActiveIndex = primaryActiveIndex;
                        primaryActiveIndex = mainIndex;
                        highlightedIndex = mainIndex;
                        activeIndicesSet.clear();
                        activeIndicesSet.add(mainIndex);
                        if (isSec) {
                            activeIndicesSet.add(lineIndex);
                        }
                        updateLinesDepthOfField(activeIndicesSet, mainIndex, -1, false);
                        scrollToActiveLine(mainIndex, false);
                    }
                });
            }

            if (lineGestures) {
                lineView.setOnLongClickListener(v -> {
                    LyricsLineView lv = (LyricsLineView) v;
                    String textToCopy = lv.getCopyTextForTouch(lv.lastTouchX, lv.lastTouchY);
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
                agentTags.add(null);
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

            // A centred line takes neither duet side.
            if (isDuetSong && !line.isCentered()) {
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

            final String agentId = line.agentId();
            final boolean agentChanged = agentId != null && !agentId.isEmpty()
                    && !agentId.equals(previousLeadAgentId);
            if (!line.isBG()) {
                previousLeadAgentId = agentId;
            }
            final Map<String, String> agentNames = newLyrics.agentNames();
            final String agentName = agentChanged && agentNames != null
                    ? agentNames.get(agentId) : null;
            TextView agentTag = createSectionTag(context, agentName);

            if (sectionTag != null || agentTag != null) {
                FrameLayout tagRow = new FrameLayout(context);
                FrameLayout.LayoutParams tagRowParams = slotLayoutParams();
                tagRowParams.topMargin = (Settings.LYRICS_SHOW_SECTION.get() && i > 0) ? Dim.dp24 : 0;
                if (sectionTag != null) {
                    tagRow.addView(sectionTag, tagLayoutParams(isRightAligned));
                }
                if (agentTag != null) {
                    tagRow.addView(agentTag, tagLayoutParams(!isRightAligned));
                }
                lineRow.addView(tagRow, tagRowParams);
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
            agentTags.add(agentTag);
            lineUnsungSpans.add(null);
            lineIsBackground.add(false);
        }

        initialBuildComplete = true;

        LINE_BUILDER_EXECUTOR.execute(() -> {
            try {
                final int lineCount = newLyrics.lines().size();
                List<List<LyricsSpanBuilder.WordTiming>> allTimings = new ArrayList<>(lineCount);
                for (int i = 0; i < lineCount; i++) {
                    if (generation != buildGeneration.get() || lyrics == null) {
                        return;
                    }
                    allTimings.add(LyricsSpanBuilder.computeWordTimings(
                            newLyrics.lines(), i, onlySnap != OnlyMode.NONE || Settings.LYRICS_WORD_SYNC.get()));
                }
                handler.post(() -> {
                    if (generation != buildGeneration.get() || lyrics == null) {
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
                            if (generation != buildGeneration.get() || lyrics == null
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
                                                baseLinePaint, lineRightAligned[i], romanizedFromEmbedded);
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
                                    lineView.setRubyActive(rubyDrawn());
                                    lineView.setSmallRunHoldsWords(result.smallRunHoldsWords());
                                    // Always populate wordTimings and unsungColor on the view immediately
                                    // after setting text so word sync and syllable readings are ready.
                                    lineView.setHighlight(resolvedSpans, lineView.positionMs,
                                            lineView.allSung, LyricsColors.unsungWordColor(),
                                            presentation.originalStart());
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
                            // The real growth is the whole height delta: it also carries what
                            // chooseHeight does to the main line's descent, which the region-only
                            // sizes never see. Sizing `trailing` from the remainder keeps
                            // leading + trailing equal to the measured change, so the scroll
                            // anchor compensates the exact amount the content moved.
                            final int trailing = Math.max(0, newH - oldH - leading);
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
                    initialBuildComplete = true;
                    long curPos = LyricsManager.getInstance().getPositionMs();
                    lastLivePosMs = curPos;
                    if (!sameContent && !preserveHidePosition) {
                        seekPending = true;
                    }
                    handleSeekOrRewind(curPos);
                    updateHighlight();
                    updateWordSync(curPos);
                    updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1, true);

                    if (Settings.LYRICS_SPRING_SCROLL.get() || resizes.isEmpty()) {
                        revealTask.run();
                    } else {
                        startRowHeightAnimation(resizes, true, revealTask);
                    }

                    if (!sameContent && !preserveHidePosition) {
                        // A new song opens at the line being sung, once its rows are laid out.
                        handler.post(() -> {
                            try {
                                if (lyrics != null && !lineViews.isEmpty()) {
                                    exitBrowsing();
                                    long nowPos = LyricsManager.getInstance().getPositionMs();
                                    lastLivePosMs = nowPos;
                                    jumpToPosition(nowPos);
                                }
                            } catch (Exception ex) {
                                Logger.printException(() -> "Initial lyrics scroll failure", ex);
                            } finally {
                                isProgrammaticScrolling = false;
                            }
                        });
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

        List<String> songwriters = newLyrics.songwriters();
        List<String> credits = newLyrics.credits();
        String about = newLyrics.about();
        boolean hasAbout = about != null && !about.isEmpty();
        boolean invitesContribution = newLyrics.synced()
                && QuickLRCClient.PROVIDER_NAME.equals(newLyrics.providerName());
        List<String> infoLines = new ArrayList<>();
        if (songwriters != null && !songwriters.isEmpty()) {
            infoLines.add(str("morphe_music_lyrics_written_by", String.join(", ", songwriters)));
        }
        if (credits != null) {
            infoLines.addAll(credits);
        }
        if (hasAbout) {
            infoLines.add(about);
        }
        if (invitesContribution) {
            infoLines.add(str(LYRICS_CONTRIBUTE_CREDIT_KEY));
        }
        if (!infoLines.isEmpty()) {
            creditView.setText(String.join("\n", infoLines));
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

        animateCreditBlockLook(false, 4, 0L, !isBrowsing, newLyrics.synced(), true);
        if (newLyrics.synced() && !lineViews.isEmpty()) {
            if (sameContent || preserveHidePosition) {
                userScrollUntilUptimeMs = SystemClock.uptimeMillis()
                        + SECONDARY_REVEAL_MILLISECONDS + 100;
                updateHighlight();
                lastScrollTarget = -1;
                final int preserveIndex = preserveHidePosition ? preserveHideIndex : -1;
                final int preserveGeneration = buildGeneration.get();
                linesContainer.getViewTreeObserver().addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                if (preserveGeneration != buildGeneration.get()
                                        || lyrics == null || !lyrics.synced()
                                        || lineViews.isEmpty() || lineRows.isEmpty()) {
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
                                                : getScrollAnchorPx())));
                                scrollEngine.suppress(REBUILD_SUPPRESS_MS);
                                scrollWithoutBrowsing(y);
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
                isProgrammaticScrolling = true;
                scrollView.scrollTo(0, 0);
                scrollView.post(() -> isProgrammaticScrolling = false);
            }
        } else {
            if (!sameContent) {
                isProgrammaticScrolling = true;
                scrollView.scrollTo(0, 0);
                scrollView.post(() -> isProgrammaticScrolling = false);
            }
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
                                scrollWithoutBrowsing(y);
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

            isBrowsing = false;
            userScrollUntilUptimeMs = 0L;
            handler.removeCallbacks(resumeFollowRunnable);

            if (translatedLines != null) {
                cancelNormalTranslate();
                if (Settings.LYRICS_SPRING_SCROLL.get()) {
                    showLyrics(current);
                    updateTranslateLabel();
                } else {
                    hideSecondaryRegions(true, current);
                }
                return;
            }

            Settings.LYRICS_TRANSLATE.save(true);
            Settings.LYRICS_TRANSLATE_ONLY.save(false);
            translateInProgress = true;
            final int requestId = ++translateRequestId;
            setButtonLabel(translateView, str("morphe_music_lyrics_translating"), true);

            LyricsTranslator.translate(track, current, current.providerName(),
                    (lines, fromGoogle, fromAI, model, skipped) -> {
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
                        if (lines == null && !skipped) {
                            toastError(str("morphe_music_lyrics_translate_failed"));
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

            invalidateTranslate();
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
                (lines, fromGoogle, fromAI, model, skipped) -> {
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
                        // Nothing to translate is not a failure.
                        if (!skipped) {
                            toastError(str("morphe_music_lyrics_translate_failed"));
                        }
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

    private void requestNormalTranslate(Lyrics current, TrackInfo track) {
        Settings.LYRICS_TRANSLATE.save(true);
        Settings.LYRICS_TRANSLATE_ONLY.save(false);
        translateInProgress = true;
        final int requestId = ++translateRequestId;
        setButtonLabel(translateView, str("morphe_music_lyrics_translating"), true);

        LyricsTranslator.translate(track, current, current.providerName(),
                (lines, fromGoogle, fromAI, model, skipped) -> {
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
                    if (lines == null && !skipped) {
                        toastError(str("morphe_music_lyrics_translate_failed"));
                    }
                    showLyrics(current);
                    if (translatedLines != null) {
                        flashButtonLabel(translateView,
                                str("morphe_music_lyrics_translate_hide"),
                                updateTranslateLabelRunnable, 3000);
                    }
                });
    }

    private void cancelTranslateOnly() {
        clearTranslateOnlyState();
        rebuildAfterOnlyEnded();
    }

    private void cancelRomanizeOnly() {
        clearRomanizeOnlyState();
        rebuildAfterOnlyEnded();
    }

    /** The only mode has just ended, so the lines have to come back without it. */
    private void rebuildAfterOnlyEnded() {
        Lyrics current = lyrics;
        if (current != null) {
            // The romaji toggle was turned off, so the ruby riding on it has to go too.
            showLyrics(rubyDrawn() ? current : hideRuby(current));
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

    /** Leaves the modes held by a button that is being hidden, so a mode goes with its button. */
    public void releaseOnlyModesForHiddenButtons(boolean translateOff, boolean romanizeOff) {
        final boolean releaseTranslate = translateOff && (onlyMode == OnlyMode.TRANS
                || onlyMode == OnlyMode.BOTH || dualPillActive);
        final boolean releaseRomanize = romanizeOff && (onlyMode == OnlyMode.ROMA
                || onlyMode == OnlyMode.BOTH || dualPillActive);
        if (releaseTranslate) {
            if (onlyMode == OnlyMode.NONE) {
                Settings.LYRICS_TRANSLATE_ONLY.save(false);
                cancelNormalTranslate();
                updateTranslateLabel();
            } else {
                cancelTranslateOnly();
            }
        }
        if (releaseRomanize) {
            if (onlyMode == OnlyMode.NONE) {
                Settings.LYRICS_ROMANIZE_ONLY.save(false);
                cancelNormalRomanize();
                updateRomanizeLabel();
            } else {
                cancelRomanizeOnly();
            }
        }
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
            showLyrics(rubyDrawn() ? current : hideRuby(current));
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
            showLyrics(keepData ? current : hideRuby(current));
        }
        updateTranslateLabel();
        updateRomanizeLabel();
        updateFooter();
    }

    private void disableNormalTranslate() {
        cancelNormalTranslate();
        setButtonLabel(translateView, null, false);
    }

    private void disableNormalRomanize() {
        cancelNormalRomanize();
        setButtonLabel(romanizeView, null, false);
    }

    /** The id bump is what drops an in-flight answer: its callback only lands on the current id. */
    private void invalidateTranslate() {
        translateRequestId++;
        translateInProgress = false;
    }

    private void invalidateRomanize() {
        romanizeRequestId++;
        romanizeInProgress = false;
    }

    /** Drops the kept translation, so its old text can never be drawn again. */
    private void clearTranslatedData() {
        translatedLines = null;
        translatedFromGoogle = false;
        translatedFromAI = false;
        translatedAiModel = null;
    }

    private void clearRomanizedData() {
        romanizedLines = null;
        perWordRomaji = false;
        romanizedFromGoogle = false;
        romanizedFromAI = false;
        romanizedFromEmbedded = false;
        romanizedAiModel = null;
        romanizedLocalDictionary = null;
        appliedRubyLanguage = null;
    }

    /** Also turns the setting off, so the panel does not ask for the translation again. */
    private void cancelNormalTranslate() {
        invalidateTranslate();
        Settings.LYRICS_TRANSLATE.save(false);
        clearTranslatedData();
        updateFooter();
    }

    private void cancelNormalRomanize() {
        invalidateRomanize();
        Settings.LYRICS_ROMANIZE.save(false);
        clearRomanizedData();
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
                romanizedFromGoogle, romanizedFromAI, romanizedFromEmbedded, romanizedAiModel, onlyMode,
                romanizedLocalDictionary,
                () -> onTargetLanguageChanged(true),
                () -> onTargetLanguageChanged(false),
                () -> onSourceClicked()));
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

    /** Ruby follows the romanization: it is only drawn while a reading is being shown. */
    private boolean rubyDrawn() {
        return normalRomanizeActive();
    }

    /** Takes the readings off the words, for a romanization that was just turned off. */
    private Lyrics hideRuby(Lyrics current) {
        rubyRequestId++;
        final Lyrics stripped = RubyGenerator.stripRuby(current);
        lyrics = stripped;
        return stripped;
    }

    /** Whether any word in the lyrics carries a ruby reading. */
    private static boolean hasRubyInLyrics(Lyrics lyrics) {
        for (LyricsLine line : lyrics.lines()) {
            for (Word word : line.words()) {
                if (word.ruby() != null && !word.ruby().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void onTargetLanguageChanged(boolean translation) {
        final Lyrics current = lyrics;
        final TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        if (current == null || track == null) {
            updateFooter();
            return;
        }
        try {
            if (translation) {
                if (translatedLines == null && !translateInProgress) {
                    updateFooter();
                    return;
                }
                // A fresh request, not a toggle: the shown translation is the old language.
                invalidateTranslate();
                clearTranslatedData();
                if (onlyMode != OnlyMode.NONE) {
                    requestTranslateOnly(current);
                } else {
                    requestNormalTranslate(current, track);
                }
            } else {
                // Keep the Rime row in step with the language, so the provider list needs no trip
                // to the settings.
                setRimeProviderEnabled(RimeDictStore.isActive());
                if (romanizedLines == null && !perWordRomaji && !romanizeInProgress) {
                    // Romaji off → ruby off; re-applied when romaji turns on
                    if (appliedRubyLanguage != null) {
                        appliedRubyLanguage = null;
                        final Lyrics currentLyrics = lyrics;
                        if (currentLyrics != null) {
                            showLyrics(hideRuby(currentLyrics));
                        }
                    }
                    updateFooter();
                    return;
                }
                invalidateRomanize();
                clearRomanizedData();
                // Ruby follows the romanization, so apply it again for the new language before
                // the reading flow reads it.
                applyRuby();
                final Lyrics updated = lyrics;
                if (onlyMode != OnlyMode.NONE) {
                    requestRomanizeOnly(updated);
                } else {
                    requestNormalRomanize(updated, track);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onTargetLanguageChanged failure", ex);
            updateFooter();
        }
    }

    /** Saves only when the flag actually changes, so an untouched list is never rewritten. */
    private static void setRimeProviderEnabled(boolean enabled) {
        final String stored = Settings.LYRICS_ROMANIZATION_PROVIDERS.get();
        final String updated = ProcessingProviders.withProviderEnabled(
                stored, ProcessingProviders.ROMANIZATION_ORDER, ProcessingProviders.RIME, enabled);
        if (!Objects.equals(updated, stored)) {
            Settings.LYRICS_ROMANIZATION_PROVIDERS.save(updated);
        }
    }

    private void applyOnlyModeState() {
        captureOnlyModeScrollAnchor();
        previousActiveSet.clear();
        clearActiveLineState();
        updateSessionMainLine();
    }

    private void updateSessionMainLine() {
        LyricsPresentation.setMainLineOnlyState(onlyMode == OnlyMode.TRANS || onlyMode == OnlyMode.BOTH,
                onlyMode == OnlyMode.ROMA || onlyMode == OnlyMode.BOTH,
                translatedLines, romanizedLines);
        MiniplayerLyrics.onSettingsChanged();
        LockScreenLyrics.onSettingsChanged();
    }

    private void clearActiveLineState() {
        for (TextView lineView : lineViews) {
            if (lineView instanceof LyricsLineView view) {
                view.setActiveLine(false);
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

            final CreditInfo creditInfo = new CreditInfo(
                    creditOf(translatedFromGoogle, translatedFromAI, translatedAiModel, null),
                    creditOf(romanizedFromGoogle, romanizedFromAI, romanizedAiModel,
                            romanizedLocalDictionary));
            // Read here, so a main thread change cannot swap the data mid-save.
            final List<String> savedTranslatedLines = translatedLines;
            final List<LyricsLine> savedRomanizedLines = romanizedLines;
            final String savedTransLang = LyricsRequests.translationLanguage();
            final String savedRomaLang = LyricsRequests.romanizationLanguage();

            Utils.runOnBackgroundThread(() -> {
                try {
                    String savedPath = LyricsFileSaver.save(appContext, track, current, creditInfo,
                            savedTranslatedLines, savedRomanizedLines,
                            savedTransLang, savedRomaLang);
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

    /** Who a saved file credits for a translation or romanization, null when it came with the lyrics. */
    @Nullable
    private static String creditOf(boolean fromGoogle, boolean fromAI, @Nullable String aiModel,
                                   @Nullable String localDictionary) {
        if (fromGoogle) {
            return "Google";
        }
        if (fromAI) {
            return "AI:" + (aiModel != null ? aiModel : "");
        }
        return localDictionary != null && !localDictionary.isEmpty()
                ? "Local:" + localDictionary : null;
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
                showLyrics(hideRuby(current));
                setButtonLabel(romanizeView, null, false);
                return;
            }

            isBrowsing = false;
            userScrollUntilUptimeMs = 0L;
            handler.removeCallbacks(resumeFollowRunnable);

            // With the romanization turned off the button only shows and hides the ruby.
            if (romanizedLines != null || perWordRomaji
                    || (LyricsRequests.isRomanizationOff() && Settings.LYRICS_ROMANIZE.get())) {
                cancelNormalRomanize();
                if (Settings.LYRICS_SPRING_SCROLL.get()) {
                    showLyrics(hideRuby(current));
                    updateRomanizeLabel();
                } else {
                    hideSecondaryRegions(false, hideRuby(current));
                }
                return;
            }

            // Ensure ruby is applied when romaji is turned on
            if (appliedRubyLanguage == null) {
                applyRuby();
                current = lyrics;
            }

            final Lyrics target = current;
            Settings.LYRICS_ROMANIZE.save(true);
            Settings.LYRICS_ROMANIZE_ONLY.save(false);
            romanizeInProgress = true;
            final int requestId = ++romanizeRequestId;
            setButtonLabel(romanizeView, str("morphe_music_lyrics_romanizing"), true);

            LyricsRomanizer.romanize(track, target, target.providerName(),
                    (lines, fromGoogle, fromAI, model, perWord, localDictionary, fromEmbedded, skipped) -> {
                        if (requestId != romanizeRequestId || !romanizeInProgress) {
                            return;
                        }
                        romanizeInProgress = false;

                        // The track moved on while the romanization was in flight.
                        if (lyrics != target) {
                            return;
                        }

                        final boolean romaOk = LyricsMerge.hasText(lines);
                        romanizedLines = romaOk ? lines : null;
                        romanizedFromGoogle = romaOk && fromGoogle;
                        romanizedFromAI = romaOk && fromAI;
                        romanizedFromEmbedded = romaOk && fromEmbedded;
                        if (romanizedFromAI && model != null) {
                            romanizedAiModel = model;
                        }
                        romanizedLocalDictionary = romaOk ? localDictionary : null;
                        // A reading on the words stands on its own: dropping it whenever the
                        // whole-line side came back empty would blank the line with nothing said.
                        perWordRomaji = perWord;
                        if (!romaOk && !perWord && !skipped) {
                            toastError(str("morphe_music_lyrics_romanize_failed"));
                        }
                        if (appliedRubyLanguage == null) {
                            // Ruby follows the romanization, so make sure it is on for this reading.
                            applyRuby();
                        } else {
                            showLyrics(target);
                        }
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
            invalidateRomanize();
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
                (lines, fromGoogle, fromAI, model, perWord, localDictionary, fromEmbedded, skipped) -> {
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
                    romanizedFromEmbedded = romaOk && fromEmbedded;
                    if (romanizedFromAI && model != null) {
                        romanizedAiModel = model;
                    }
                    romanizedLocalDictionary = romaOk ? localDictionary : null;
                    perWordRomaji = perWord;
                    if (!romaOk && !perWord) {
                        // Nothing to romanize is not a failure.
                        if (!skipped) {
                            toastError(str("morphe_music_lyrics_romanize_failed"));
                        }
                        fallBackFromDualMode(true);
                        return;
                    }
                    if (!asOnly) {
                        if (appliedRubyLanguage == null) {
                            // Ruby follows the romanization, so make sure it is on for this reading.
                            applyRuby();
                        } else {
                            showLyrics(current);
                        }
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
                    if (appliedRubyLanguage == null) {
                        // Ruby follows the romanization, so make sure it is on for this reading.
                        applyRuby();
                    } else {
                        showLyrics(current);
                    }
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

    private void requestNormalRomanize(Lyrics current, TrackInfo track) {
        Settings.LYRICS_ROMANIZE.save(true);
        Settings.LYRICS_ROMANIZE_ONLY.save(false);
        romanizeInProgress = true;
        final int requestId = ++romanizeRequestId;
        setButtonLabel(romanizeView, str("morphe_music_lyrics_romanizing"), true);

        LyricsRomanizer.romanize(track, current, current.providerName(),
                (lines, fromGoogle, fromAI, model, perWord, localDictionary, fromEmbedded, skipped) -> {
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
                    romanizedFromEmbedded = romaOk && fromEmbedded;
                    if (romanizedFromAI && model != null) {
                        romanizedAiModel = model;
                    }
                    romanizedLocalDictionary = romaOk ? localDictionary : null;
                    // A reading on the words stands on its own: dropping it whenever the
                    // whole-line side came back empty would blank the line with nothing said.
                    perWordRomaji = perWord;
                    if (!romaOk && !perWord && !skipped) {
                        toastError(str("morphe_music_lyrics_romanize_failed"));
                    }
                    if (appliedRubyLanguage == null) {
                        // Ruby follows the romanization, so make sure it is on for this reading.
                        applyRuby();
                    } else {
                        showLyrics(current);
                    }
                    if (romaOk) {
                        flashButtonLabel(romanizeView,
                                str("morphe_music_lyrics_romanize_hide"),
                                updateRomanizeLabelRunnable, 3000);
                    }
                });
    }

    private void updateRomanizeLabel() {
        if (romanizeView != null) {
            final boolean on = onlyMode == OnlyMode.ROMA || onlyMode == OnlyMode.BOTH
                    || (onlyMode == OnlyMode.NONE && (romanizedLines != null || perWordRomaji))
                    || (onlyMode == OnlyMode.TRANS && Settings.LYRICS_ROMANIZE.get());
            setButtonLabel(romanizeView, null, on);
        }
    }

    /**
     * Shows the readings that are known right away, then asks the AI provider, if one is on,
     * for the rest.
     */
    private void applyRuby() {
        if (rawLyrics == null) {
            return;
        }
        final String rubyLanguage = Settings.LYRICS_RUBY_LANGUAGE.get();
        final Lyrics local = RubyGenerator.applyLocal(rawLyrics);
        lyrics = local;
        // Only mark applied if readings added; null so ruby re-applies when dicts load
        appliedRubyLanguage = hasRubyInLyrics(local) ? rubyLanguage : null;
        showLyrics(local);

        final int requestId = ++rubyRequestId;
        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        if (track == null || !RubyGenerator.usesAi(local)) {
            return;
        }
        RubyGenerator.requestAi(track, local, generated -> {
            if (requestId != rubyRequestId) {
                return;
            }
            if (generated != local) {
                lyrics = generated;
                showLyrics(generated);
            }
        });
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
                            toastError(str(failure == QuickLRCClient.Failure.OUT_OF_CREDITS
                                    ? "morphe_music_lyrics_transcribe_out_of_credits"
                                    : "morphe_music_lyrics_transcribe_failed"));
                        }
                        updateTranscribeAffordances();
                        return;
                    }
                    if (result == null) {
                        toastError(str("morphe_music_lyrics_transcribe_failed"));
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
            toastError(str("morphe_music_lyrics_search_no_providers"));
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
                () -> {
                },
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

    /** A failure the app hit on its own: it still reaches the log, the toast follows the debug switch. */
    private static void toastError(String message) {
        if (BaseSettings.DEBUG_TOAST_ON_ERROR.get()) {
            Utils.showToastShort(message);
        }
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
                    scrollEngine.suppress(REBUILD_SUPPRESS_MS);
                    scrollWithoutBrowsing(target);
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
                        scrollEngine.suppress(REBUILD_SUPPRESS_MS);
                        scrollWithoutBrowsing(target);
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
            // A row whose region starts lower keeps the rows above it, so it shifts instead.
            final int freedLayoutH = start == 0
                    ? LyricsViewSupport.leadingRegionSize(layout, start, end)
                    : LyricsViewSupport.trailingRegionSize(layout, start, end);
            final int targetLayoutH = layout.getHeight() - freedLayoutH;
            final int targetH = targetLayoutH + tv.getPaddingTop() + tv.getPaddingBottom()
                    + LyricsViewSupport.rowHeightOutsideText(row, tv);
            if (freedLayoutH <= 0 || targetH <= 0 || targetH >= oldH - 1) {
                continue;
            }
            final int leading = start == 0 ? oldH - targetH : 0;
            final int trailing = start == 0 ? 0 : oldH - targetH;
            resizes.add(new RowResize(i, row, tv, oldH, targetH, leading, trailing));
        }
        if (!resizes.isEmpty()) {
            startRowHeightAnimation(resizes, false, onEnd);
        } else if (onEnd != null) {
            onEnd.run();
        }
    }

    /** {@return the height of the row's lead line, for a row whose own height is not laid out yet} */
    private static int getPrimaryChildHeight(View row) {
        if (row instanceof ViewGroup vg && vg.getChildCount() > 0) {
            for (int c = 0; c < vg.getChildCount(); c++) {
                View child = vg.getChildAt(c);
                if (child instanceof LyricsLineView llv && llv.isSecondary()) {
                    continue;
                }
                if (child.getVisibility() != View.GONE && child.getHeight() > 0) {
                    return row.getPaddingTop() + child.getHeight() + row.getPaddingBottom();
                }
            }
        }
        return row.getHeight();
    }

    /** {@return where on screen the line being sung is kept} */
    private int getScrollAnchorPx() {
        if (lyrics != null && lyrics.synced() && scrollView.getHeight() > 0) {
            return Math.max(Dim.dp(36), Math.round(scrollView.getHeight() * SCROLL_ANCHOR_RATIO));
        }
        return linesContainer.getPaddingTop() > 0 ? linesContainer.getPaddingTop() : Dim.dp(48);
    }

    /**
     * {@return how much the gaps and background vocals up to this line are about to grow or
     * shrink, which the layout has not applied yet}
     */
    private int pendingGapDeltaBefore(int lineIndex) {
        int delta = 0;
        for (GapLineView gv : gapLineViews) {
            if (gv.getInterval().beforeLineIndex() <= lineIndex) {
                delta += gv.getPendingLayoutDelta();
            }
        }
        for (TextView tv : lineViews) {
            if (tv instanceof LyricsLineView slv && slv.isSecondary()) {
                int pIdx = slv.getParentLineIndex();
                if (pIdx >= 0 && pIdx < lineIndex) {
                    delta += slv.getPendingLayoutDelta();
                }
            }
        }
        return delta;
    }

    /**
     * {@return the first or last lead line of the run of active lines around this one, such as
     * lines sung together by several singers}
     */
    private int activeGroupEdge(int targetIndex, boolean forward) {
        if (targetIndex < 0 || targetIndex >= lineRows.size()
                || activeIndicesSet.size() <= 1 || !activeIndicesSet.contains(targetIndex)) {
            return targetIndex;
        }
        final int step = forward ? 1 : -1;
        int edge = targetIndex;
        for (int scan = targetIndex + step; scan >= 0 && scan < lineRows.size(); scan += step) {
            if (isSecondaryLineAt(scan)) {
                continue;
            }
            if (!activeIndicesSet.contains(scan)) {
                break;
            }
            edge = scan;
        }
        return edge;
    }

    private int computeActiveGroupEndIndex(int targetIndex) {
        return activeGroupEdge(targetIndex, true);
    }

    /**
     * {@return the scroll position that puts this line, or the whole run of active lines it is
     * in, at the anchor, or -1 when it is not laid out yet}
     *
     * @param targetIndex A line, or the line count for the footer.
     */
    private int computeBlockScrollTargetY(int targetIndex) {
        if (scrollView.getHeight() <= 0 || targetIndex < 0 || targetIndex > lineRows.size()) {
            return -1;
        }
        final int viewportH = scrollView.getHeight();
        final int anchorPx = getScrollAnchorPx();

        if (targetIndex == lineRows.size()) {
            if (footerContainer.getVisibility() == View.GONE || footerContainer.getHeight() <= 0) {
                return -1;
            }
            return Math.max(0, footerContainer.getTop() + pendingGapDeltaBefore(Integer.MAX_VALUE) - anchorPx);
        }

        View targetRow = lineRows.get(targetIndex);
        if (targetIndex > 0 && (targetRow.getHeight() <= 0
                || (targetRow.getTop() <= 0 && linesContainer.getHeight() <= 0))) {
            return -1;
        }

        final int runStart = activeGroupEdge(targetIndex, false);
        final int runEnd = activeGroupEdge(targetIndex, true);
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        View prevRow = null;
        for (int i = runStart; i <= runEnd; i++) {
            View row = lineRows.get(i);
            // A background vocal shares its lead line's row.
            if (row == prevRow || row.getVisibility() == View.GONE) continue;
            prevRow = row;
            final int rowTop = row.getTop();
            top = Math.min(top, rowTop);
            bottom = Math.max(bottom, rowTop + (row.getHeight() > 0 ? row.getHeight() : getPrimaryChildHeight(row)));
        }
        if (top == Integer.MAX_VALUE) {
            return Math.max(0, targetRow.getTop() - anchorPx);
        }

        // A run taller than the screen shows its end, where the singing is.
        int targetTop = top;
        final int availableH = viewportH - anchorPx - Dim.dp(80);
        if (bottom - top > availableH && availableH > 0) {
            targetTop = bottom - availableH;
        }
        return Math.max(0, targetTop - anchorPx);
    }

    /** {@return the row a scroll comes from: the line that was sung before, or the fallback} */
    private int scrollReferenceIndex(int fallbackIndex) {
        return previousPrimaryActiveIndex >= 0 ? previousPrimaryActiveIndex : fallbackIndex;
    }

    @Nullable
    private View lineRowAt(int index) {
        return index >= 0 && index < lineRows.size() ? lineRows.get(index) : null;
    }

    private void recomputeAndScroll(int referenceIndex) {
        if (scrollView.getHeight() <= 0) return;
        if (creditLineActive || referenceIndex == lineRows.size()) {
            final int clamped = computeBlockScrollTargetY(lineRows.size());
            if (clamped < 0) return;
            lastScrollTarget = clamped;
            final int refIdx = scrollReferenceIndex(Math.max(0, lineRows.size() - 1));
            scrollEngine.scrollTo(clamped, footerContainer, lineRows.size(), lineRowAt(refIdx), refIdx,
                    DEFAULT_SCROLL_LOOK_AHEAD_MS, false);
            return;
        }
        final int targetIdx = referenceIndex < 0 ? primaryActiveIndex : referenceIndex;
        if (targetIdx < 0 || targetIdx >= lineRows.size()) {
            return;
        }
        final int clamped = computeBlockScrollTargetY(targetIdx);
        if (clamped < 0) return;
        lastScrollTarget = clamped;
        scrollToRow(clamped, targetIdx, DEFAULT_SCROLL_LOOK_AHEAD_MS, DEFAULT_SCROLL_LOOK_AHEAD_MS);
    }

    /** Scrolls to a line together with the rest of the active run it starts. */
    private void scrollToRow(int targetY, int index, long lookAheadMs, long timeGapMs) {
        final View targetView = lineRows.get(index);
        final int endIdx = computeActiveGroupEndIndex(index);
        final View endView = lineRowAt(endIdx);
        final int refIdx = scrollReferenceIndex(index);
        final View refView = lineRowAt(refIdx);
        scrollEngine.scrollTo(targetY, targetView, index, endView != null ? endView : targetView, endIdx,
                refView != null ? refView : targetView, refIdx, lookAheadMs, timeGapMs, false);
    }

    private void showJumpToCurrentButton() {
        if (!Settings.LYRICS_SHOW_CURRENT_BUTTON.get() || lyrics == null || !lyrics.synced()) return;
        jumpToCurrentView.bringToFront();
        jumpToCurrentView.animate().cancel();
        if (jumpToCurrentView.getVisibility() == VISIBLE && jumpToCurrentView.getAlpha() == 1f) return;
        jumpToCurrentView.setAlpha(0f);
        jumpToCurrentView.setVisibility(VISIBLE);
        jumpToCurrentView.animate().alpha(1f).setDuration(JUMP_BUTTON_FADE_MS).start();
    }

    private void hideJumpToCurrentButton(boolean instant) {
        jumpToCurrentView.animate().cancel();
        if (instant) {
            jumpToCurrentView.setAlpha(0f);
            jumpToCurrentView.setVisibility(GONE);
        } else if (jumpToCurrentView.getVisibility() == VISIBLE) {
            jumpToCurrentView.animate().alpha(0f).setDuration(JUMP_BUTTON_FADE_MS)
                    .withEndAction(() -> jumpToCurrentView.setVisibility(GONE)).start();
        }
    }

    /**
     * Hands the scrolling back to the lyrics. A fling still running scrolls on, and so starts
     * browsing again.
     */
    private void exitBrowsing() {
        scrollEngine.reset();
        isBrowsing = false;
        userScrollUntilUptimeMs = 0;
        isDraggingScroll = false;
        lastScrollTarget = -1;
        handler.removeCallbacks(resumeFollowRunnable);
        hideJumpToCurrentButton(true);
    }

    /** Leaves manual scrolling and goes back to the line, or the instrumental break, being sung. */
    private void onResumeFollow() {
        exitBrowsing();

        final Lyrics current = lyrics;
        if (current == null || !current.synced()) {
            if (highlightedIndex >= 0) {
                updateLinesDepthOfField(activeIndicesSet, highlightedIndex, -1, false);
                scrollToActiveLine(highlightedIndex, false);
            }
            return;
        }
        final long currentPos = LyricsManager.getInstance().getPositionMs();
        final GapInterval activeGi = getActiveGapInterval(currentPos);
        final boolean inGapBreak = activeGi != null && currentPos < activeGi.endMs();
        Set<Integer> activeSet = inGapBreak ? Collections.emptySet() : computeActiveLines(currentPos);
        int active = inGapBreak ? -1 : computePrimaryActiveIndex(activeSet, currentPos);
        if (active < 0 && activeGi != null) {
            active = activeGi.beforeLineIndex();
        }
        if (active < 0) {
            active = primaryActiveIndex >= 0 ? primaryActiveIndex : highlightedIndex;
        }
        if (active < 0) {
            // The last line that started, or the first one before the lyrics start.
            active = Math.max(0, Math.min(lineRows.size() - 1,
                    LyricsRetiming.findLastStartedLineIndex(retimedLyrics, currentPos)));
        }

        previousPrimaryActiveIndex = primaryActiveIndex;
        if (activeSet.isEmpty() && !inGapBreak && active < retimedLyrics.size()) {
            LyricsRetiming.RetimedEntry entry = retimedLyrics.get(active);
            if (currentPos + LyricsRetiming.HIGHLIGHT_LOOK_AHEAD_MS >= entry.startMs
                    && currentPos <= entry.adjustedEndTimeMs) {
                activeSet = Collections.singleton(active);
            }
        }
        activeIndicesSet.clear();
        activeIndicesSet.addAll(activeSet);
        highlightedIndex = active;
        primaryActiveIndex = active;
        updateLinesDepthOfField(activeIndicesSet, inGapBreak ? -1 : active,
                inGapBreak ? activeGi.beforeLineIndex() : -1, false);
        GapLineView gv = inGapBreak ? gapLineViewsByLine.get(activeGi.beforeLineIndex()) : null;
        if (gv != null) {
            scrollToGapView(gv, false);
        } else {
            scrollToActiveLine(active, false);
        }
        updateWordSync(currentPos);
    }

    /** {@return the instrumental break showing its dots at this position, if any} */
    @Nullable
    private GapInterval getActiveGapInterval(long currentTime) {
        if (!Settings.LYRICS_GAP_DOTS.get() || lyrics == null || !lyrics.synced() || gapIntervals.isEmpty()) {
            return null;
        }
        for (GapInterval gi : gapIntervals) {
            long startCheck = (gi.beforeLineIndex() == 0)
                    ? gi.startMs()
                    : (gi.startMs() - LyricsRetiming.HIGHLIGHT_LOOK_AHEAD_MS);
            if (currentTime >= startCheck && currentTime < gi.nextStartMs()) {
                return gi;
            }
        }
        return null;
    }

    /** {@return where to scroll to show the dots of a break, which opens at the top for the intro} */
    private int gapScrollTarget(GapLineView gv) {
        final int beforeIdx = gv.getInterval().beforeLineIndex();
        return beforeIdx == 0
                ? 0
                : Math.max(0, gv.getTop() + pendingGapDeltaBefore(beforeIdx - 1) - getScrollAnchorPx());
    }

    private void scrollToGapView(GapLineView gv, boolean forceInstant) {
        scrollToGapView(gv, forceInstant, true);
    }

    private void scrollToGapView(GapLineView gv, boolean forceInstant, boolean retryAfterLayout) {
        if (scrollView.getHeight() <= 0) {
            return;
        }
        if (gv.getHeight() == 0 && gv.getTop() == 0 && gv.getInterval().beforeLineIndex() > 0) {
            // Not laid out yet. The tick scrolls there later if this frame does not do.
            if (retryAfterLayout) {
                gv.post(() -> scrollToGapView(gv, forceInstant, false));
            }
            return;
        }
        final int clamped = gapScrollTarget(gv);
        lastScrollTarget = clamped;
        int idx = 0;
        for (int i = 0; i < lineRows.size(); i++) {
            if (lineRows.get(i).getTop() >= gv.getTop()) {
                idx = i;
                break;
            }
        }
        final int refIdx = scrollReferenceIndex(idx);
        final View refView = lineRowAt(refIdx);
        scrollEngine.scrollTo(clamped, gv, idx, refView != null ? refView : gv, refIdx,
                DEFAULT_SCROLL_LOOK_AHEAD_MS, forceInstant);
    }

    private void scrollToActiveLine(int index, boolean forceInstant) {
        scrollToActiveLine(index, forceInstant, true);
    }

    private void scrollToActiveLine(int index, boolean forceInstant, boolean retryAfterLayout) {
        if (index < 0 || index >= lineRows.size() || scrollView.getHeight() <= 0) {
            return;
        }
        View row = lineRows.get(index);
        final int clamped = row.getHeight() == 0 && row.getTop() == 0 && index > 0
                ? -1 : computeBlockScrollTargetY(index);
        if (clamped < 0) {
            // Not laid out yet. The tick scrolls there later if this frame does not do.
            if (retryAfterLayout) {
                row.post(() -> scrollToActiveLine(index, forceInstant, false));
            }
            return;
        }
        lastScrollTarget = clamped;
        final int refIdx = scrollReferenceIndex(index);
        final View refView = lineRowAt(refIdx);
        scrollEngine.scrollTo(clamped, row, index, refView != null ? refView : row, refIdx,
                DEFAULT_SCROLL_LOOK_AHEAD_MS, forceInstant);
    }

    /**
     * Seeks the video to this time of the lyrics, and holds the highlight there until the
     * player has caught up.
     *
     * @param mainIndex The tapped lead line, which stays the active one meanwhile, or -1.
     */
    private void seekToLyricsTime(long lyricsTimeMs, int mainIndex) {
        exitBrowsing();
        final long videoLength = VideoInformation.getVideoLength();
        LyricsManager manager = LyricsManager.getInstance();
        long target = manager.toVideoTimeMs(lyricsTimeMs
                + Settings.LYRICS_OFFSET_MS.get()
                + manager.getTemporaryOffsetMs());
        if (target < 0) {
            target = 0;
        } else if (videoLength > 0 && target > videoLength) {
            target = videoLength;
        }
        final long seekTime = target;
        if (!VideoInformation.seekTo(seekTime)) {
            Logger.printDebug(() -> "Seek to lyrics failed: " + seekTime);
        }
        final long lyricSeekPos = Math.max(0L, lyricsTimeMs);
        seekPending = true;
        pendingSeekPosMs = lyricSeekPos;
        pendingSeekUptimeMs = SystemClock.uptimeMillis();
        pendingSeekMainIndex = mainIndex;
        lastLivePosMs = lyricSeekPos;
        handleSeekOrRewind(lyricSeekPos);
    }

    /** Scrolls without the scroll counting as the user's. */
    private void scrollWithoutBrowsing(int y) {
        isProgrammaticScrolling = true;
        scrollView.scrollTo(0, y);
        isProgrammaticScrolling = false;
    }

    private void clearLines() {
        buildGeneration.incrementAndGet();
        exitBrowsing();
        // Removing the rows clamps the scroll position on the next layout, which is no user scroll.
        isProgrammaticScrolling = true;
        try {
            removeLines();
        } finally {
            scrollView.post(() -> isProgrammaticScrolling = false);
        }
    }

    private void removeLines() {
        for (TextView lineView : lineViews) {
            lineView.animate().cancel();
            if (lineView instanceof LyricsLineView view) {
                // Also cancels the fade, which would otherwise keep a reference to a removed view.
                view.cancelScaleAnimation();
                view.cancelRevealAnimators();
            }
        }
        if (rowHeightAnimator != null) {
            rowHeightAnimator.cancel();
            rowHeightAnimator = null;
        }
        for (View lineRow : lineRows) {
            lineRow.setTranslationY(0f);
        }
        for (GapLineView gv : gapLineViews) {
            gv.destroy();
            linesContainer.removeView(gv);
        }
        gapLineViews.clear();
        gapLineViewsByLine.clear();
        for (View lineRow : new LinkedHashSet<>(lineRows)) {
            linesContainer.removeView(lineRow);
        }
        gapIntervals.clear();
        lineViews.clear();
        lineRows.clear();
        sectionTags.clear();
        agentTags.clear();
        lineWordSpans.clear();
        lineOriginalStarts.clear();
        lineUnsungSpans.clear();
        lineIsBackground.clear();
        final ValueAnimator creditAnimator = creditLookAnimator;
        creditLookAnimator = null;
        if (creditAnimator != null) {
            creditAnimator.cancel();
        }
        footerContainer.setTranslationY(0f);
        creditLineActive = false;
        creditLookToAlpha = -1f;
        creditLookToScale = -1f;
        highlightedIndex = -1;
        primaryActiveIndex = -1;
        previousPrimaryActiveIndex = -1;
        activeIndicesSet.clear();
        previousActiveSet.clear();
        lastOverlayIndex = -1;
        lastOverlayHideActive = false;
        lastScrollTarget = -1;
        lastLivePosMs = 0L;
        seekPending = false;
        pendingSeekPosMs = -1L;
        pendingSeekUptimeMs = 0L;
        pendingSeekMainIndex = -1;
        retimedLyrics = Collections.emptyList();
        retimedAdjustedEndMax = new long[0];
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

    /** {@return the lead line of a background vocal, or the line itself} */
    private int leadLineIndex(int index) {
        int lead = index;
        if (lead >= 0 && lead < lineViews.size()
                && lineViews.get(lead) instanceof LyricsLineView llv
                && llv.isSecondary() && llv.getParentLineIndex() >= 0) {
            lead = llv.getParentLineIndex();
        }
        while (lead > 0 && isSecondaryLineAt(lead)) {
            lead--;
        }
        return lead;
    }

    /** {@return the lead lines of these lines, each once, in order} */
    private List<Integer> leadLineIndices(Iterable<Integer> indices) {
        Set<Integer> leads = new TreeSet<>();
        for (int idx : indices) {
            final int lead = leadLineIndex(idx);
            if (lead >= 0 && lead < retimedLyrics.size()) {
                leads.add(lead);
            }
        }
        return new ArrayList<>(leads);
    }

    /**
     * {@return the lines lit at this position, slightly ahead so a line lights as it is sung: the
     * lines sung, their lead lines, and the background vocals of those that are sung now}
     */
    private Set<Integer> computeActiveLines(long currentTimeMs) {
        if (retimedLyrics.isEmpty()) {
            return Collections.emptySet();
        }
        final long scanTime = currentTimeMs + LyricsRetiming.HIGHLIGHT_LOOK_AHEAD_MS;
        List<Integer> scanned = LyricsRetiming.findActiveRetimedLineIndices(
                retimedLyrics, retimedAdjustedEndMax, scanTime, 0L);
        Set<Integer> set = new TreeSet<>(scanned);
        for (int i : scanned) {
            final int lead = leadLineIndex(i);
            if (lead >= 0) {
                set.add(lead);
            }
        }

        for (int i = 0; i < lineViews.size(); i++) {
            if (!(lineViews.get(i) instanceof LyricsLineView llv) || !llv.isSecondary()) {
                continue;
            }
            final int pIdx = llv.getParentLineIndex();
            if (pIdx < 0 || !set.contains(pIdx)) {
                continue;
            }
            boolean shouldActivateSec = true;
            if (i < retimedLyrics.size() && pIdx < retimedLyrics.size()) {
                LyricsRetiming.RetimedEntry secEntry = retimedLyrics.get(i);
                LyricsRetiming.RetimedEntry parEntry = retimedLyrics.get(pIdx);
                if (secEntry.startMs > parEntry.startMs + BG_LATE_START_MS
                        && scanTime < secEntry.startMs - BG_OPEN_LEAD_MS) {
                    // A vocal sung well into its lead line opens only shortly before it is sung.
                    shouldActivateSec = false;
                } else if (secEntry.actualEndTimeMs > secEntry.startMs
                        && secEntry.actualEndTimeMs < parEntry.actualEndTimeMs - BG_EARLY_END_MS
                        && currentTimeMs > secEntry.actualEndTimeMs + BG_LINGER_MS) {
                    // And one done well before its lead line closes again.
                    shouldActivateSec = false;
                }
            }
            if (shouldActivateSec) {
                set.add(i);
            } else {
                set.remove(i);
            }
        }
        return set;
    }

    /** {@return the lead line the scroll follows among these active lines} */
    private int computePrimaryActiveIndex(Set<Integer> activeSet, long currentTimeMs) {
        if (retimedLyrics.isEmpty()) {
            return -1;
        }
        final int idx = leadLineIndex(LyricsRetiming.calculatePrimaryRetimedLineIndex(
                retimedLyrics, leadLineIndices(activeSet), currentTimeMs, previousPrimaryActiveIndex));
        return Math.max(0, idx);
    }

    private void handleSeekOrRewind(long targetPosMs) {
        if (lyrics == null || lineViews.isEmpty()) return;
        final int lineCount = lineViews.size();
        for (int i = 0; i < lineCount; i++) {
            LyricsLine line = (i < lyrics.lines().size()) ? lyrics.lines().get(i) : null;
            if (line == null) continue;
            long start = line.startTimeMs() - LyricsRetiming.HIGHLIGHT_LOOK_AHEAD_MS;
            TextView tv = lineViews.get(i);
            long end = line.endTimeMs();
            if (tv instanceof LyricsLineView llv) {
                long wordEnd = llv.getLastWordEndMs();
                if (wordEnd != LyricsLine.NO_TIME) {
                    end = (end == LyricsLine.NO_TIME) ? wordEnd : Math.max(end, wordEnd);
                }
            }
            if (end == LyricsLine.NO_TIME && i < retimedLyrics.size()) {
                end = retimedLyrics.get(i).actualEndTimeMs;
            }
            if (end == LyricsLine.NO_TIME) {
                end = (i + 1 < lyrics.lines().size())
                        ? lyrics.lines().get(i + 1).startTimeMs() : Long.MAX_VALUE;
            }
            if (targetPosMs < start) {
                clearWordHighlight(i);
                if (tv instanceof LyricsLineView llv) {
                    llv.setActiveLine(false);
                }
            } else if (targetPosMs >= end) {
                boolean wasActive = activeIndicesSet.contains(i) || i == primaryActiveIndex;
                if (!wasActive) {
                    applyWordColors(i, 0, true);
                    applyBgWordColors(i, 0, true);
                }
                if (tv instanceof LyricsLineView llv) {
                    llv.setActiveLine(false);
                }
            } else {
                applyWordColors(i, targetPosMs, false);
                applyBgWordColors(i, targetPosMs, false);
                if (tv instanceof LyricsLineView llv) {
                    llv.setActiveLine(true);
                }
            }
        }
        previousActiveSet.clear();
    }

    /**
     * {@return the pause between the line sung at this position, with its background vocals,
     * and the next lead line, or {@link Long#MIN_VALUE} after the last line}
     */
    private long currentPauseAfterLineMs(long pos, int refIdx) {
        if (retimedLyrics.isEmpty()) {
            return Long.MIN_VALUE;
        }
        List<Integer> curActive = LyricsRetiming.findActiveRetimedLineIndices(
                retimedLyrics, retimedAdjustedEndMax, pos, 0L);
        final int currentIdx = leadLineIndex(LyricsRetiming.calculatePrimaryRetimedLineIndex(
                retimedLyrics, curActive, pos, Math.max(0, refIdx)));
        if (currentIdx < 0 || currentIdx >= retimedLyrics.size()) {
            return Long.MIN_VALUE;
        }
        long rawEnd = retimedLyrics.get(currentIdx).actualEndTimeMs;
        int nextMainIdx = currentIdx + 1;
        while (nextMainIdx < retimedLyrics.size() && isSecondaryLineAt(nextMainIdx)) {
            rawEnd = Math.max(rawEnd, retimedLyrics.get(nextMainIdx).actualEndTimeMs);
            nextMainIdx++;
        }
        return nextMainIdx < retimedLyrics.size()
                ? retimedLyrics.get(nextMainIdx).startMs - rawEnd
                : Long.MIN_VALUE;
    }

    private void updateHighlight() {
        try {
            Lyrics current = lyrics;
            if (current == null || !current.synced() || lineViews.isEmpty() || !initialBuildComplete) {
                return;
            }

            LyricsManager manager = LyricsManager.getInstance();
            final long rawPos = manager.getPositionMs();
            final boolean hasPendingSeek = (pendingSeekPosMs >= 0);
            final long pos;
            if (hasPendingSeek) {
                // A tapped line stays the position until the player has really moved there.
                long elapsed = SystemClock.uptimeMillis() - pendingSeekUptimeMs;
                boolean reached = elapsed >= SEEK_SETTLE_MIN_MS
                        && rawPos >= pendingSeekPosMs - SEEK_REACHED_BEFORE_MS
                        && rawPos <= pendingSeekPosMs + SEEK_REACHED_AFTER_MS;
                if (reached || elapsed > SEEK_SETTLE_MAX_MS) {
                    pendingSeekPosMs = -1L;
                    pendingSeekMainIndex = -1;
                    pos = rawPos;
                } else {
                    pos = pendingSeekPosMs;
                }
            } else {
                pos = rawPos;
            }
            final long prevPos = lastLivePosMs;
            lastLivePosMs = pos;

            final boolean isInitial = (prevPos == 0 && pos > 0);
            final boolean isBackward = prevPos > 0 && (pos < prevPos - BACKWARD_JUMP_MS
                    || (pos < RESTART_POSITION_MS && prevPos >= BACKWARD_JUMP_MS));
            final boolean isForceJump = prevPos > 0 && Math.abs(pos - prevPos) > FORWARD_JUMP_MS;
            final boolean isSeek = seekPending || hasPendingSeek || isInitial || isBackward || isForceJump;
            seekPending = false;

            if (isInitial || isBackward || isForceJump) {
                handleSeekOrRewind(pos);
            }

            final boolean springScrollOn = Settings.LYRICS_SPRING_SCROLL.get();
            final boolean gapDotsOn = Settings.LYRICS_GAP_DOTS.get();
            GapInterval activeGi = getActiveGapInterval(pos);
            final boolean inGapBreak = (activeGi != null && pos < activeGi.endMs());
            for (GapLineView gv : gapLineViews) {
                boolean shouldBeActive = gapDotsOn && inGapBreak && gv.getInterval() == activeGi;
                gv.updateState(pos, shouldBeActive);
            }

            final int refIdx = Math.max(0, leadLineIndex(primaryActiveIndex >= 0
                    ? primaryActiveIndex : Math.max(0, previousPrimaryActiveIndex)));

            // The scroll starts ahead of the next line, by the pause before it and by how much
            // later the line at the anchor moves than the first line on screen.
            final long pauseMs = currentPauseAfterLineMs(pos, refIdx);
            final long interLineGapMs = pauseMs == Long.MIN_VALUE ? 0L : Math.max(0L, pauseMs);
            final long scrollLookAheadMs;
            if (!springScrollOn) {
                scrollLookAheadMs = LyricsRetiming.HIGHLIGHT_LOOK_AHEAD_MS;
            } else if (pauseMs == Long.MIN_VALUE) {
                scrollLookAheadMs = LyricsRetiming.SCROLL_LOOK_AHEAD_MIN_MS;
            } else {
                scrollLookAheadMs = Math.max(LyricsRetiming.SCROLL_LOOK_AHEAD_MIN_MS,
                        Math.min(LyricsRetiming.SCROLL_LOOK_AHEAD_MAX_MS, pauseMs));
            }
            final int anchorPx = scrollView.getHeight() > 0 ? getScrollAnchorPx() : 0;
            final long staggerLeadMs = springScrollOn
                    ? scrollEngine.computeStaggerLeadMs(scrollLookAheadMs, anchorPx)
                    : 0L;
            final long predictiveTime = pos + scrollLookAheadMs + staggerLeadMs;

            List<Integer> mainPredActiveList = (!inGapBreak && !retimedLyrics.isEmpty())
                    ? leadLineIndices(LyricsRetiming.findActiveRetimedLineIndices(
                            retimedLyrics, retimedAdjustedEndMax, predictiveTime, 50L))
                    : Collections.emptyList();

            Set<Integer> newActiveSet = inGapBreak
                    ? Collections.emptySet()
                    : computeActiveLines(pos);
            if (!inGapBreak && hasPendingSeek && pendingSeekMainIndex >= 0) {
                newActiveSet = new TreeSet<>(newActiveSet);
                newActiveSet.add(pendingSeekMainIndex);
            }

            int newPrimary;
            if (inGapBreak) {
                newPrimary = -1;
            } else if (activeGi != null && pos >= activeGi.endMs() && pos < activeGi.nextStartMs()) {
                newPrimary = Math.max(0, Math.min(activeGi.beforeLineIndex(), lineViews.size() - 1));
            } else if (hasPendingSeek && pendingSeekMainIndex >= 0) {
                newPrimary = pendingSeekMainIndex;
            } else if (!retimedLyrics.isEmpty() && (pos + LyricsRetiming.SCROLL_LOOK_AHEAD_MIN_MS) < retimedLyrics.get(0).startMs) {
                newPrimary = 0;
            } else {
                newPrimary = Math.max(0, leadLineIndex(LyricsRetiming.calculatePrimaryRetimedLineIndex(
                        retimedLyrics, mainPredActiveList, predictiveTime, refIdx)));
                // While playing on, the scroll never steps back to an earlier line.
                if (!isSeek && manager.isPlaying() && newPrimary < refIdx
                        && pos >= prevPos - FORWARD_JUMP_MS) {
                    newPrimary = refIdx;
                }
            }
            final boolean isAfterAllLyrics = !inGapBreak
                    && retimedAdjustedEndMax.length > 0
                    && pos > retimedAdjustedEndMax[retimedAdjustedEndMax.length - 1]
                    && newActiveSet.isEmpty()
                    && footerContainer.getVisibility() == VISIBLE;

            if (!inGapBreak && !isAfterAllLyrics && newPrimary >= 0 && newActiveSet.isEmpty()) {
                boolean primaryStillSinging = true;
                if (newPrimary < retimedLyrics.size()) {
                    LyricsRetiming.RetimedEntry entry = retimedLyrics.get(newPrimary);
                    if (pos + LyricsRetiming.HIGHLIGHT_LOOK_AHEAD_MS < entry.startMs
                            || pos > entry.effectiveEndMs()) {
                        primaryStillSinging = false;
                    }
                }
                if (primaryStillSinging) {
                    newActiveSet = Collections.singleton(newPrimary);
                }
            }

            boolean creditActiveChanged = (isAfterAllLyrics != creditLineActive);
            creditLineActive = isAfterAllLyrics;

            boolean activeSetChanged = !newActiveSet.equals(activeIndicesSet);
            boolean primaryChanged = (newPrimary != primaryActiveIndex);

            if (activeSetChanged) {
                activeIndicesSet.clear();
                activeIndicesSet.addAll(newActiveSet);
            }
            if (primaryChanged) {
                if (primaryActiveIndex >= 0) {
                    previousPrimaryActiveIndex = primaryActiveIndex;
                }
                primaryActiveIndex = newPrimary;
                highlightedIndex = newPrimary;
            }

            applyLineOverlay(primaryActiveIndex);

            if (isSeek || activeSetChanged || primaryChanged || creditActiveChanged) {
                // Update word sync before updateLinesDepthOfField so allSung is current when updateLineLook runs.
                updateWordSync(pos);
                updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex,
                        inGapBreak ? activeGi.beforeLineIndex() : -1, false);
            }

            if (isBrowsing || SystemClock.uptimeMillis() < userScrollUntilUptimeMs || rowHeightAnimator != null) {
                return;
            }

            if (!springScrollOn) {
                if (primaryActiveIndex >= 0 && primaryActiveIndex < lineRows.size()) {
                    scrollAnchorIntoView(primaryActiveIndex, primaryChanged || activeSetChanged || isSeek);
                }
                return;
            }

            if (inGapBreak && scrollView.getHeight() > 0) {
                final int targetIdx = activeGi.beforeLineIndex();
                final GapLineView gv = gapLineViewsByLine.get(targetIdx);
                final View lineRow = lineRowAt(targetIdx);
                final int clamped;
                if (targetIdx == 0) {
                    clamped = 0;
                } else if (gv != null && gv.getTop() > 0) {
                    clamped = gapScrollTarget(gv);
                } else if (lineRow != null && lineRow.getTop() > 0) {
                    // The dots are not laid out yet, but go right above the line.
                    clamped = Math.max(0, lineRow.getTop() + pendingGapDeltaBefore(targetIdx)
                            - Dim.dp(GapLineView.TARGET_HEIGHT_DP) - anchorPx);
                } else {
                    clamped = -1;
                }
                if (clamped >= 0) {
                    if (isSeek || clamped != lastScrollTarget) {
                        lastScrollTarget = clamped;
                        final View targetView = gv != null ? gv : lineRow;
                        final int prevRef = scrollReferenceIndex(targetIdx);
                        final View refView = lineRowAt(prevRef);
                        scrollEngine.scrollTo(clamped, targetView, targetIdx, null, targetIdx,
                                refView != null ? refView : targetView, prevRef,
                                scrollLookAheadMs, interLineGapMs, false);
                    }
                    return;
                }
            }

            if (creditLineActive) {
                final int clamped = computeBlockScrollTargetY(lineRows.size());
                if (clamped >= 0 && (isSeek || creditActiveChanged || lastScrollTarget < 0)) {
                    lastScrollTarget = clamped;
                    final int prevRef = primaryActiveIndex >= 0
                            ? primaryActiveIndex : Math.max(0, lineRows.size() - 1);
                    final View refView = lineRowAt(prevRef);
                    scrollEngine.scrollTo(clamped, footerContainer, lineRows.size(), null, lineRows.size(),
                            refView != null ? refView : footerContainer, prevRef,
                            scrollLookAheadMs, interLineGapMs, false);
                }
                return;
            }

            if (primaryActiveIndex >= 0 && primaryActiveIndex < lineRows.size()) {
                final int clamped = computeBlockScrollTargetY(primaryActiveIndex);
                if (clamped >= 0 && (isSeek || primaryChanged || creditActiveChanged || lastScrollTarget < 0)) {
                    lastScrollTarget = clamped;
                    scrollToRow(clamped, primaryActiveIndex, scrollLookAheadMs, interLineGapMs);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateHighlight failure", ex);
        }
    }

    /**
     * Gives every line the look of its distance from the line being sung, with the lines
     * further away changing a little later.
     *
     * @param gapFocusIndex The line after the instrumental break being shown, if any.
     */
    private void updateLinesDepthOfField(Set<Integer> activeSet, int primaryIndex, int gapFocusIndex, boolean instant) {
        final boolean isAutoScrollEnabled = !isBrowsing;
        final boolean isLrcSynced = (lyrics != null && lyrics.synced());
        final float delayIncrementMs = LyricsRetiming.calculateScrollDelayIncrement(
                scrollEngine.getScrollDurationMs());
        final int count = lineViews.size();

        final int[] mainOrd = new int[count];
        int curOrd = 0;
        for (int i = 0; i < count; i++) {
            TextView lv = lineViews.get(i);
            if (lv instanceof LyricsLineView slv && slv.isSecondary() && slv.getParentLineIndex() >= 0) {
                int pIdx = slv.getParentLineIndex();
                mainOrd[i] = (pIdx >= 0 && pIdx < i) ? mainOrd[pIdx] : Math.max(0, curOrd - 1);
            } else {
                mainOrd[i] = curOrd++;
            }
        }

        int anchorLineIdx = primaryIndex;
        if (anchorLineIdx < 0 && gapFocusIndex >= 0) {
            anchorLineIdx = gapFocusIndex;
        }
        if (anchorLineIdx < 0 && highlightedIndex >= 0) {
            anchorLineIdx = highlightedIndex;
        }
        if (anchorLineIdx < 0 && !activeSet.isEmpty()) {
            for (int act : activeSet) {
                anchorLineIdx = act;
                break;
            }
        }
        final int creditOrd = curOrd;
        final int anchorOrd = creditLineActive
                ? creditOrd
                : ((anchorLineIdx >= 0 && anchorLineIdx < count) ? mainOrd[anchorLineIdx] : -1);

        Set<Integer> activeParentsFromSecondary = new HashSet<>();
        for (int i = 0; i < count; i++) {
            TextView v = lineViews.get(i);
            if (v instanceof LyricsLineView slv && slv.isSecondary() && slv.getParentLineIndex() >= 0) {
                if (activeSet.contains(i)) {
                    activeParentsFromSecondary.add(slv.getParentLineIndex());
                }
            }
        }

        final boolean springScrollOn = Settings.LYRICS_SPRING_SCROLL.get();
        for (int i = 0; i < count; i++) {
            final TextView lv = lineViews.get(i);
            if (lv instanceof LyricsLineView slv && slv.isSecondary() && slv.getParentLineIndex() >= 0) {
                int pIdx = slv.getParentLineIndex();
                boolean parentActive = (pIdx >= 0 && (activeSet.contains(pIdx) || activeParentsFromSecondary.contains(pIdx)));
                boolean isExpanded = !isLrcSynced || activeSet.contains(i) || parentActive;
                boolean isLineSinging = !isLrcSynced || activeSet.contains(i);
                slv.updateLineLook(isLineSinging, isLineSinging ? 0 : 1, 0L, isAutoScrollEnabled, isLrcSynced, instant);
                slv.animateSecondaryExpansion(isExpanded, instant);
                continue;
            }

            final boolean hasActiveSec = activeParentsFromSecondary.contains(i);
            final boolean isActive = !isLrcSynced || activeSet.contains(i) || hasActiveSec;

            // Every active line is fully focused.
            final int dist = (isActive || anchorOrd < 0) ? 0 : Math.abs(mainOrd[i] - anchorOrd);
            final long staggerDelayMs = springScrollOn
                    ? Math.min(MAX_LOOK_STAGGER_MS, Math.round(dist * delayIncrementMs)) : 0L;

            if (lv instanceof LyricsLineView llv) {
                llv.updateLineLook(isActive, dist, staggerDelayMs, isAutoScrollEnabled, isLrcSynced, instant);
            } else {
                final float targetAlpha;
                if (!isAutoScrollEnabled) {
                    targetAlpha = LyricsViewSupport.BROWSING_LINE_ALPHA;
                } else if (isActive) {
                    targetAlpha = 1.0f;
                } else {
                    targetAlpha = Settings.LYRICS_FOCUS_SPOTLIGHT.get() ? 0.12f : INACTIVE_LINE_ALPHA;
                }
                LyricsViewSupport.fadeTo(lv, targetAlpha);
            }
        }

        if (footerContainer.getVisibility() == VISIBLE) {
            final int creditDist = (!isLrcSynced || anchorOrd < 0) ? 0 : Math.abs(creditOrd - anchorOrd);
            final long creditStaggerMs = Math.min(MAX_LOOK_STAGGER_MS, Math.round(creditDist * delayIncrementMs));
            animateCreditBlockLook(creditLineActive, creditDist, creditStaggerMs,
                    isAutoScrollEnabled, isLrcSynced, instant);
        }
    }

    private void animateCreditBlockLook(
            boolean isActive,
            int distance,
            long staggerDelayMs,
            boolean isAutoScrollEnabled,
            boolean isLrcSynced,
            boolean instant) {
        final boolean focusSpotlightOn = Settings.LYRICS_FOCUS_SPOTLIGHT.get();
        final float targetAlpha;
        if (!isAutoScrollEnabled || !isLrcSynced) {
            targetAlpha = LyricsViewSupport.BROWSING_LINE_ALPHA;
        } else if (isActive) {
            targetAlpha = 1.0f;
        } else if (focusSpotlightOn) {
            targetAlpha = LyricsViewSupport.spotlightAlpha(distance);
        } else {
            targetAlpha = INACTIVE_LINE_ALPHA;
        }
        final float targetScale;
        if (isActive || !isAutoScrollEnabled || !isLrcSynced || !Settings.LYRICS_LINE_SCALE.get()) {
            targetScale = 1.0f;
        } else if (focusSpotlightOn) {
            targetScale = LyricsViewSupport.spotlightScale(distance);
        } else {
            targetScale = LyricsViewSupport.INACTIVE_LINE_SCALE;
        }

        final boolean firstLook = creditLookToAlpha < 0f;
        if (!instant && !firstLook
                && Math.abs(creditLookToAlpha - targetAlpha) < 0.001f
                && Math.abs(creditLookToScale - targetScale) < 0.001f) {
            return;
        }
        // Cleared first, or the cancelled animator's end listener settles the old target.
        final ValueAnimator running = creditLookAnimator;
        creditLookAnimator = null;
        if (running != null) {
            running.cancel();
        }
        creditLookToAlpha = targetAlpha;
        creditLookToScale = targetScale;
        footerContainer.setPivotX(footerContainer.getPaddingLeft());
        footerContainer.setPivotY(0f);
        if (instant || firstLook) {
            applyCreditLook(targetAlpha, targetScale);
            return;
        }

        final float fromAlpha = creditLookCurAlpha;
        final float fromScale = creditLookCurScale;
        final int alphaMs = Math.max(1, (int) (CREDIT_LOOK_ALPHA_MS + staggerDelayMs));
        final int scaleMs = Math.max(1, (int) (CREDIT_LOOK_SCALE_MS + staggerDelayMs * 2L));
        final int totalMs = Math.max(alphaMs, scaleMs);

        ValueAnimator animator = ValueAnimator.ofFloat(0f, (float) totalMs);
        animator.setDuration(totalMs);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(anim -> {
            final float clockMs = (Float) anim.getAnimatedValue();
            final float fAlpha = LyricsViewSupport.EASE.getInterpolation(Math.min(1f, clockMs / alphaMs));
            final float fScale = LyricsViewSupport.EASE.getInterpolation(Math.min(1f, clockMs / scaleMs));
            applyCreditLook(fromAlpha + (targetAlpha - fromAlpha) * fAlpha,
                    fromScale + (targetScale - fromScale) * fScale);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (creditLookAnimator == animation) {
                    creditLookAnimator = null;
                    applyCreditLook(targetAlpha, targetScale);
                }
            }
        });
        creditLookAnimator = animator;
        animator.start();
    }

    private void applyCreditLook(float alpha, float scale) {
        creditLookCurAlpha = alpha;
        creditLookCurScale = scale;
        footerContainer.setAlpha(alpha);
        footerContainer.setScaleX(scale);
        footerContainer.setScaleY(scale);
    }

    /**
     * Plays the dots over a silence long enough to read as an instrumental break, in place of the
     * section label of the section that comes next.
     *
     * <p>Everything here is derived from the playback position rather than accumulated, so a seek
     * into the middle of a gap simply lands on that gap's frame and a dropped tick cannot leave the
     * label or the dots stranded.
     */
    private void scrollAnchorIntoView(int anchor, boolean lineChanged) {
        if (anchor < 0 || anchor >= lineRows.size() || scrollView.getHeight() <= 0) {
            return;
        }
        if (seekPending || SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
            return;
        }
        if (isLayoutRequested()) {
            deferScrollToPreDraw(anchor, lineChanged);
            return;
        }
        final int clamped = computeBlockScrollTargetY(anchor);
        if (clamped < 0) return;
        final int dist = Math.abs(scrollView.getScrollY() - clamped);
        if (dist > scrollView.getHeight() * SCROLL_INSTANT_THRESHOLD_FACTOR) {
            scrollEngine.reset();
            scrollWithoutBrowsing(clamped);
            lastScrollTarget = clamped;
        } else if (clamped != lastScrollTarget
                && (lineChanged || dist > scrollView.getHeight() / SCROLL_SMOOTH_THRESHOLD_FACTOR)) {
            lastScrollTarget = clamped;
            scrollToActiveLine(anchor, false);
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

    private void updateWordSync(long positionMs) {
        boolean enabled = Settings.LYRICS_WORD_SYNC.get();
        if (enabled != wordSyncWasEnabled) {
            if (!enabled) {
                int count = Math.min(lineWordSpans.size(), lineViews.size());
                for (int i = 0; i < count; i++) {
                    resetLineForWordSyncOff(i, activeIndicesSet.contains(i));
                }
                previousActiveSet.clear();
                wordSyncWasEnabled = enabled;
                return;
            }
            wordSyncWasEnabled = enabled;
        }
        if (!enabled) {
            return;
        }

        // A line that went out is left fully sung, or unsung when the position went back before it.
        for (int prevIdx : previousActiveSet) {
            if (activeIndicesSet.contains(prevIdx)) {
                continue;
            }
            LyricsLine prevLine = (lyrics != null && prevIdx < lyrics.lines().size())
                    ? lyrics.lines().get(prevIdx) : null;
            if (prevLine != null && positionMs < prevLine.startTimeMs()) {
                clearWordHighlight(prevIdx);
            } else {
                applyWordColors(prevIdx, 0, true);
                applyBgWordColors(prevIdx, 0, true);
            }
        }
        previousActiveSet.clear();
        previousActiveSet.addAll(activeIndicesSet);

        // Highlight words for all active lines simultaneously (multi-lyrics support).
        for (int activeIdx : activeIndicesSet) {
            if (activeIdx >= 0 && activeIdx < lineViews.size()) {
                TextView tv = lineViews.get(activeIdx);
                if (activeIdx < lineWordSpans.size() && tv instanceof LyricsLineView llv) {
                    List<LyricsSpanBuilder.WordTiming> spans = lineWordSpans.get(activeIdx);
                    if (spans != null && !spans.isEmpty() && !llv.hasWordTimings()) {
                        int origStart = activeIdx < lineOriginalStarts.size() ? lineOriginalStarts.get(activeIdx) : 0;
                        llv.setHighlight(spans, positionMs, false, LyricsColors.unsungWordColor(), origStart);
                    }
                }
                final boolean hasWords = (tv instanceof LyricsLineView llv)
                        ? llv.hasWordTimings()
                        : (activeIdx < lineWordSpans.size() && !lineWordSpans.get(activeIdx).isEmpty());
                if (!hasWords) {
                    applyWordColors(activeIdx, 0, true);
                } else {
                    applyWordColors(activeIdx, positionMs, false);
                }
                applyBgWordColors(activeIdx, positionMs, false);
            }
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
        if (lyrics == null || parentIndex < 0) return;
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
            boolean isLineSung = allSung;
            if (!isLineSung && timings.isEmpty() && positionMs != Long.MIN_VALUE && lyrics != null && index < lyrics.lines().size()) {
                long lineStart = lyrics.lines().get(index).startTimeMs();
                isLineSung = (positionMs >= lineStart - 100L);
            }
            ((LyricsLineView) lineView).setHighlight(
                    timings, positionMs, isLineSung,
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
     * Builds the small label that introduces a section, drawn as a white pill above the line it
     * belongs to.
     *
     * @return {@code null} when the line opens no section. The label is always built when there
     * is one, so flipping the setting on can reveal it without rebuilding the lyrics;
     * whether it is shown is decided by its visibility.
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
            }
            for (int i = 0; i < agentTags.size(); i++) {
                TextView tag = agentTags.get(i);
                if (tag == null) {
                    continue;
                }
                tag.setAlpha(1f);
                if (tag.getVisibility() != visibility) {
                    tag.setVisibility(visibility);
                }
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
        // Lines laid out against the old row have to be measured again against the new one.
        final Lyrics current = lyrics;
        if (dualWasUp && current != null && !current.isEmpty()) {
            fadeOutAndRebuild(current);
        }
    }

    /** Fades the lines out, then builds them again once they are gone. */
    private void fadeOutAndRebuild(Lyrics current) {
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
            if (outGeneration == buildGeneration.get() && lyrics == current) {
                showLyrics(current);
            }
        }, SECONDARY_REVEAL_MILLISECONDS);
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
        if (onlyMode != OnlyMode.NONE) {
            updateSessionMainLine();
        }
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
                final boolean isActive = activeIndicesSet.contains(i);
                resetLineForWordSyncOff(i, isActive);
                forEachBgLine(i, lyrics, bg -> resetLineForWordSyncOff(bg, isActive));
            }
            previousActiveSet.clear();
        }
        wordSyncWasEnabled = enabled;
        applyLyricLayoutSettings();
    }

    public void applySpringScrollSetting() {
        final boolean enabled = Settings.LYRICS_SPRING_SCROLL.get();
        scrollEngine.setSpringScrollEnabled(enabled);
        scrollEngine.reset();
        lastScrollTarget = -1;
        if (lyrics != null && !lyrics.isEmpty()) {
            updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1, true);
            updateHighlight();
        }
    }

    public void applyGapDotsSetting() {
        final boolean gapDotsOn = Settings.LYRICS_GAP_DOTS.get();
        if (!gapDotsOn) {
            for (GapLineView gv : gapLineViews) {
                gv.hide();
            }
        }
        lastScrollTarget = -1;
        if (lyrics != null && !lyrics.isEmpty()) {
            updateHighlight();
        }
    }

    public void applyFocusSpotlightSetting() {
        if (lyrics != null && !lyrics.isEmpty()) {
            updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1, true);
            updateHighlight();
        }
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
        for (TextView tag : agentTags) {
            if (tag != null) {
                tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, tagSize);
                final int insetH = LyricsViewSupport.sectionTagPaddingHorizontal(tag.getContext());
                final int insetV = LyricsViewSupport.scaledPixels(tag.getContext(),
                        SECTION_TAG_PADDING_VERTICAL_FACTOR);
                tag.setPadding(insetH, insetV, insetH, insetV);
            }
        }
    }

    public void applyLineScale() {
        updateLinesDepthOfField(activeIndicesSet, primaryActiveIndex, -1, false);
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    public void applyHideInfo() {
        CharSequence info = creditView.getText();
        final boolean visible = !Settings.LYRICS_HIDE_INFO.get()
                && info != null && info.length() > 0;
        creditView.setVisibility(visible ? VISIBLE : GONE);
    }

    public void applyDynamicBackgroundSettings() {
        dynamicBackgroundView.setVisibility(Settings.LYRICS_DYNAMIC_BACKGROUND.get() ? VISIBLE : GONE);
        dynamicBackgroundView.onSettingsChanged();
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

    /** The furthest lines change their look at most this much later than the sung one. */
    private static final long MAX_LOOK_STAGGER_MS = 200L;
    private static final long CREDIT_LOOK_ALPHA_MS = 750L;
    private static final long CREDIT_LOOK_SCALE_MS = 800L;
    /** A tapped line is the position for at least this long after the seek. */
    private static final long SEEK_SETTLE_MIN_MS = 150L;
    /** And at most this long, if the player never gets near it. */
    private static final long SEEK_SETTLE_MAX_MS = 1500L;
    private static final long SEEK_REACHED_BEFORE_MS = 200L;
    private static final long SEEK_REACHED_AFTER_MS = 2500L;
    /** A position this far behind the last one is a seek back. */
    private static final long BACKWARD_JUMP_MS = 600L;
    /** A position this close to the start after a later one is a restart. */
    private static final long RESTART_POSITION_MS = 200L;
    /** A position this far ahead of the last one is a seek forward. */
    private static final long FORWARD_JUMP_MS = 1000L;
    /** A line with no end of its own is taken to last at least this long, and at most the max. */
    private static final long UNENDED_LINE_MIN_MS = 1000L;
    private static final long UNENDED_LINE_MAX_MS = 4500L;
    /** The dots of a break show for at least this long before they close. */
    private static final long MIN_GAP_DOTS_MS = 600L;
    /** A background vocal starting this long after its lead line is held closed until it is sung. */
    private static final long BG_LATE_START_MS = 550L;
    /** How long before it is sung a late background vocal opens. */
    private static final long BG_OPEN_LEAD_MS = 250L;
    /** A background vocal ending this long before its lead line closes ahead of it. */
    private static final long BG_EARLY_END_MS = 600L;
    /** How long such a background vocal stays open after it ends. */
    private static final long BG_LINGER_MS = 300L;
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

    private static boolean[] secondaryLineFlags(List<LyricsLine> lines) {
        boolean[] flags = new boolean[lines.size()];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = isSecondaryLyricLine(i, lines);
        }
        return flags;
    }

    /**
     * {@return which lines sit on the right: those in a right-to-left script, swapped for the
     * second singer of a duet}
     */
    private static boolean[] rightAlignedLines(List<LyricsLine> lines, boolean[] isSecFlags) {
        boolean isDuetSong = false;
        for (LyricsLine l : lines) {
            if (l.isDuet()) {
                isDuetSong = true;
                break;
            }
        }
        boolean[] rightAligned = new boolean[lines.size()];
        int parentIdx = -1;
        for (int i = 0; i < rightAligned.length; i++) {
            if (!isSecFlags[i]) {
                parentIdx = i;
            }
            LyricsLine l = lines.get(i);
            final boolean lineRtl = ScriptDetector.isLineRtl(l.text());
            // A background vocal sits on its lead line's side.
            final boolean isDuetLine = l.isDuet()
                    || (isSecFlags[i] && parentIdx >= 0 && lines.get(parentIdx).isDuet());
            rightAligned[i] = isDuetSong ? lineRtl != isDuetLine : lineRtl;
        }
        return rightAligned;
    }

    /**
     * {@return the instrumental breaks long enough for dots: before the first line, and
     * between a lead line with its background vocals and the next lead line}
     */
    private static List<GapInterval> buildGapIntervals(List<LyricsLine> lines, boolean[] isSecFlags,
                                                       boolean[] lineRightAligned) {
        List<GapInterval> gaps = new ArrayList<>();
        final boolean forceCenter = Settings.LYRICS_FORCE_CENTER.get();
        int prevMainIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isSecFlags[i] && prevMainIdx >= 0) {
                continue;
            }
            final LyricsLine line = lines.get(i);
            final int prevIdx = prevMainIdx;
            prevMainIdx = i;
            final long currentStart = line.startTimeMs();
            if (currentStart == LyricsLine.NO_TIME) {
                continue;
            }

            final long gapStart;
            if (prevIdx < 0) {
                if (currentStart < LyricsRetiming.LONG_PAUSE_MS) {
                    continue;
                }
                gapStart = 0;
            } else {
                final LyricsLine prevLine = lines.get(prevIdx);
                final long prevStart = prevLine.startTimeMs();
                if (prevStart == LyricsLine.NO_TIME) {
                    continue;
                }
                long prevEnd = prevLine.sungEndMs();
                for (int s = prevIdx + 1; s < i; s++) {
                    final long secEnd = lines.get(s).sungEndMs();
                    if (secEnd != LyricsLine.NO_TIME) {
                        prevEnd = prevEnd == LyricsLine.NO_TIME ? secEnd : Math.max(prevEnd, secEnd);
                    }
                }
                final long gapBetweenStarts = currentStart - prevStart;
                // A line with no words and a next line close after it is not followed by a
                // break worth showing, whatever its end says.
                if (gapBetweenStarts < LyricsRetiming.ASSUMED_LINE_END_MAX_GAP_MS
                        && prevLine.words().isEmpty()) {
                    continue;
                }
                final long safeEnd;
                if (prevEnd != LyricsLine.NO_TIME) {
                    safeEnd = prevEnd;
                } else {
                    // A line with no end is sung for a while at least.
                    final long minSafeEnd = prevStart + Math.min(UNENDED_LINE_MAX_MS,
                            Math.max(UNENDED_LINE_MIN_MS, gapBetweenStarts / 2));
                    safeEnd = Math.max(LyricsRetiming.estimateEndMs(prevLine, currentStart), minSafeEnd);
                }
                if (currentStart - safeEnd < LyricsRetiming.LONG_PAUSE_MS) {
                    continue;
                }
                gapStart = safeEnd + GapLineView.LYRICS_GAP_START_LAG_MS;
            }
            final long gapEnd = Math.max(gapStart + MIN_GAP_DOTS_MS,
                    currentStart - GapLineView.GAP_EXIT_DURATION_MS);
            gaps.add(new GapInterval(gapStart, gapEnd, currentStart, i, lineRightAligned[i],
                    forceCenter || line.isCentered(), ScriptDetector.isLineRtl(line.text())));
        }
        return gaps;
    }

    /** Whether the built line at this index is a background vocal in its lead line's row. */
    private boolean isSecondaryLineAt(int index) {
        if (index <= 0) {
            return false;
        }
        if (index < lineViews.size()) {
            TextView tv = lineViews.get(index);
            if (tv instanceof LyricsLineView llv) {
                return llv.isSecondary() && llv.getParentLineIndex() >= 0;
            }
        }
        if (index < retimedLyrics.size()) {
            return retimedLyrics.get(index).isSecondary;
        }
        return index < lineIsBackground.size()
                && Boolean.TRUE.equals(lineIsBackground.get(index));
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
}
