/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5527
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.LyricsPresentation;
import app.morphe.extension.music.patches.lyrics.derive.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.derive.LyricsTranslator;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsTextFormatter;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.ui.GapLineView.GapInterval;
import app.morphe.extension.music.patches.lyrics.ui.LyricsGlyphMaskCache.ClusterMaskCache;
import app.morphe.extension.music.patches.lyrics.ui.PlayerLiveLyricsSupport.DotPhase;
import app.morphe.extension.music.patches.lyrics.ui.PlayerLiveLyricsSupport.GapKind;
import app.morphe.extension.music.patches.lyrics.ui.PlayerLiveLyricsSupport.StateSnapshot;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WipeResult;
import app.morphe.extension.music.patches.lyrics.ui.WordSweepAnimator.EmphasisSample;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Single-line live karaoke lyrics strip shown inside {@code @id/current_song_view}.
 * Inflated from {@code watch_while_layout*.xml} by the Third-party lyrics patch.
 */
@SuppressWarnings("unused")
public final class PlayerLiveLyricsView extends View
        implements LyricsManager.Listener, Choreographer.FrameCallback {

    /** Attached views. Only accessed on the main thread. */
    private static final Set<PlayerLiveLyricsView> ACTIVE_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final Interpolator ELEV_EASE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator REVEAL_EASE = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);

    private static final float TEXT_SIZE_SP = 15f;
    private static final int TOP_PADDING_DP = 8;
    private static final int BOTTOM_PADDING_DP = 4;
    private static final int CHEVRON_WIDTH_DP = 6;
    private static final int CHEVRON_HEIGHT_DP = 10;
    private static final int CHEVRON_GAP_DP = 10;
    private static final int CHEVRON_RIGHT_INSET_DP = 6;
    private static final int EDGE_FADE_LEFT_DP = 8;
    private static final int EDGE_FADE_RIGHT_DP = 12;
    private static final int SWEEP_SOFT_EDGE_DP = 8;
    private static final float GAP_NOTE_SIZE_DP = 10.5f;
    private static final float GAP_NOTE_SPACING_DP = 5.0f;
    private static final float GAP_TEXT_GAP_DP = 8.0f;
    private static final float GAP_WAVE_AMPLITUDE_DP = 1.6f;
    private static final float GAP_NOTE_SCALE_GAIN = 0.09f;
    private static final int GAP_TEXT_BASE_ALPHA = 175;

    // Subtle single-line equivalents of WordSweepAnimator's elevate/glow/scale curves.
    private static final float NORMAL_ELEVATE_DP = 1.0f;
    private static final float HEAVY_LIFT_DP = 1.15f;
    private static final float HEAVY_RISE_DP = 0.65f;
    private static final float HEAVY_SCALE_GAIN = 0.045f;
    private static final int WORD_GLOW_RADIUS_DP = 3;
    private static final int WORD_GLOW_PEAK_ALPHA = 56;
    private static final int MASK_PAD_DP = 8;

    private static final int NOTE_SIZE_DP = 12;
    private static final int NOTE_TEXT_GAP_DP = 7;
    private static final long LINE_FADE_DURATION_MS = 240L;
    private static final long REVEAL_ENTER_DURATION_MS = 260L;
    private static final long REVEAL_EXIT_DURATION_MS = 220L;
    private static final float TRANSITION_SLIDE_DP = 4.5f;
    private static final long FETCH_DEBOUNCE_MS = 200L;

    private static final int SUNG_BASE_ALPHA = 245;
    private static final int UNSUNG_BASE_ALPHA = 122;
    private static final int LINE_ONLY_BASE_ALPHA = 235;
    private static final int CHEVRON_BASE_ALPHA = 170;
    private static final int FETCHING_BASE_ALPHA = 175;

    private final TextPaint textPaint = new TextPaint(
            Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable
    private LinearGradient leftFadeShader;
    @Nullable
    private LinearGradient rightFadeShader;
    private int rightFadeShaderViewportWidth = -1;
    private final PlayerLiveLyricsTransition outgoingTransition = new PlayerLiveLyricsTransition();
    private final Path chevronPath = new Path();
    private final Path notePath = new Path();
    private final Path gapSingleNotePath = new Path();
    private final Path gapDoubleNotePath = new Path();
    private final EmphasisSample emphasisScratch = new EmphasisSample();
    private final WipeResult wipeScratch = new WipeResult();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable showLoadingRunnable = this::onShowLoadingTimeout;

    private final int topPaddingPx = Dim.dp(TOP_PADDING_DP);
    private final int bottomPaddingPx = Dim.dp(BOTTOM_PADDING_DP);
    private final int chevronReservePx = Dim.dp(chevronTotalDp());
    private final float chevronWidthPx = Dim.dp(CHEVRON_WIDTH_DP);
    private final float chevronHeightPx = Dim.dp(CHEVRON_HEIGHT_DP);
    private final float chevronRightInsetPx = Dim.dp(CHEVRON_RIGHT_INSET_DP);
    private final float fadeLeftPx = Dim.dp(EDGE_FADE_LEFT_DP);
    private final float fadeRightPx = Dim.dp(EDGE_FADE_RIGHT_DP);
    private final float sweepSoftEdgePx = Dim.dp(SWEEP_SOFT_EDGE_DP);
    private final float gapNoteSizePx = Dim.dp(GAP_NOTE_SIZE_DP);
    private final float gapSingleNoteWidthPx = gapNoteSizePx * 0.68f;
    private final float gapNoteSpacingPx = Dim.dp(GAP_NOTE_SPACING_DP);
    private final float gapTextGapPx = Dim.dp(GAP_TEXT_GAP_DP);
    private final float gapWaveAmpPx = Dim.dp(GAP_WAVE_AMPLITUDE_DP);
    private final float normalElevatePx = Dim.dp(NORMAL_ELEVATE_DP);
    private final float heavyLiftPx = Dim.dp(HEAVY_LIFT_DP);
    private final float heavyRisePx = Dim.dp(HEAVY_RISE_DP);
    private final float wordGlowRadiusPx = Dim.dp(WORD_GLOW_RADIUS_DP);
    private final float maskPadPx = Dim.dp(MASK_PAD_DP);
    private final float noteSizePx = Dim.dp(NOTE_SIZE_DP);
    private final float noteTextGapPx = Dim.dp(NOTE_TEXT_GAP_DP);
    private final float transitionSlidePx = Dim.dp(TRANSITION_SLIDE_DP);

    private static final int KIND_NONE = 0;
    private static final int KIND_LOADING = 1;
    private static final int KIND_GAP = 2;
    private static final int KIND_LINE = 3;

    private LyricsManager.State currentState = LyricsManager.State.IDLE;
    @Nullable
    private Lyrics currentLyrics;
    @Nullable
    private Lyrics derivedForLyrics;
    private String derivedMode = Settings.LIVE_MODE_ORIGINAL;
    private int derivedGeneration;
    @Nullable
    private List<String> liveTranslatedLines;
    @Nullable
    private List<LyricsLine> liveRomanizedLines;
    private List<GapInterval> gapIntervals = Collections.emptyList();
    private String fetchingText = "";
    private String[] gapIntroOptions = new String[0];
    private String[] gapInterludeOptions = new String[0];
    private String[] gapOutroOptions = new String[0];

    private boolean pendingTrackTransition;
    private boolean loadingShown;
    private long loadingEnteredUptimeMs;
    private long gapEnteredUptimeMs;
    private long cachedGapNextStartMs = -1L;
    private GapKind cachedGapKind = GapKind.NONE;
    private String cachedGapLabel = "";

    private int cachedLineIndex = -2;
    private boolean cachedInGap = false;
    private int indexHint = -1;
    private String renderedText = "";
    @Nullable
    private StaticLayout textLayout;
    @Nullable
    private ClusterMaskCache maskCache;
    private float lineLeftPx;
    private float totalTextWidth;
    private boolean lineIsRtl;
    private boolean lineMotionOff;
    private boolean hasWordSweep;
    private boolean pendingInitialScroll;
    private int wordCount;
    private long activeLineStartMs;
    private long activeLineEndMs;

    @Nullable
    private StaticLayout[] wordLayouts = new StaticLayout[0];
    private float[] wordStartX = new float[0];
    private float[] wordEndX = new float[0];
    private float[] wordVisualL = new float[0];
    private float[] wordVisualR = new float[0];
    private Integer[] visualOrder = new Integer[0];
    private long[] wordStartMs = new long[0];
    private long[] wordEndMs = new long[0];

    // Normal word elevation tracks (matches WordSweepAnimator.ElevTracks).
    private boolean[] wordElevTriggered = new boolean[0];
    private float[] wordElevFrom = new float[0];
    private float[] wordElevTarget = new float[0];
    private long[] wordElevStartUptimeMs = new long[0];
    private float[] wordElevCurrent = new float[0];

    // Heavy word emphasis & glow tracks (matches WordSweepAnimator heavy word gate).
    private boolean[] wordIsHeavy = new boolean[0];
    private float[][] wordEmphasisTable = new float[0][];
    private float[] wordEmphasisSpanMs = new float[0];
    private float[] wordAmplitude = new float[0];
    private float[] wordGlowMax = new float[0];

    private float[] drawLiftPx = new float[0];
    private float[] drawScale = new float[0];
    private float[] drawGlowAlpha = new float[0];

    private float scrollX;
    private long lastFrameUptimeMs;
    private long lineTransitionStartMs;
    private boolean frameLoopActive;

    private float revealFraction;
    private float revealStartFraction;
    private float revealTargetFraction;
    private long revealAnimStartMs;
    private int lastMeasuredHeight = -1;

    private int lastFrameKind = KIND_NONE;
    private long lastCapturedNow;
    private long lastPositionMs;
    private float lastSweepX;
    private float lastScrollX;
    private long lastGapStartMs;
    private long lastGapEndMs;
    private long lastGapNextStartMs;
    private boolean lastGapRightAligned;
    private GapKind lastGapKind = GapKind.NONE;
    private String lastGapLabel = "";

    private int foregroundRgb = 0xFFFFFF;
    private boolean launchAvailable = true;
    private boolean launchFailedForTrack;
    private int lastLineSweepIndex = -2;
    private long lastLineClockPos = Long.MIN_VALUE;
    private float lastLineSweepX;

    private int tintColor(int alpha) {
        return Color.argb(alpha,
                Color.red(foregroundRgb),
                Color.green(foregroundRgb),
                Color.blue(foregroundRgb));
    }

    private int activeViewportWidth() {
        return Math.max(0, getWidth() - (launchAvailable ? chevronReservePx : 0));
    }

    private static int chevronTotalDp() {
        return CHEVRON_GAP_DP + CHEVRON_WIDTH_DP + CHEVRON_RIGHT_INSET_DP;
    }

    public PlayerLiveLyricsView(Context context) {
        this(context, null);
    }

    public PlayerLiveLyricsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        textPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP, getResources().getDisplayMetrics()));
        textPaint.setColor(tintColor(SUNG_BASE_ALPHA));

        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        chevronPaint.setStrokeWidth(Dim.dp(1.75f));
        chevronPaint.setColor(tintColor(CHEVRON_BASE_ALPHA));

        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        fetchingText = str("morphe_music_lyrics_player_live_fetching");
        gapIntroOptions = ResourceUtils.getStringArray("morphe_music_lyrics_player_live_gap_intro_entries");
        gapInterludeOptions = ResourceUtils.getStringArray("morphe_music_lyrics_player_live_gap_interlude_entries");
        gapOutroOptions = ResourceUtils.getStringArray("morphe_music_lyrics_player_live_gap_outro_entries");
        buildNotePaths();

        setContentDescription(str("morphe_music_lyrics_player_live_title"));
        setOnClickListener(v -> {
            if (launchAvailable && !LyricsPanelInstaller.openLyricsTab()) {
                launchFailedForTrack = true;
                updateLaunchAvailability();
            }
        });
        updateLaunchAvailability();
        applyTypography();
    }

    private void onShowLoadingTimeout() {
        if ((currentState == LyricsManager.State.LOADING || pendingTrackTransition) && isLiveLyricsEnabled()) {
            loadingShown = true;
            loadingEnteredUptimeMs = SystemClock.uptimeMillis();
            lineTransitionStartMs = loadingEnteredUptimeMs;
            outgoingTransition.clearOutgoingFlag();
            syncVisibilityAndLoop();
        }
    }

    /** Builds crisp vector silhouettes for fetching, outro, and dynamic 3-note gap patterns. */
    private void buildNotePaths() {
        PlayerLiveLyricsArtwork.buildDoubleNotePathInto(notePath, noteSizePx);
        PlayerLiveLyricsArtwork.buildSingleNotePathInto(gapSingleNotePath, gapSingleNoteWidthPx, gapNoteSizePx);
        PlayerLiveLyricsArtwork.buildDoubleNotePathInto(gapDoubleNotePath, gapNoteSizePx);
    }

    /** @return Whether the live lyrics strip is turned on. */
    public static boolean isLiveLyricsEnabled() {
        return Settings.LYRICS_ENABLED.get() && Settings.LYRICS_PLAYER_LIVE.get();
    }

    /** Notifies attached views after any relevant lyrics setting changes. */
    public static void onSettingsChanged() {
        onSettingsChanged(false);
    }

    /**
     * Notifies attached views after a lyrics setting change, optionally forcing translation or
     * romanization to re-run when provider, language, or dictionary preferences changed.
     */
    public static void onSettingsChanged(boolean forceDerived) {
        Utils.runOnMainThreadNowOrLater(() -> {
            for (PlayerLiveLyricsView view : ACTIVE_VIEWS) {
                view.applyTypography();
                view.invalidateLineCache();
                view.rebuildGapIntervals(view.currentLyrics);
                view.launchFailedForTrack = false;
                view.updateLaunchAvailability();
                view.requestLayout();
                view.requestDerivedLyricsIfNeeded(forceDerived);
                view.syncVisibilityAndLoop();
            }
        });
    }

    /** Re-evaluates whether tapping the live lyrics strip can open the lyrics panel. */
    public static void refreshLaunchAvailability() {
        Utils.runOnMainThreadNowOrLater(() -> {
            for (PlayerLiveLyricsView view : ACTIVE_VIEWS) {
                view.updateLaunchAvailability();
            }
        });
    }

    private void applyTypography() {
        textPaint.setTypeface(LyricsFontManager.mainTypeface());
        textPaint.setFontVariationSettings(LyricsFontManager.mainFontVariation());
        updateForegroundTint();
    }

    private void updateForegroundTint() {
        int resolvedRgb = 0xFFFFFF;
        if (getParent() instanceof ViewGroup parent) {
            int bestAlpha = -1;
            for (int i = 0, count = parent.getChildCount(); i < count; i++) {
                View child = parent.getChildAt(i);
                if (child == this || child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                if (child instanceof TextView tv && tv.getText() != null && tv.getText().length() > 0) {
                    final int color = tv.getCurrentTextColor();
                    final int alpha = Color.alpha(color);
                    if (alpha > bestAlpha) {
                        bestAlpha = alpha;
                        resolvedRgb = color & 0x00FFFFFF;
                    }
                }
            }
        }
        if (foregroundRgb != resolvedRgb) {
            foregroundRgb = resolvedRgb;
            invalidate();
        }
        textPaint.setColor(tintColor(SUNG_BASE_ALPHA));
        chevronPaint.setColor(tintColor(CHEVRON_BASE_ALPHA));
    }

    private void updateLaunchAvailability() {
        if (!isLiveLyricsEnabled()) {
            return;
        }
        final boolean available = !launchFailedForTrack && LyricsPanelInstaller.canOpenLyricsTab();
        if (launchAvailable != available || isClickable() != available) {
            launchAvailable = available;
            setClickable(available);
            setFocusable(available);
            cachedLineIndex = -2;
            textLayout = null;
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            ACTIVE_VIEWS.add(this);
            applyTypography();
            updateLaunchAvailability();
            LyricsManager.getInstance().addListener(this);
            syncVisibilityAndLoop();
        } catch (Exception ex) {
            Logger.printException(() -> "PlayerLiveLyricsView attach failure", ex);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && isLiveLyricsEnabled()) {
            launchFailedForTrack = false;
            updateForegroundTint();
            updateLaunchAvailability();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(showLoadingRunnable);
        stopFrameLoop();
        clearDerivedLyrics();
        invalidateLineCache();
        outgoingTransition.release();
        revealFraction = 0f;
        revealStartFraction = 0f;
        revealTargetFraction = 0f;
        revealAnimStartMs = 0L;
        lastMeasuredHeight = -1;
        try {
            LyricsManager.getInstance().removeListener(this);
        } catch (Exception ex) {
            Logger.printException(() -> "PlayerLiveLyricsView detach failure", ex);
        }
        ACTIVE_VIEWS.remove(this);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            outgoingTransition.release();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateFrameLoop();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        setAlpha(isPressed() ? 0.65f : 1.0f);
    }

    @Override
    public void onTrackTransition(boolean transitioning) {
        if (pendingTrackTransition == transitioning) {
            return;
        }
        pendingTrackTransition = transitioning;
        if (transitioning) {
            launchFailedForTrack = false;
        }
        mainHandler.removeCallbacks(showLoadingRunnable);
        final long now = SystemClock.uptimeMillis();
        if (transitioning && isLiveLyricsEnabled()) {
            if (getVisibility() == View.VISIBLE) {
                captureOutgoingSnapshot(now, activeViewportWidth());
                loadingShown = true;
                loadingEnteredUptimeMs = now;
            } else {
                loadingShown = false;
                mainHandler.postDelayed(showLoadingRunnable, FETCH_DEBOUNCE_MS);
            }
        } else if (!transitioning && currentState != LyricsManager.State.LOADING) {
            if (getVisibility() == View.VISIBLE) {
                captureOutgoingSnapshot(now, activeViewportWidth());
            }
            loadingShown = false;
        }
        syncVisibilityAndLoop();
    }

    @Override
    public void onLyricsChanged(LyricsManager.State state, @Nullable Lyrics lyrics) {
        if (!isLiveLyricsEnabled()) {
            // Nothing to show. The settings listener rebuilds everything if the setting is turned on.
            pendingTrackTransition = false;
            loadingShown = false;
            mainHandler.removeCallbacks(showLoadingRunnable);
            currentState = state;
            currentLyrics = lyrics;
            return;
        }
        final boolean wasShowingLoading = isShowingLoading();
        pendingTrackTransition = false;
        mainHandler.removeCallbacks(showLoadingRunnable);

        final boolean stayLoading = wasShowingLoading && state == LyricsManager.State.LOADING;
        final long now = SystemClock.uptimeMillis();
        if (!stayLoading
                && getVisibility() == View.VISIBLE
                && shouldCaptureOnLyricsChange(state, lyrics)) {
            captureOutgoingSnapshot(now, activeViewportWidth());
        }

        if (lyrics != currentLyrics) {
            launchFailedForTrack = false;
        }
        currentState = state;
        currentLyrics = lyrics;
        indexHint = -1;
        invalidateLineCache();
        rebuildGapIntervals(lyrics);
        updateForegroundTint();
        updateLaunchAvailability();
        if (state == LyricsManager.State.LOADED && lyrics != null) {
            requestDerivedLyricsIfNeeded(false);
        } else {
            clearDerivedLyrics();
        }

        if (state == LyricsManager.State.LOADING) {
            if (stayLoading) {
                loadingShown = true;
            } else if (getVisibility() == View.VISIBLE) {
                // Keep row visible seamlessly when transitioning from an already-shown track.
                loadingShown = true;
                loadingEnteredUptimeMs = now;
            } else {
                loadingShown = false;
                mainHandler.postDelayed(showLoadingRunnable, FETCH_DEBOUNCE_MS);
            }
        } else {
            loadingShown = false;
        }

        syncVisibilityAndLoop();
    }

    private boolean shouldCaptureOnLyricsChange(LyricsManager.State newState, @Nullable Lyrics newLyrics) {
        if (lastFrameKind == KIND_NONE) {
            return false;
        }
        if (newState == LyricsManager.State.LOADED) {
            return newLyrics != currentLyrics
                    || lastFrameKind != KIND_LINE
                    || newLyrics == null
                    || !newLyrics.synced()
                    || newLyrics.isEmpty();
        }
        return true;
    }

    @Override
    public void onPlaybackStateChanged(boolean playing) {
        updateFrameLoop();
        invalidate();
    }

    @Override
    public void onTemporaryOffsetChanged(int offsetMs) {
        resetLineSweepGuard();
        invalidate();
    }

    private void rebuildGapIntervals(@Nullable Lyrics lyrics) {
        if (lyrics == null || !lyrics.synced() || lyrics.isEmpty() || !Settings.LYRICS_GAP_DOTS.get()) {
            gapIntervals = Collections.emptyList();
            return;
        }
        final List<LyricsLine> lines = lyrics.lines();
        final int size = lines.size();
        final boolean[] isSec = new boolean[size];
        for (int i = 0; i < size; i++) {
            isSec[i] = LyricsTextUtils.isSecondaryLine(lines.get(i));
        }
        final boolean[] rightAligned = LyricsLayoutCalc.rightAlignedLines(lines, isSec);
        gapIntervals = LyricsLayoutCalc.buildGapIntervals(lines, isSec, rightAligned);
    }

    private void releaseMaskCache() {
        if (maskCache != null) {
            maskCache.evictAndClear();
            maskCache = null;
        }
    }

    private void resetLineSweepGuard() {
        lastLineSweepIndex = -2;
        lastLineClockPos = Long.MIN_VALUE;
        lastLineSweepX = 0f;
    }

    private long getLiveClockPositionMs() {
        return LyricsManager.getInstance().getPositionMs();
    }

    private void invalidateLineCache() {
        cachedLineIndex = -2;
        cachedInGap = false;
        cachedGapNextStartMs = -1L;
        cachedGapKind = GapKind.NONE;
        cachedGapLabel = "";
        renderedText = "";
        textLayout = null;
        lineLeftPx = 0f;
        totalTextWidth = 0f;
        wordCount = 0;
        hasWordSweep = false;
        activeLineStartMs = 0L;
        activeLineEndMs = 0L;
        pendingInitialScroll = false;
        scrollX = 0f;
        lastFrameKind = KIND_NONE;
        resetLineSweepGuard();
        releaseMaskCache();
    }

    private void clearDerivedLyrics() {
        derivedGeneration++;
        derivedForLyrics = null;
        derivedMode = Settings.LIVE_MODE_ORIGINAL;
        liveTranslatedLines = null;
        liveRomanizedLines = null;
    }

    private void requestDerivedLyricsIfNeeded(boolean force) {
        final Lyrics lyrics = currentLyrics;
        final String mode = Settings.LYRICS_PLAYER_LIVE_MODE.get();
        if (!hasSyncedLyrics() || lyrics == null || Settings.LIVE_MODE_ORIGINAL.equals(mode)) {
            clearDerivedLyrics();
            return;
        }
        if (!force && lyrics == derivedForLyrics && mode.equals(derivedMode)) {
            return;
        }
        clearDerivedLyrics();
        derivedForLyrics = lyrics;
        derivedMode = mode;
        final int generation = derivedGeneration;
        final TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        if (track == null) {
            return;
        }
        final String source = lyrics.providerName() != null ? lyrics.providerName() : "";

        if (Settings.LIVE_MODE_TRANSLATED.equals(mode)) {
            LyricsTranslator.translate(track, lyrics, source,
                    (translatedLines, fromGoogle, fromAI, aiModel, skipped) -> {
                        if (generation != derivedGeneration
                                || currentLyrics != lyrics
                                || !Settings.LIVE_MODE_TRANSLATED.equals(Settings.LYRICS_PLAYER_LIVE_MODE.get())) {
                            return;
                        }
                        if (!skipped && translatedLines != null && hasAnyNonEmptyLine(translatedLines)) {
                            applyDerivedResult(translatedLines, null);
                        }
                    });
        } else if (Settings.LIVE_MODE_ROMANIZED.equals(mode)) {
            LyricsRomanizer.romanize(track, lyrics, source,
                    (romanizedLines, fromGoogle, fromAI, aiModel, perWord, localDict, fromEmbedded, skipped) -> {
                        if (generation != derivedGeneration
                                || currentLyrics != lyrics
                                || !Settings.LIVE_MODE_ROMANIZED.equals(Settings.LYRICS_PLAYER_LIVE_MODE.get())) {
                            return;
                        }
                        if (!skipped && hasAnyNonEmptyLyricsLine(romanizedLines)) {
                            applyDerivedResult(null, romanizedLines);
                        }
                    });
        }
    }

    private void applyDerivedResult(@Nullable List<String> translatedLines,
                                    @Nullable List<LyricsLine> romanizedLines) {
        final long now = SystemClock.uptimeMillis();
        if (getVisibility() == View.VISIBLE && lastFrameKind != KIND_NONE) {
            captureOutgoingSnapshot(now, activeViewportWidth());
        }
        liveTranslatedLines = translatedLines;
        liveRomanizedLines = romanizedLines;
        cachedLineIndex = -2;
        textLayout = null;
        wordCount = 0;
        hasWordSweep = false;
        releaseMaskCache();
        updateFrameLoop();
        invalidate();
    }

    private static boolean hasAnyNonEmptyLine(@Nullable List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyNonEmptyLyricsLine(@Nullable List<LyricsLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        for (LyricsLine line : lines) {
            if (line != null && line.text() != null && !line.text().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isShowingLoading() {
        return isLiveLyricsEnabled()
                && (currentState == LyricsManager.State.LOADING || pendingTrackTransition)
                && loadingShown;
    }

    private boolean hasSyncedLyrics() {
        return isLiveLyricsEnabled()
                && !pendingTrackTransition
                && currentState == LyricsManager.State.LOADED
                && currentLyrics != null
                && currentLyrics.synced()
                && !currentLyrics.isEmpty();
    }

    private boolean shouldShow() {
        return isShowingLoading() || hasSyncedLyrics();
    }

    private boolean isTransitionActive() {
        return lineTransitionStartMs > 0L
                && (SystemClock.uptimeMillis() - lineTransitionStartMs) < LINE_FADE_DURATION_MS;
    }

    private boolean isRevealAnimating() {
        return revealAnimStartMs > 0L;
    }

    private boolean isCollapsing() {
        return !shouldShow() && revealTargetFraction < 0.5f && revealFraction > 0.005f;
    }

    private int desiredHeightPx() {
        final Paint.FontMetrics fm = textPaint.getFontMetrics();
        final int lineHeight = Math.max(1, Math.round((float) Math.ceil(fm.descent - fm.ascent)));
        return topPaddingPx + lineHeight + bottomPaddingPx;
    }

    private void startRevealAnimation(float target, long now) {
        if (Math.abs(revealTargetFraction - target) < 0.01f
                && (revealAnimStartMs > 0L || Math.abs(revealFraction - target) <= 0.002f)) {
            return;
        }
        revealStartFraction = revealFraction;
        revealTargetFraction = target;
        revealAnimStartMs = now;
    }

    private void stepReveal(long now) {
        if (revealAnimStartMs <= 0L) {
            return;
        }
        final long duration = revealTargetFraction > revealStartFraction
                ? REVEAL_ENTER_DURATION_MS : REVEAL_EXIT_DURATION_MS;
        final long elapsed = Math.max(0L, now - revealAnimStartMs);
        if (elapsed >= duration) {
            revealFraction = revealTargetFraction;
            revealAnimStartMs = 0L;
            return;
        }
        final float eased = REVEAL_EASE.getInterpolation(elapsed / (float) duration);
        revealFraction = PlayerLiveLyricsSupport.computeRevealStep(
                revealStartFraction, revealTargetFraction, eased);
    }

    private void syncVisibilityAndLoop() {
        final boolean show = shouldShow();
        final boolean settingsActive = isLiveLyricsEnabled();
        final long now = SystemClock.uptimeMillis();

        if (show) {
            if (getVisibility() != View.VISIBLE) {
                revealFraction = 0f;
                revealStartFraction = 0f;
                revealTargetFraction = 1f;
                revealAnimStartMs = now;
                lineTransitionStartMs = now;
                outgoingTransition.clearOutgoingFlag();
                lastMeasuredHeight = 0;
                setVisibility(View.VISIBLE);
                requestLayout();
            } else if (revealTargetFraction < 0.5f || revealFraction < 0.998f) {
                startRevealAnimation(1f, now);
                requestLayout();
            }
        } else {
            if (getVisibility() != View.VISIBLE) {
                revealFraction = 0f;
                revealStartFraction = 0f;
                revealTargetFraction = 0f;
                revealAnimStartMs = 0L;
                lineTransitionStartMs = 0L;
                outgoingTransition.clearOutgoingFlag();
                lastFrameKind = KIND_NONE;
                lastMeasuredHeight = -1;
            } else if (!settingsActive || !isShown()
                    || (lastFrameKind == KIND_NONE && !outgoingTransition.hasOutgoingSnapshot())) {
                revealFraction = 0f;
                revealStartFraction = 0f;
                revealTargetFraction = 0f;
                revealAnimStartMs = 0L;
                lineTransitionStartMs = 0L;
                outgoingTransition.clearOutgoingFlag();
                lastFrameKind = KIND_NONE;
                lastMeasuredHeight = -1;
                setVisibility(View.GONE);
            } else {
                if (lastFrameKind != KIND_NONE) {
                    captureOutgoingSnapshot(now, activeViewportWidth());
                }
                startRevealAnimation(0f, now);
            }
        }

        updateFrameLoop();
        if (getVisibility() == View.VISIBLE) {
            invalidate();
        }
    }

    private void updateFrameLoop() {
        final boolean wantLoop = isAttachedToWindow()
                && getWindowVisibility() == View.VISIBLE
                && getVisibility() == View.VISIBLE
                && (isShowingLoading()
                        || isTransitionActive()
                        || isRevealAnimating()
                        || (hasSyncedLyrics() && LyricsManager.getInstance().isPlaying()));
        if (wantLoop && !frameLoopActive) {
            frameLoopActive = true;
            lastFrameUptimeMs = SystemClock.uptimeMillis();
            Choreographer.getInstance().postFrameCallback(this);
        } else if (!wantLoop && frameLoopActive) {
            stopFrameLoop();
        }
    }

    private void stopFrameLoop() {
        if (frameLoopActive) {
            frameLoopActive = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!frameLoopActive) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (revealAnimStartMs > 0L) {
            final int prevH = lastMeasuredHeight;
            stepReveal(now);
            final int nextH = Math.max(0, Math.round(desiredHeightPx() * revealFraction));
            if (nextH != prevH) {
                requestLayout();
            }
            if (revealAnimStartMs == 0L && revealTargetFraction < 0.5f && !shouldShow()) {
                revealFraction = 0f;
                lineTransitionStartMs = 0L;
                outgoingTransition.clearOutgoingFlag();
                lastFrameKind = KIND_NONE;
                lastMeasuredHeight = -1;
                setVisibility(View.GONE);
                stopFrameLoop();
                return;
            }
        }
        if (lineTransitionStartMs > 0L && (now - lineTransitionStartMs) >= LINE_FADE_DURATION_MS) {
            lineTransitionStartMs = 0L;
            outgoingTransition.clearOutgoingFlag();
        }
        if (isShown()) {
            invalidate();
        } else if (revealAnimStartMs == 0L && lineTransitionStartMs == 0L) {
            updateFrameLoop();
        }
        if (frameLoopActive) {
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int fullH = desiredHeightPx();
        final int desiredHeight = (getVisibility() == View.VISIBLE
                && revealTargetFraction >= 0.5f && revealFraction >= 0.998f)
                ? fullH
                : Math.max(0, Math.round(fullH * revealFraction));
        lastMeasuredHeight = desiredHeight;
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        final boolean show = shouldShow();
        final boolean collapsing = !show && isCollapsing();
        if (!show && !collapsing) {
            return;
        }

        final int viewportWidth = activeViewportWidth();
        if (viewportWidth <= 0 || getHeight() <= 0) {
            return;
        }

        final long now = SystemClock.uptimeMillis();
        final long deltaMs = lastFrameUptimeMs > 0 ? now - lastFrameUptimeMs : 16L;
        lastFrameUptimeMs = now;
        final float revealAlpha = PlayerLiveLyricsSupport.computeContentRevealAlpha(revealFraction, collapsing);

        if (collapsing) {
            final float outOffsetY = -transitionSlidePx * 0.5f * (1f - revealFraction);
            outgoingTransition.drawOutgoing(canvas, revealAlpha, outOffsetY);
            drawChevron(canvas, revealAlpha);
            return;
        }

        if (isShowingLoading()) {
            drawLoadingFrame(canvas, viewportWidth, now, revealAlpha);
            return;
        }

        final long positionMs = getLiveClockPositionMs();
        final boolean gapDotsEnabled = Settings.LYRICS_GAP_DOTS.get();
        final StateSnapshot snapshot = PlayerLiveLyricsSupport.resolveState(
                currentLyrics, gapDotsEnabled ? gapIntervals : null, positionMs, indexHint, gapDotsEnabled);
        if (!snapshot.visible()) {
            return;
        }

        if (snapshot.isGap()) {
            drawGapFrame(canvas, viewportWidth, now, positionMs, snapshot, revealAlpha);
            return;
        }
        drawLineFrame(canvas, viewportWidth, now, deltaMs, positionMs, snapshot.lineIndex(), revealAlpha);
    }

    private void drawLoadingFrame(Canvas canvas, int viewportWidth, long now, float revealAlpha) {
        if (lastFrameKind != KIND_NONE && lastFrameKind != KIND_LOADING) {
            captureOutgoingSnapshot(now, viewportWidth);
        }
        final float inProgress = computeTransitionInAlpha(now);
        final float inOffsetY = (1f - inProgress) * transitionSlidePx;
        drawOutgoingIfActive(canvas, inProgress, revealAlpha);
        drawFetchingState(canvas, viewportWidth, now, inProgress * revealAlpha, inOffsetY);
        recordFrameLoading(now);
        drawChevron(canvas, revealAlpha);
        finishTransitionLoopIfDone();
    }

    private void drawGapFrame(Canvas canvas, int viewportWidth, long now,
                              long positionMs, StateSnapshot snapshot, float revealAlpha) {
        final boolean gapChanged = !cachedInGap
                || cachedGapNextStartMs != snapshot.gapNextStartMs()
                || cachedGapKind != snapshot.gapKind()
                || (lastFrameKind != KIND_NONE && lastFrameKind != KIND_GAP);
        if (gapChanged) {
            if (lastFrameKind != KIND_NONE
                    && !(lastFrameKind == KIND_GAP
                            && cachedGapNextStartMs == snapshot.gapNextStartMs()
                            && cachedGapKind == snapshot.gapKind())) {
                captureOutgoingSnapshot(now, viewportWidth);
            }
            cachedInGap = true;
            cachedGapNextStartMs = snapshot.gapNextStartMs();
            cachedGapKind = snapshot.gapKind();
            cachedGapLabel = resolveGapLabel(snapshot);
            gapEnteredUptimeMs = now;
            scrollX = 0f;
        }
        final float inProgress = computeTransitionInAlpha(now);
        final float inOffsetY = (1f - inProgress) * transitionSlidePx;
        drawOutgoingIfActive(canvas, inProgress, revealAlpha);
        final long elapsedSinceEnterMs = Math.max(0L, now - gapEnteredUptimeMs);
        final DotPhase phase = PlayerLiveLyricsSupport.computeDotPhase(
                positionMs,
                snapshot.gapStartMs(),
                snapshot.gapEndMs(),
                snapshot.gapNextStartMs(),
                elapsedSinceEnterMs,
                GapLineView.GAP_ENTER_DURATION_MS
        );
        final boolean gapRightAligned = isGapRightAligned(snapshot);
        drawGapContent(canvas, viewportWidth, snapshot.gapKind(), phase, elapsedSinceEnterMs,
                gapRightAligned, cachedGapLabel, inProgress * revealAlpha, inOffsetY);
        recordFrameGap(now, positionMs, snapshot, gapRightAligned, cachedGapLabel);
        drawChevron(canvas, revealAlpha);
        finishTransitionLoopIfDone();
    }

    private String resolveGapLabel(StateSnapshot snapshot) {
        final GapKind kind = snapshot.gapKind();
        final String[] options;
        if (kind == GapKind.INTRO) {
            options = gapIntroOptions;
        } else if (kind == GapKind.INTERLUDE) {
            options = gapInterludeOptions;
        } else if (kind == GapKind.OUTRO) {
            options = gapOutroOptions;
        } else {
            return "";
        }
        final TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        final int seed = track != null
                ? track.hashCode()
                : (currentLyrics != null ? currentLyrics.lines().size() * 31 : 0);
        final int idx = PlayerLiveLyricsSupport.selectGapLabelIndex(
                seed, kind, snapshot.gapStartMs(), options.length);
        if (idx < 0 || idx >= options.length || options[idx] == null) {
            return "";
        }
        return options[idx];
    }

    private void drawLineFrame(Canvas canvas, int viewportWidth, long now,
                               long deltaMs, long positionMs, int lineIndex, float revealAlpha) {
        indexHint = lineIndex;
        if (cachedInGap || lineIndex != cachedLineIndex || textLayout == null
                || (lastFrameKind != KIND_NONE && lastFrameKind != KIND_LINE)) {
            if (lastFrameKind != KIND_NONE
                    && (lastFrameKind != KIND_LINE || lineIndex != cachedLineIndex)) {
                captureOutgoingSnapshot(now, viewportWidth);
            }
            prepareLine(lineIndex, viewportWidth);
        }

        if (textLayout == null || renderedText.isEmpty()) {
            final float inProgress = computeTransitionInAlpha(now);
            drawOutgoingIfActive(canvas, inProgress, revealAlpha);
            drawChevron(canvas, revealAlpha);
            finishTransitionLoopIfDone();
            return;
        }

        if (pendingInitialScroll) {
            pendingInitialScroll = false;
            scrollX = (lineIsRtl && totalTextWidth > viewportWidth)
                    ? Math.max(0f, totalTextWidth - viewportWidth)
                    : 0f;
        }

        final float inProgress = computeTransitionInAlpha(now);
        final float inOffsetY = (1f - inProgress) * transitionSlidePx;
        drawOutgoingIfActive(canvas, inProgress, revealAlpha);

        float sweepX = hasWordSweep
                ? PlayerLiveLyricsSupport.computeSweepX(
                        wordStartX, wordEndX, wordStartMs, wordEndMs, wordCount,
                        totalTextWidth, positionMs, lineIsRtl)
                : (lineIsRtl ? 0f : totalTextWidth);
        if (hasWordSweep) {
            final boolean playing = LyricsManager.getInstance().isPlaying();
            final boolean sameLine = lastLineSweepIndex == lineIndex;
            final boolean realBackwardSeek = positionMs < lastLineClockPos - 600L;
            if (!sameLine || !playing || realBackwardSeek) {
                lastLineSweepIndex = lineIndex;
                lastLineSweepX = sweepX;
            } else {
                sweepX = lineIsRtl
                        ? Math.min(lastLineSweepX, sweepX)
                        : Math.max(lastLineSweepX, sweepX);
                lastLineSweepX = sweepX;
            }
            lastLineClockPos = positionMs;
        }

        final float targetScrollX = hasWordSweep
                ? PlayerLiveLyricsSupport.computeTargetScrollX(
                        sweepX, totalTextWidth, viewportWidth, lineIsRtl)
                : PlayerLiveLyricsSupport.computeLineOnlyTargetScrollX(
                        positionMs, activeLineStartMs, activeLineEndMs,
                        totalTextWidth, viewportWidth, lineIsRtl);
        scrollX = (!LyricsManager.getInstance().isPlaying() && !isTransitionActive())
                ? targetScrollX
                : PlayerLiveLyricsSupport.stepScrollX(scrollX, targetScrollX, deltaMs);

        drawTextLayer(canvas, viewportWidth, sweepX, positionMs, now, scrollX,
                inProgress * revealAlpha, inOffsetY);
        recordFrameLine(now, positionMs, sweepX, scrollX);
        drawChevron(canvas, revealAlpha);
        finishTransitionLoopIfDone();
    }

    private boolean isGapRightAligned(StateSnapshot snapshot) {
        final Lyrics lyrics = currentLyrics;
        if (lyrics == null || lyrics.lines().isEmpty()) {
            return snapshot.gapRightAligned();
        }
        final int targetIdx = Math.max(0, Math.min(lyrics.lines().size() - 1, snapshot.lineIndex()));
        final LyricsLine line = lyrics.lines().get(targetIdx);
        final String mode = Settings.LYRICS_PLAYER_LIVE_MODE.get();
        if (Settings.LIVE_MODE_TRANSLATED.equals(mode)) {
            final String trans = LyricsSpanBuilder.translationAt(line, targetIdx, liveTranslatedLines);
            if (trans != null && !trans.isEmpty()) {
                return ScriptDetector.isLineRtl(trans);
            }
        } else if (Settings.LIVE_MODE_ROMANIZED.equals(mode)) {
            final String roma = LyricsSpanBuilder.romanizationForOnlyMode(line, targetIdx, liveRomanizedLines);
            if (roma != null && !roma.isEmpty()) {
                return ScriptDetector.isLineRtl(roma);
            }
        }
        return snapshot.gapRightAligned();
    }

    private void drawFetchingState(Canvas canvas, int viewportWidth, long now,
                                   float transitionAlpha, float offsetY) {
        final float elapsed = Math.max(0L, now - loadingEnteredUptimeMs);
        final float pulse = 0.5f + 0.5f * (float) Math.sin((elapsed / 1600.0) * Math.PI * 2.0);
        final float alphaFactor = 0.60f + 0.35f * pulse;
        final int alpha = Math.round(FETCHING_BASE_ALPHA * alphaFactor * PlayerLiveLyricsSupport.clamp01(transitionAlpha));
        if (alpha <= 0) {
            return;
        }

        final int fullHeight = desiredHeightPx();
        final int layerHeight = Math.max(fullHeight, getHeight());
        final float contentCenterY = topPaddingPx + (fullHeight - topPaddingPx - bottomPaddingPx) * 0.5f + offsetY;
        drawNoteLabelRow(canvas, viewportWidth, layerHeight, contentCenterY, fetchingText, alpha, false);
    }

    private void drawNoteLabelRow(Canvas canvas, int viewportWidth, int layerHeight,
                                  float contentCenterY, String label, int alpha, boolean rightAligned) {
        if (alpha <= 0 || viewportWidth <= 0) {
            return;
        }
        final float maxTextW = Math.max(0f, viewportWidth - noteSizePx - noteTextGapPx);
        final String trimmed = label == null ? "" : label;
        final CharSequence drawnText = trimmed.isEmpty() || maxTextW <= 2f
                ? ""
                : TextUtils.ellipsize(trimmed, textPaint, maxTextW, TextUtils.TruncateAt.END);
        final float textW = drawnText.length() > 0
                ? textPaint.measureText(drawnText, 0, drawnText.length())
                : 0f;
        final float noteLeft = rightAligned
                ? Math.max(0f, viewportWidth - noteSizePx)
                : 0f;
        final float textX = rightAligned
                ? Math.max(0f, noteLeft - noteTextGapPx - textW)
                : (noteSizePx + noteTextGapPx);

        final float noteTop = contentCenterY - noteSizePx * 0.5f;
        notePaint.setColor(tintColor(alpha));
        canvas.save();
        canvas.clipRect(0, 0, viewportWidth, layerHeight);
        canvas.translate(noteLeft, noteTop);
        canvas.drawPath(notePath, notePaint);
        canvas.restore();

        if (drawnText.length() > 0) {
            drawClippedLabel(canvas, viewportWidth, layerHeight, drawnText, textX, contentCenterY, alpha);
        }
    }

    private void drawClippedLabel(Canvas canvas, int viewportWidth, int layerHeight,
                                  CharSequence text, float textX, float contentCenterY, int alpha) {
        final Paint.FontMetrics fm = textPaint.getFontMetrics();
        final float baselineY = contentCenterY - (fm.ascent + fm.descent) * 0.5f;
        textPaint.setShader(null);
        textPaint.clearShadowLayer();
        textPaint.setColor(tintColor(alpha));
        canvas.save();
        canvas.clipRect(0, 0, viewportWidth, layerHeight);
        canvas.drawText(text, 0, text.length(), textX, baselineY, textPaint);
        canvas.restore();
        textPaint.setColor(tintColor(SUNG_BASE_ALPHA));
    }

    private void resetPreparedLine() {
        renderedText = "";
        textLayout = null;
        lineLeftPx = 0f;
        totalTextWidth = 0f;
        wordCount = 0;
        hasWordSweep = false;
        pendingInitialScroll = false;
        scrollX = 0f;
    }

    private StaticLayout buildTightSingleLineLayout(String text) {
        final int initialMeasureWidth = Math.max(1, (int) Math.ceil(textPaint.measureText(text)) + 32);
        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), textPaint, initialMeasureWidth)
                .setMaxLines(1)
                .setIncludePad(false)
                .build();
        final float measuredSpan = Math.max(0f, layout.getLineRight(0) - layout.getLineLeft(0));
        final int tightWidth = Math.max(1, (int) Math.ceil(measuredSpan));
        if (tightWidth == initialMeasureWidth) {
            return layout;
        }
        return StaticLayout.Builder
                .obtain(text, 0, text.length(), textPaint, tightWidth)
                .setMaxLines(1)
                .setIncludePad(false)
                .build();
    }

    private void prepareLine(int lineIndex, int viewportWidth) {
        releaseMaskCache();
        resetLineSweepGuard();
        cachedLineIndex = lineIndex;
        cachedInGap = false;
        cachedGapNextStartMs = -1L;

        final Lyrics lyrics = currentLyrics;
        if (lyrics == null || lineIndex < 0 || lineIndex >= lyrics.lines().size()) {
            resetPreparedLine();
            return;
        }

        final List<LyricsLine> lines = lyrics.lines();
        final LyricsLine line = lines.get(lineIndex);
        activeLineStartMs = Math.max(0L, line.startTimeMs());
        activeLineEndMs = PlayerLiveLyricsSupport.resolveLineEndMs(lines, lineIndex);

        final String mode = Settings.LYRICS_PLAYER_LIVE_MODE.get();
        final boolean wantWordSync = Settings.LYRICS_WORD_SYNC.get();
        String baseRaw = null;
        List<WordTiming> timings = Collections.emptyList();

        if (Settings.LIVE_MODE_TRANSLATED.equals(mode)) {
            baseRaw = LyricsSpanBuilder.translationAt(line, lineIndex, liveTranslatedLines);
        } else if (Settings.LIVE_MODE_ROMANIZED.equals(mode)) {
            final List<WordTiming> origTimings = LyricsWordTimingCalc.computeWordTimings(lines, lineIndex, false);
            final LyricsReadingCalc.Romanized romanized = LyricsReadingCalc.romanizedWithTimings(
                    line, lineIndex, liveRomanizedLines, origTimings);
            if (romanized.text() != null && !romanized.text().isEmpty()) {
                baseRaw = romanized.text();
                if (wantWordSync && romanized.spans() != null && !romanized.spans().isEmpty()) {
                    timings = romanized.spans();
                }
            }
        }

        if (baseRaw == null || baseRaw.isEmpty()) {
            baseRaw = LyricsTextUtils.displayLineText(line);
            timings = wantWordSync
                    ? LyricsWordTimingCalc.computeWordTimings(lines, lineIndex, false)
                    : Collections.emptyList();
        }

        final LyricsTextFormatter.Result formatted = LyricsPresentation.format(baseRaw);
        renderedText = PlayerLiveLyricsSupport.toSingleLine(formatted.text());
        if (renderedText.isEmpty()) {
            resetPreparedLine();
            return;
        }

        final StaticLayout layout = buildTightSingleLineLayout(renderedText);
        textLayout = layout;
        lineLeftPx = layout.getLineLeft(0);
        totalTextWidth = Math.max(0f, layout.getLineRight(0) - lineLeftPx);
        lineIsRtl = ScriptDetector.isLineRtl(renderedText)
                || layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT;
        lineMotionOff = lineIsRtl || LyricsTextUtils.containsJoiningScript(renderedText);

        if (viewportWidth > 0) {
            pendingInitialScroll = false;
            scrollX = (lineIsRtl && totalTextWidth > viewportWidth)
                    ? Math.max(0f, totalTextWidth - viewportWidth)
                    : 0f;
        } else {
            pendingInitialScroll = lineIsRtl;
            scrollX = 0f;
        }

        prepareWordTracks(layout, formatted, timings);
    }

    private void prepareWordTracks(StaticLayout layout, LyricsTextFormatter.Result formatted,
                                   List<WordTiming> timings) {
        if (timings.isEmpty()) {
            wordCount = 0;
            hasWordSweep = false;
            return;
        }

        final int textLen = renderedText.length();
        final List<WordTiming> mappedTimings = new ArrayList<>(timings.size());
        for (int i = 0; i < timings.size(); i++) {
            final WordTiming w = timings.get(i);
            final int startOff = Math.max(0, Math.min(textLen, formatted.offset(w.start())));
            final int endOff = Math.max(startOff, Math.min(textLen, formatted.offset(w.end())));
            if (endOff > startOff) {
                mappedTimings.add(new WordTiming(
                        startOff, endOff, w.startMs(), w.endMs(), w.romaji(),
                        w.hasTrailingSpace(), w.ruby(), w.averaged(), w.wordGroup()));
            }
        }
        final List<WordTiming> resolvedTimings = PlayerLiveLyricsSupport.fillUncoveredTextSpans(
                renderedText, mappedTimings);
        if (resolvedTimings.isEmpty()) {
            wordCount = 0;
            hasWordSweep = false;
            return;
        }

        wordCount = resolvedTimings.size();
        ensureWordCapacity(wordCount);

        for (int i = 0; i < wordCount; i++) {
            final WordTiming w = resolvedTimings.get(i);
            final int startOff = w.start();
            final int endOff = w.end();
            final float sx = layout.getPrimaryHorizontal(startOff) - lineLeftPx;
            final float ex = layout.getPrimaryHorizontal(endOff) - lineLeftPx;
            wordStartX[i] = sx;
            wordEndX[i] = ex;
            wordVisualL[i] = Math.min(sx, ex);
            wordVisualR[i] = Math.max(sx, ex);
            visualOrder[i] = i;
            wordStartMs[i] = w.startMs();
            wordEndMs[i] = w.endMs();

            wordElevTriggered[i] = false;
            wordElevFrom[i] = 0f;
            wordElevTarget[i] = 0f;
            wordElevStartUptimeMs[i] = -1L;
            wordElevCurrent[i] = 0f;

            final String wordText = startOff < endOff ? renderedText.substring(startOff, endOff) : "";
            wordLayouts[i] = buildWordLayout(wordText);
            populateHeavyWord(i, wordText, w);
        }
        Arrays.sort(visualOrder, 0, wordCount, (a, b) -> Float.compare(wordVisualL[a], wordVisualL[b]));

        // Rasterize ALPHA_8 masks via LyricsGlyphMaskCache for bilinear subpixel smooth motion.
        if (lineMotionOff) {
            maskCache = new ClusterMaskCache(1, maskPadPx);
            maskCache.obtain(0, textLayout);
        } else {
            maskCache = new ClusterMaskCache(wordCount, maskPadPx);
            for (int i = 0; i < wordCount; i++) {
                if (wordLayouts[i] != null) {
                    maskCache.obtain(i, wordLayouts[i]);
                    if (wordIsHeavy[i]) {
                        maskCache.obtainGlow(i, wordLayouts[i], wordGlowRadiusPx);
                    }
                }
            }
        }
        hasWordSweep = true;
    }

    private void populateHeavyWord(int idx, String wordText, WordTiming w) {
        final boolean heavy = PlayerLiveLyricsSupport.isHeavyWord(
                wordText, w.startMs(), w.endMs(), w.averaged(), lineMotionOff);
        wordIsHeavy[idx] = heavy;
        if (!heavy) {
            wordEmphasisTable[idx] = null;
            wordEmphasisSpanMs[idx] = 1f;
            wordAmplitude[idx] = 0f;
            wordGlowMax[idx] = 0f;
            return;
        }
        final long durMs = w.endMs() - w.startMs();
        final int nonSpace = PlayerLiveLyricsSupport.countNonWhitespaceChars(wordText);
        final float holdMs = PlayerLiveLyricsSupport.heavyHoldMs(durMs, nonSpace);
        final double sec = Math.max(0.001, durMs / 1000.0);
        wordEmphasisTable[idx] = WordSweepAnimator.buildEmphasisTable(durMs, Math.round(holdMs));
        wordEmphasisSpanMs[idx] = WordSweepAnimator.emphasisSpanMs(w.startMs(), w.endMs(), holdMs);
        wordAmplitude[idx] = WordSweepAnimator.emphasisAmplitude(sec);
        wordGlowMax[idx] = WordSweepAnimator.emphasisGlowMax(sec);
    }

    @Nullable
    private StaticLayout buildWordLayout(String wordText) {
        if (wordText.isEmpty()) {
            return null;
        }
        final int w = Math.max(1, (int) Math.ceil(textPaint.measureText(wordText)) + 8);
        return StaticLayout.Builder
                .obtain(wordText, 0, wordText.length(), textPaint, w)
                .setMaxLines(1)
                .setIncludePad(false)
                .build();
    }

    private void ensureWordCapacity(int count) {
        if (wordStartX.length >= count) {
            return;
        }
        wordLayouts = new StaticLayout[count];
        wordStartX = new float[count];
        wordEndX = new float[count];
        wordVisualL = new float[count];
        wordVisualR = new float[count];
        visualOrder = new Integer[count];
        wordStartMs = new long[count];
        wordEndMs = new long[count];
        wordElevTriggered = new boolean[count];
        wordElevFrom = new float[count];
        wordElevTarget = new float[count];
        wordElevStartUptimeMs = new long[count];
        wordElevCurrent = new float[count];
        wordIsHeavy = new boolean[count];
        wordEmphasisTable = new float[count][];
        wordEmphasisSpanMs = new float[count];
        wordAmplitude = new float[count];
        wordGlowMax = new float[count];
        drawLiftPx = new float[count];
        drawScale = new float[count];
        drawGlowAlpha = new float[count];
    }

    /**
     * Evaluates {@link WordSweepAnimator}-aligned motion for all words on the active line:
     * <ul>
     *   <li>{@code elevate glow}: sustained words (>= 1000ms, 1..7 chars, non-joining) run
     *       spring emphasis lift, subtle pulse scale, and soft glow fade.</li>
     *   <li>{@code elevate}: normal words gently elevate via 1000ms ease-in-out and hold.</li>
     *   <li>{@code elevate non glow}: RTL / joining scripts skip per-word glow/clip splitting.</li>
     * </ul>
     */
    private boolean stepWordMotions(long positionMs, long now, boolean smoothSweepEnabled) {
        if (!smoothSweepEnabled || !hasWordSweep || wordCount <= 0) {
            for (int i = 0; i < wordCount; i++) {
                drawLiftPx[i] = 0f;
                drawScale[i] = 1f;
                drawGlowAlpha[i] = 0f;
            }
            return false;
        }

        boolean anyGlow = false;
        for (int i = 0; i < wordCount; i++) {
            final long startMs = wordStartMs[i];

            if (!wordElevTriggered[i] && positionMs >= startMs) {
                wordElevTriggered[i] = true;
                wordElevFrom[i] = wordElevCurrent[i];
                wordElevTarget[i] = normalElevatePx;
                wordElevStartUptimeMs[i] = now;
            } else if (wordElevTriggered[i] && positionMs < startMs) {
                wordElevTriggered[i] = false;
                wordElevFrom[i] = wordElevCurrent[i];
                wordElevTarget[i] = 0f;
                wordElevStartUptimeMs[i] = now;
            }

            if (wordElevStartUptimeMs[i] >= 0L) {
                final long elapsed = Math.max(0L, now - wordElevStartUptimeMs[i]);
                if (elapsed >= PlayerLiveLyricsSupport.ELEV_ANIM_DURATION_MS) {
                    wordElevCurrent[i] = wordElevTarget[i];
                } else {
                    final float t = elapsed / (float) PlayerLiveLyricsSupport.ELEV_ANIM_DURATION_MS;
                    final float eased = ELEV_EASE.getInterpolation(t);
                    wordElevCurrent[i] = wordElevFrom[i] + (wordElevTarget[i] - wordElevFrom[i]) * eased;
                }
            } else {
                wordElevCurrent[i] = 0f;
            }

            if (wordIsHeavy[i] && wordEmphasisTable[i] != null) {
                if (positionMs < startMs) {
                    drawLiftPx[i] = 0f;
                    drawScale[i] = 1f;
                    drawGlowAlpha[i] = 0f;
                } else {
                    final float growFrac = PlayerLiveLyricsSupport.clamp01(
                            (positionMs - startMs) / wordEmphasisSpanMs[i]);
                    WordSweepAnimator.sampleEmphasisCached(wordEmphasisTable[i], growFrac, emphasisScratch);
                    final float amp = wordAmplitude[i];
                    final float pulse = emphasisScratch.pulse * amp;
                    drawLiftPx[i] = heavyLiftPx * emphasisScratch.lift + heavyRisePx * pulse;
                    drawScale[i] = 1f + HEAVY_SCALE_GAIN * pulse;
                    final float glow = growFrac > 0f && growFrac < 1f
                            ? PlayerLiveLyricsSupport.clamp01(wordGlowMax[i] * emphasisScratch.glow)
                            : 0f;
                    drawGlowAlpha[i] = glow;
                    if (glow > 0.005f) {
                        anyGlow = true;
                    }
                }
            } else {
                drawLiftPx[i] = wordElevCurrent[i];
                drawScale[i] = 1f;
                drawGlowAlpha[i] = 0f;
            }
        }
        return anyGlow;
    }

    private float computeTransitionInAlpha(long now) {
        if (lineTransitionStartMs <= 0L) {
            return 1f;
        }
        final long elapsed = Math.max(0L, now - lineTransitionStartMs);
        if (elapsed >= LINE_FADE_DURATION_MS) {
            lineTransitionStartMs = 0L;
            outgoingTransition.clearOutgoingFlag();
            return 1f;
        }
        return REVEAL_EASE.getInterpolation(elapsed / (float) LINE_FADE_DURATION_MS);
    }

    private void drawOutgoingIfActive(Canvas canvas, float inProgress, float revealAlpha) {
        outgoingTransition.drawOutgoingIfActive(canvas, inProgress, revealAlpha, transitionSlidePx);
    }

    private void finishTransitionLoopIfDone() {
        if (lineTransitionStartMs == 0L
                && revealAnimStartMs == 0L
                && !isShowingLoading()
                && !LyricsManager.getInstance().isPlaying()) {
            stopFrameLoop();
        }
    }

    private void recordFrameLoading(long now) {
        lastFrameKind = KIND_LOADING;
        lastCapturedNow = now;
    }

    private void recordFrameGap(long now, long positionMs, StateSnapshot snapshot,
                                boolean rightAligned, String label) {
        lastFrameKind = KIND_GAP;
        lastCapturedNow = now;
        lastPositionMs = positionMs;
        lastGapStartMs = snapshot.gapStartMs();
        lastGapEndMs = snapshot.gapEndMs();
        lastGapNextStartMs = snapshot.gapNextStartMs();
        lastGapRightAligned = rightAligned;
        lastGapKind = snapshot.gapKind();
        lastGapLabel = label;
    }

    private void recordFrameLine(long now, long positionMs, float sweepX, float activeScrollX) {
        lastFrameKind = KIND_LINE;
        lastCapturedNow = now;
        lastPositionMs = positionMs;
        lastSweepX = sweepX;
        lastScrollX = activeScrollX;
    }

    private void captureOutgoingSnapshot(long now, int viewportWidth) {
        final int height = desiredHeightPx();
        if (lastFrameKind == KIND_NONE || viewportWidth <= 0 || height <= 0 || !isShown()) {
            return;
        }
        outgoingTransition.capture(viewportWidth, height, now, lastCapturedNow,
                lineTransitionStartMs, LINE_FADE_DURATION_MS, transitionSlidePx, REVEAL_EASE,
                this::renderLastFrameContent);
        lineTransitionStartMs = now;
        lastFrameKind = KIND_NONE;
        updateFrameLoop();
    }

    private void renderLastFrameContent(@Nullable Canvas targetCanvas, int viewportWidth,
                                        long captureNow, float alphaScale, float offsetY) {
        if (targetCanvas == null) {
            return;
        }
        if (lastFrameKind == KIND_LOADING) {
            drawFetchingState(targetCanvas, viewportWidth, captureNow, alphaScale, offsetY);
        } else if (lastFrameKind == KIND_GAP) {
            final long captureElapsedMs = Math.max(0L, captureNow - gapEnteredUptimeMs);
            final DotPhase phase = PlayerLiveLyricsSupport.computeDotPhase(
                    lastPositionMs,
                    lastGapStartMs,
                    lastGapEndMs,
                    lastGapNextStartMs,
                    captureElapsedMs,
                    GapLineView.GAP_ENTER_DURATION_MS
            );
            drawGapContent(targetCanvas, viewportWidth, lastGapKind, phase, captureElapsedMs,
                    lastGapRightAligned, lastGapLabel, alphaScale, offsetY);
        } else if (lastFrameKind == KIND_LINE && textLayout != null && !renderedText.isEmpty()) {
            drawTextLayer(targetCanvas, viewportWidth, lastSweepX, lastPositionMs, captureNow,
                    lastScrollX, alphaScale, offsetY);
        }
    }

    private void drawTextLayer(Canvas canvas, int viewportWidth, float sweepX,
                               long positionMs, long now, float activeScrollX,
                               float alphaScale, float offsetY) {
        final StaticLayout layout = textLayout;
        if (layout == null) {
            return;
        }
        final int clampedAlpha = Math.round(255f * PlayerLiveLyricsSupport.clamp01(alphaScale));
        if (clampedAlpha <= 0) {
            return;
        }

        final int layerHeight = Math.max(desiredHeightPx(), getHeight());
        final int outerSave = clampedAlpha < 254
                ? canvas.saveLayerAlpha(0f, 0f, viewportWidth, layerHeight, clampedAlpha)
                : canvas.save();

        final float baseX = (lineIsRtl && totalTextWidth <= viewportWidth)
                ? (viewportWidth - totalTextWidth)
                : 0f;
        final boolean overflowLeft = activeScrollX > 0.5f;
        final boolean overflowRight = (totalTextWidth - activeScrollX) > (viewportWidth + 0.5f);
        final boolean needEdgeFade = overflowLeft || overflowRight;

        final int top = Math.round(topPaddingPx + Math.max(0,
                (desiredHeightPx() - topPaddingPx - bottomPaddingPx - layout.getHeight()) / 2) + offsetY);
        final int saveCount = needEdgeFade
                ? canvas.saveLayer(0f, 0f, viewportWidth, layerHeight, null)
                : canvas.save();
        canvas.clipRect(0, 0, viewportWidth, layerHeight);

        final boolean smoothSweep = Settings.LYRICS_WORD_SMOOTH_SWEEP.get();
        final boolean anyGlow = stepWordMotions(positionMs, now, smoothSweep);

        canvas.save();
        canvas.translate(baseX - activeScrollX, top);

        final ClusterMaskCache cache = maskCache;

        final int sungColor = tintColor(SUNG_BASE_ALPHA);
        final int unsungColor = tintColor(UNSUNG_BASE_ALPHA);
        if (hasWordSweep && cache != null) {
            if (!lineMotionOff && anyGlow) {
                drawMaskedHeavyGlow(canvas, cache, layout.getHeight());
            }
            if (lineMotionOff) {
                drawMaskedWholeLine(canvas, cache, layout, sweepX, sungColor, unsungColor, smoothSweep);
            } else {
                drawMaskedWords(canvas, cache, layout.getHeight(), sweepX, sungColor, unsungColor);
            }
        } else {
            textPaint.setShader(null);
            textPaint.setColor(tintColor(LINE_ONLY_BASE_ALPHA));
            canvas.translate(-lineLeftPx, 0f);
            layout.draw(canvas);
            textPaint.setColor(sungColor);
        }
        canvas.restore();

        if (needEdgeFade) {
            if (overflowLeft && fadeLeftPx > 0f) {
                fadePaint.setShader(getLeftFadeShader());
                canvas.drawRect(0f, 0f, fadeLeftPx, layerHeight, fadePaint);
            }
            if (overflowRight && fadeRightPx > 0f) {
                final float fadeStart = Math.max(0f, viewportWidth - fadeRightPx);
                if (fadeStart < viewportWidth) {
                    fadePaint.setShader(getRightFadeShader(viewportWidth));
                    canvas.drawRect(fadeStart, 0f, viewportWidth, layerHeight, fadePaint);
                }
            }
            fadePaint.setShader(null);
        }

        canvas.restoreToCount(saveCount);
        canvas.restoreToCount(outerSave);
    }

    private LinearGradient getLeftFadeShader() {
        if (leftFadeShader == null) {
            leftFadeShader = new LinearGradient(
                    0f, 0f, fadeLeftPx, 0f,
                    Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
        }
        return leftFadeShader;
    }

    private LinearGradient getRightFadeShader(int viewportWidth) {
        if (rightFadeShader == null || rightFadeShaderViewportWidth != viewportWidth) {
            rightFadeShaderViewportWidth = viewportWidth;
            final float fadeStart = Math.max(0f, viewportWidth - fadeRightPx);
            rightFadeShader = new LinearGradient(
                    fadeStart, 0f, viewportWidth, 0f,
                    Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        }
        return rightFadeShader;
    }

    private float lineElevateLift() {
        for (int i = 0; i < wordCount; i++) {
            if (wordElevCurrent[i] > 0.05f) {
                return wordElevCurrent[i];
            }
        }
        return 0f;
    }

    private void applyWordTransform(Canvas canvas, int idx, float pivotY) {
        final float wL = wordVisualL[idx];
        canvas.translate(wL, -drawLiftPx[idx]);
        final float s = drawScale[idx];
        if (Math.abs(s - 1f) > 0.002f) {
            canvas.scale(s, s, (wordVisualR[idx] - wL) * 0.5f, pivotY);
        }
    }

    private void drawMaskedHeavyGlow(Canvas canvas, ClusterMaskCache cache, int layoutHeight) {
        ColorFilter glowFilter = cache.glowTint.get(tintColor(255));
        final float pivotY = layoutHeight * 0.5f;
        for (int k = 0; k < wordCount; k++) {
            final int idx = visualOrder[k];
            final float glow = drawGlowAlpha[idx];
            if (glow <= 0.005f || wordLayouts[idx] == null) {
                continue;
            }
            Bitmap glowMask = cache.obtainGlow(idx, wordLayouts[idx], wordGlowRadiusPx);
            if (glowMask == null) {
                continue;
            }
            final float intensityNorm = Math.min(1f, glow / 0.45f);
            final float alpha = Math.min(1f, intensityNorm * (WORD_GLOW_PEAK_ALPHA / 255f));
            canvas.save();
            applyWordTransform(canvas, idx, pivotY);
            cache.drawMaskTinted(canvas, glowMask, cache.glowScaleOf(idx), glowFilter, alpha, true);
            canvas.restore();
        }
    }

    private void drawMaskedWholeLine(Canvas canvas, ClusterMaskCache cache, StaticLayout layout,
                                     float sweepX, int sungColor, int unsungColor, boolean smoothSweep) {
        Bitmap lineMask = cache.obtain(0, layout);
        if (lineMask == null) {
            return;
        }
        final float halfEdge = sweepSoftEdgePx * 0.5f;
        final float liftY = smoothSweep ? -lineElevateLift() : 0f;
        final float maskSweep = sweepX + lineLeftPx;
        final boolean notStartedYet = hasWordSweep
                && (lineIsRtl ? sweepX >= totalTextWidth : sweepX <= 0f);
        canvas.save();
        canvas.translate(-lineLeftPx, liftY);
        if (notStartedYet) {
            cache.drawMaskTinted(canvas, lineMask, cache.scaleOf(0),
                    cache.unlitTint.get(unsungColor), 1f, false);
        } else {
            wipeScratch.bandStart = lineIsRtl ? (maskSweep + halfEdge) : (maskSweep - halfEdge);
            wipeScratch.bandEnd = lineIsRtl ? (maskSweep - halfEdge) : (maskSweep + halfEdge);
            cache.drawMaskBand(canvas, lineMask, cache.scaleOf(0), wipeScratch, sungColor, unsungColor);
        }
        canvas.restore();
    }

    private void drawMaskedWords(Canvas canvas, ClusterMaskCache cache, int layoutHeight,
                                 float sweepX, int sungColor, int unsungColor) {
        ColorFilter pastFilter = cache.pastTint.get(sungColor);
        ColorFilter unlitFilter = cache.unlitTint.get(unsungColor);
        final float halfEdge = sweepSoftEdgePx * 0.5f;
        final float pivotY = layoutHeight * 0.5f;
        final boolean notStartedYet = hasWordSweep
                && (lineIsRtl ? sweepX >= totalTextWidth : sweepX <= 0f);

        for (int k = 0; k < wordCount; k++) {
            final int idx = visualOrder[k];
            final StaticLayout wLayout = wordLayouts[idx];
            if (wLayout == null) {
                continue;
            }
            Bitmap mask = cache.obtain(idx, wLayout);
            if (mask == null) {
                continue;
            }
            final float wL = wordVisualL[idx];
            final float wR = wordVisualR[idx];

            canvas.save();
            applyWordTransform(canvas, idx, pivotY);
            if (notStartedYet || sweepX <= wL - halfEdge) {
                cache.drawMaskTinted(canvas, mask, cache.scaleOf(idx), unlitFilter, 1f, false);
            } else if (sweepX >= wR + halfEdge) {
                cache.drawMaskTinted(canvas, mask, cache.scaleOf(idx), pastFilter, 1f, false);
            } else {
                final float localSweep = sweepX - wL;
                wipeScratch.bandStart = localSweep - halfEdge;
                wipeScratch.bandEnd = localSweep + halfEdge;
                cache.drawMaskBand(canvas, mask, cache.scaleOf(idx), wipeScratch, sungColor, unsungColor);
            }
            canvas.restore();
        }
    }

    private void drawGapContent(Canvas canvas, int viewportWidth, GapKind kind, DotPhase phase,
                                long elapsedSinceEnterMs, boolean rightAligned, String label,
                                float transitionAlpha, float offsetY) {
        final float clampedTransition = PlayerLiveLyricsSupport.clamp01(transitionAlpha);
        if (clampedTransition <= 0.005f || viewportWidth <= 0) {
            return;
        }
        final int fullHeight = desiredHeightPx();
        final int layerHeight = Math.max(fullHeight, getHeight());
        final float contentCenterY = topPaddingPx + (fullHeight - topPaddingPx - bottomPaddingPx) * 0.5f + offsetY;

        if (kind == GapKind.OUTRO) {
            final int outroAlpha = Math.round(GAP_TEXT_BASE_ALPHA * clampedTransition);
            drawNoteLabelRow(canvas, viewportWidth, layerHeight, contentCenterY,
                    label, outroAlpha, rightAligned);
            return;
        }

        final float enterEased = GapLineView.GAP_ENTER_EASE.getInterpolation(phase.enterFraction());
        final float enterAlpha = GapLineView.GAP_EASE.getInterpolation(phase.enterFraction());
        float exitFade = 1f;
        float groupAlpha = enterAlpha * clampedTransition;
        float groupScale = 0.58f + (1.0f - 0.58f) * enterEased;

        if (phase.exiting()) {
            final float ef = phase.exitFraction();
            if (ef <= 0.35f) {
                final float k = GapLineView.GAP_EASE.getInterpolation(ef / 0.35f);
                groupScale = phase.breatheScale() + (1.18f - phase.breatheScale()) * k;
            } else {
                final float k = (ef - 0.35f) / 0.65f;
                final float c = GapLineView.GAP_EASE.getInterpolation(k);
                groupScale = 1.18f * (1.0f - c);
                exitFade = Math.max(0f, 1.0f - c);
                groupAlpha *= exitFade;
            }
        } else {
            groupScale *= phase.breatheScale();
        }

        if (groupAlpha <= 0.01f || groupScale <= 0.01f) {
            return;
        }

        final float totalNotesWidth = 2f * gapSingleNoteWidthPx + gapNoteSizePx + 2f * gapNoteSpacingPx;
        final float headroom = Math.max(gapNoteSizePx * 0.18f, totalNotesWidth * 0.08f);
        final float notesStartX = rightAligned
                ? Math.max(0f, viewportWidth - totalNotesWidth - headroom)
                : headroom;
        if (notesStartX + totalNotesWidth > viewportWidth) {
            return;
        }

        final float[] fractions = {phase.dot0Fraction(), phase.dot1Fraction(), phase.dot2Fraction()};
        final float[] noteWidths = {gapSingleNoteWidthPx, gapNoteSizePx, gapSingleNoteWidthPx};
        final Path[] notePaths = {gapSingleNotePath, gapDoubleNotePath, gapSingleNotePath};
        final float groupCenterX = notesStartX + totalNotesWidth * 0.5f;

        canvas.save();
        canvas.clipRect(0, 0, viewportWidth, layerHeight);
        canvas.scale(groupScale, groupScale, groupCenterX, contentCenterY);
        float cursorX = notesStartX;
        for (int i = 0; i < 3; i++) {
            final float w = noteWidths[i];
            final int animIdx = rightAligned ? (2 - i) : i;
            final float eased = GapLineView.GAP_EASE.getInterpolation(fractions[animIdx]);
            final float noteAlpha = GapLineView.INTERVAL_SECONDARY_ALPHA
                    + (GapLineView.INTERVAL_PRIMARY_ALPHA - GapLineView.INTERVAL_SECONDARY_ALPHA) * eased;
            final int finalAlpha = Math.max(0, Math.min(255, Math.round(noteAlpha * groupAlpha)));
            final float waveY = PlayerLiveLyricsSupport.computeNoteWaveOffset(
                    animIdx, elapsedSinceEnterMs, gapWaveAmpPx,
                    phase.enterFraction(), phase.exiting(), phase.exitFraction());
            final float noteScale = 1f + GAP_NOTE_SCALE_GAIN * eased;
            dotPaint.setColor(tintColor(finalAlpha));

            canvas.save();
            canvas.translate(cursorX, contentCenterY - gapNoteSizePx * 0.5f + waveY);
            canvas.scale(noteScale, noteScale, w * 0.5f, gapNoteSizePx * 0.5f);
            canvas.drawPath(notePaths[i], dotPaint);
            canvas.restore();
            cursorX += w + gapNoteSpacingPx;
        }
        canvas.restore();

        final int labelAlpha = Math.round(GAP_TEXT_BASE_ALPHA * enterAlpha * exitFade * clampedTransition);
        if (labelAlpha > 0 && label != null && !label.isEmpty()) {
            final float maxTextW = rightAligned
                    ? Math.max(0f, notesStartX - gapTextGapPx)
                    : Math.max(0f, viewportWidth - (notesStartX + totalNotesWidth + gapTextGapPx));
            if (maxTextW > 4f) {
                final CharSequence drawnText = TextUtils.ellipsize(
                        label, textPaint, maxTextW, TextUtils.TruncateAt.END);
                if (drawnText.length() > 0) {
                    final float textX = rightAligned
                            ? Math.max(0f, notesStartX - gapTextGapPx
                                    - textPaint.measureText(drawnText, 0, drawnText.length()))
                            : (notesStartX + totalNotesWidth + gapTextGapPx);
                    drawClippedLabel(canvas, viewportWidth, layerHeight,
                            drawnText, textX, contentCenterY, labelAlpha);
                }
            }
        }
    }

    private void drawChevron(Canvas canvas, float alphaScale) {
        if (!launchAvailable) {
            return;
        }
        final int alpha = Math.round(CHEVRON_BASE_ALPHA * PlayerLiveLyricsSupport.clamp01(alphaScale));
        if (alpha <= 0) {
            return;
        }
        chevronPaint.setColor(tintColor(alpha));
        final float rightEdge = getWidth() - chevronRightInsetPx;
        final float leftEdge = rightEdge - chevronWidthPx;
        final float cy = topPaddingPx + (desiredHeightPx() - topPaddingPx - bottomPaddingPx) * 0.5f;
        final float halfH = chevronHeightPx * 0.5f;

        chevronPath.rewind();
        chevronPath.moveTo(leftEdge, cy - halfH);
        chevronPath.lineTo(rightEdge, cy);
        chevronPath.lineTo(leftEdge, cy + halfH);
        canvas.drawPath(chevronPath, chevronPaint);
    }
}
