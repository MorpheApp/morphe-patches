/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming;
import app.morphe.extension.music.patches.lyrics.ui.SpringEasing.LyricsScrollStagger;
import app.morphe.extension.music.patches.lyrics.ui.SpringEasing.LyricsSpring;

public final class LyricsScrollEngine {

    public interface Callback {
        boolean isUserScrolling();
        void setProgrammaticScrolling(boolean isProgrammatic);
        void onRecomputeAndScroll(int referenceIndex);
        default int computeReflowScrollTarget(@Nullable View targetRow) {
            return -1;
        }
    }

    public static final long LYRICS_SCROLL_LOCK_MS = 400L;
    public static final int LYRICS_FORCED_SCROLL_MS = 500;
    public static final float LYRICS_FAR_JUMP_VIEWPORTS = 2.0f;

    private static final long SUPPRESS_TAIL_MS = 250L;
    private static final long DEFAULT_FLIP_DURATION_MS = 400L;
    private static final float MIN_FLIP_DISTANCE_PX = 0.5f;
    private static final int SHIFT_RING = 32;

    private static final class FlipLayer {
        final float offset;
        final float startMs;
        final float waitMs;
        final float remainingMs;
        final float skipMs;
        final LyricsSpring spring;

        FlipLayer(float offset, float startMs, float waitMs, float remainingMs, float skipMs, LyricsSpring spring) {
            this.offset = offset;
            this.startMs = startMs;
            this.waitMs = waitMs;
            this.remainingMs = remainingMs;
            this.skipMs = skipMs;
            this.spring = spring;
        }

        float progress(float nowMs) {
            float elapsed = nowMs - startMs;
            if (elapsed <= waitMs) return 0f;
            float active = elapsed - waitMs;
            if (active >= remainingMs) return 1f;
            float f = active / Math.max(1f, remainingMs);
            return spring.value(skipMs + f * remainingMs);
        }

        boolean isDone(float nowMs) {
            return (nowMs - startMs) >= (waitMs + remainingMs);
        }

        float translation(float nowMs) {
            return offset * (1f - progress(nowMs));
        }
    }

    public static final class LyricsItemFlip {
        float lastTop = Float.NaN;
        long seenShiftSeq = 0L;
        final List<FlipLayer> layers = new ArrayList<>(4);

        public float translation(float nowMs) {
            if (layers.isEmpty()) return 0f;
            float sum = 0f;
            for (int i = layers.size() - 1; i >= 0; i--) {
                FlipLayer layer = layers.get(i);
                if (layer.isDone(nowMs)) {
                    layers.remove(i);
                } else {
                    sum += layer.translation(nowMs);
                }
            }
            return sum;
        }

        public boolean hasActiveLayers(float nowMs) {
            if (layers.isEmpty()) return false;
            for (int i = layers.size() - 1; i >= 0; i--) {
                if (layers.get(i).isDone(nowMs)) {
                    layers.remove(i);
                }
            }
            return !layers.isEmpty();
        }

        public void reset(float top) {
            lastTop = top;
            layers.clear();
        }

        public void add(float nowMs, float offset, float delayMs, @NonNull LyricsSpring spring, float elapsedMs) {
            if (Math.abs(offset) < MIN_FLIP_DISTANCE_PX) return;
            float skip = Math.max(0f, elapsedMs - delayMs);
            float wait = Math.max(0f, delayMs - elapsedMs);
            if (skip >= spring.totalMs) return;
            float remaining = Math.max(1f, spring.totalMs - skip);
            layers.add(new FlipLayer(offset, nowMs, wait, remaining, skip, spring));
        }

        /**
         * Replaces any in-flight spring layers with a single continuous spring layer
         * starting from the element's exact current visual offset (delta + inFlight).
         */
        public void replaceWithSingleSpring(float nowMs, float layoutDelta, float delayMs, @NonNull LyricsSpring spring) {
            float inFlight = translation(nowMs);
            float totalDelta = layoutDelta + inFlight;
            float effectiveDelay = delayMs;
            if (!layers.isEmpty()) {
                float remainingWait = 0f;
                for (int i = 0; i < layers.size(); i++) {
                    FlipLayer l = layers.get(i);
                    float elapsed = nowMs - l.startMs;
                    if (elapsed < l.waitMs) {
                        remainingWait = Math.max(remainingWait, l.waitMs - elapsed);
                    }
                }
                effectiveDelay = Math.min(delayMs, remainingWait);
            }
            layers.clear();
            if (Math.abs(totalDelta) < MIN_FLIP_DISTANCE_PX) return;
            add(nowMs, totalDelta, effectiveDelay, spring, 0f);
        }
    }

    private final ScrollView scrollView;
    private final LinearLayout linesContainer;
    private final View footerContainer;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, LyricsItemFlip> itemFlips = new WeakHashMap<>();

    private final long originNano = SystemClock.elapsedRealtimeNanos();
    private float suppressedUntilMs = 0f;
    @Nullable
    private LyricsScrollStagger currentStagger = null;
    private float txStartMs = 0f;
    private float txEndMs = 0f;
    private float txDeltaPx = 0f;
    private long lastDurationMs = DEFAULT_FLIP_DURATION_MS;
    private long lastTimeGapMs = 0L;

    private final int[] shiftIndex = new int[SHIFT_RING];
    private final float[] shiftDelta = new float[SHIFT_RING];
    private final long[] shiftSeqs = new long[SHIFT_RING];
    private long shiftSeq = 0L;
    private int pendingGapCloseRowIdx = -1;

    private int lastTotalRowsHeight = -1;
    private int lastContainerHeight = -1;
    private int lastViewportHeight = -1;
    private long lastReflowShiftSeq = 0L;
    private boolean pendingScrollSync = false;

    private boolean springScrollEnabled = true;
    private boolean isLocked = false;

    public void setSpringScrollEnabled(boolean enabled) {
        if (this.springScrollEnabled == enabled) {
            return;
        }
        this.springScrollEnabled = enabled;
        if (!enabled) {
            reset();
        } else {
            syncPlacedPositions(false, collectValidRows());
        }
    }

    public boolean isSpringScrollEnabled() {
        return springScrollEnabled;
    }
    private boolean hasPendingUpdate = false;
    private int pendingTargetIndex = -1;
    private int lastTargetRowIdx = -1;
    @Nullable
    private WeakReference<View> lastTargetRowRef = null;
    private boolean isPreDrawAttached = false;
    private boolean frameCallbackScheduled = false;

    @Nullable
    private ValueAnimator forcedScrollAnimator = null;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameCallbackScheduled = false;
            updateFlipTranslations();
        }
    };

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            handlePreDrawReflowAndSync();
            return true;
        }
    };

    private final Runnable unlockRunnable = new Runnable() {
        @Override
        public void run() {
            isLocked = false;
            if (hasPendingUpdate) {
                hasPendingUpdate = false;
                final int targetIdx = pendingTargetIndex;
                pendingTargetIndex = -1;
                if (!callback.isUserScrolling()) {
                    callback.onRecomputeAndScroll(targetIdx);
                }
            }
        }
    };

    public LyricsScrollEngine(@NonNull ScrollView scrollView,
                              @NonNull LinearLayout linesContainer,
                              @NonNull View footerContainer,
                              @NonNull Callback callback) {
        this.scrollView = scrollView;
        this.linesContainer = linesContainer;
        this.footerContainer = footerContainer;
        this.callback = callback;
        attach();
    }

    public float nowMs() {
        return (SystemClock.elapsedRealtimeNanos() - originNano) / 1_000_000f;
    }

    public boolean isAnimating() {
        return isLocked || (forcedScrollAnimator != null && forcedScrollAnimator.isRunning());
    }

    public long getScrollDurationMs() {
        return lastDurationMs;
    }

    public int getLastTargetRowIdx() {
        return lastTargetRowIdx;
    }

    @NonNull
    private LyricsSpring currentSpringFor(long durationMs) {
        return SpringEasing.springFor(durationMs, lastTimeGapMs, false);
    }

    public long computeStaggerLeadMs(long scrollLookAheadMs, int anchorPx) {
        if (scrollView.getHeight() <= 0 || linesContainer.getChildCount() <= 0) return 0L;
        int visibleTop = scrollView.getScrollY();
        List<View> validRows = collectValidRows();
        int stepsAboveAnchor = 0;
        for (int i = 0; i < validRows.size(); i++) {
            View row = validRows.get(i);
            if (row instanceof GapLineView || row == footerContainer) continue;
            int top = row.getTop();
            int size = row.getHeight();
            if (top + size > visibleTop && top + size / 2 < visibleTop + anchorPx) {
                stepsAboveAnchor++;
            }
        }
        int clampedSteps = Math.min(LyricsScrollStagger.MAX_STAGGER_STEPS, stepsAboveAnchor);
        long durationMs = LyricsRetiming.calculateScrollDuration(scrollLookAheadMs, false);
        float delayInc = LyricsRetiming.calculateScrollDelayIncrementFloat(durationMs, false);
        return (long) (clampedSteps * delayInc);
    }

    public void suppress(long forMs) {
        suppressedUntilMs = nowMs() + forMs + SUPPRESS_TAIL_MS;
        currentStagger = null;
        txDeltaPx = 0f;
        pendingScrollSync = false;
        pendingGapCloseRowIdx = -1;
    }

    public void recordLayoutShift(@Nullable View sourceView, float delta) {
        if (Math.abs(delta) < MIN_FLIP_DISTANCE_PX || sourceView == null) return;
        List<View> validRows = collectValidRows();
        int rowIdx = -1;
        for (int i = 0; i < validRows.size(); i++) {
            View r = validRows.get(i);
            if (r == sourceView || (r instanceof ViewGroup && ((ViewGroup) r).indexOfChild(sourceView) >= 0)) {
                rowIdx = i;
                break;
            }
        }
        if (rowIdx >= 0) {
            if (sourceView instanceof GapLineView && delta < 0f) {
                pendingGapCloseRowIdx = rowIdx;
            }
            recordLayoutShift(rowIdx, delta);
        }
    }

    public void recordLayoutShift(int index, float delta) {
        if (Math.abs(delta) < MIN_FLIP_DISTANCE_PX) return;
        long seq = ++shiftSeq;
        int slot = (int) (seq % SHIFT_RING);
        shiftIndex[slot] = index;
        shiftDelta[slot] = delta;
        shiftSeqs[slot] = seq;
    }

    private float consumeLayoutShift(@NonNull LyricsItemFlip flip, int index) {
        float sum = 0f;
        for (int i = 0; i < SHIFT_RING; i++) {
            if (shiftSeqs[i] > flip.seenShiftSeq && shiftIndex[i] < index) {
                sum += shiftDelta[i];
            }
        }
        flip.seenShiftSeq = shiftSeq;
        return sum;
    }

    private float remainingStaggerMs(int index, float now) {
        LyricsScrollStagger st = currentStagger;
        if (st == null || now >= txEndMs) return 0f;
        return Math.max(0f, st.steps(index) * st.delayIncrementMs - (now - txStartMs));
    }

    public void onInnerPlaced(@NonNull LyricsItemFlip flip, int rowIndex, float topInParent, boolean enabled) {
        float now = nowMs();
        if (!enabled || now < suppressedUntilMs) {
            flip.reset(topInParent);
            flip.seenShiftSeq = shiftSeq;
            return;
        }
        float last = flip.lastTop;
        flip.lastTop = topInParent;
        if (Float.isNaN(last)) {
            flip.seenShiftSeq = shiftSeq;
            return;
        }
        float delta = last - topInParent;
        if (Math.abs(delta) < MIN_FLIP_DISTANCE_PX) return;
        LyricsScrollStagger running = (currentStagger != null && now < txEndMs) ? currentStagger : null;
        long dur = (running != null) ? running.durationMs : lastDurationMs;
        flip.replaceWithSingleSpring(now, delta, remainingStaggerMs(rowIndex, now), currentSpringFor(dur));
    }

    public void arm(@NonNull LyricsScrollStagger stagger, float deltaPx) {
        lastDurationMs = stagger.durationMs;
        float now = nowMs();
        if (now < suppressedUntilMs) return;
        this.currentStagger = stagger;
        this.txStartMs = now;
        this.txDeltaPx = deltaPx;
        this.txEndMs = now
                + LyricsScrollStagger.MAX_STAGGER_STEPS * stagger.delayIncrementMs
                + currentSpringFor(stagger.durationMs).totalMs;
    }

    public void reset() {
        if (forcedScrollAnimator != null) {
            forcedScrollAnimator.cancel();
            forcedScrollAnimator = null;
        }
        if (frameCallbackScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallbackScheduled = false;
        }
        handler.removeCallbacks(unlockRunnable);
        isLocked = false;
        hasPendingUpdate = false;
        pendingTargetIndex = -1;
        lastTargetRowIdx = -1;
        lastTargetRowRef = null;
        lastTotalRowsHeight = -1;
        lastContainerHeight = -1;
        lastViewportHeight = -1;
        currentStagger = null;
        txDeltaPx = 0f;
        pendingScrollSync = false;
        pendingGapCloseRowIdx = -1;

        int scrollY = scrollView.getScrollY();
        int childCount = linesContainer.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = linesContainer.getChildAt(i);
            child.setTranslationY(0f);
            LyricsItemFlip flip = itemFlips.get(child);
            if (flip != null) {
                flip.reset(child.getTop() - scrollY);
                flip.seenShiftSeq = shiftSeq;
            }
            resetInnerFlips(child);
        }
    }

    public void attach() {
        if (!isPreDrawAttached) {
            linesContainer.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
            isPreDrawAttached = true;
        }
    }

    public void cleanup() {
        reset();
        if (isPreDrawAttached) {
            ViewTreeObserver vto = linesContainer.getViewTreeObserver();
            if (vto.isAlive()) {
                vto.removeOnPreDrawListener(preDrawListener);
            }
            isPreDrawAttached = false;
        }
    }

    private void resetInnerFlips(@NonNull View row) {
        if (row instanceof LyricsLineView llv) {
            llv.resetInnerFlips();
        } else if (row instanceof ViewGroup vg) {
            for (int c = 0; c < vg.getChildCount(); c++) {
                View child = vg.getChildAt(c);
                if (child instanceof LyricsLineView llv) {
                    llv.resetInnerFlips();
                }
            }
        }
    }

    public void cancelCascade() {
        reset();
    }

    @NonNull
    private LyricsItemFlip flipFor(@NonNull View view) {
        LyricsItemFlip flip = itemFlips.get(view);
        if (flip == null) {
            flip = new LyricsItemFlip();
            itemFlips.put(view, flip);
        }
        return flip;
    }

    @NonNull
    private List<View> collectValidRows() {
        int childCount = linesContainer.getChildCount();
        List<View> validRows = new ArrayList<>(childCount);
        for (int i = 0; i < childCount; i++) {
            View child = linesContainer.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            validRows.add(child);
        }
        return validRows;
    }

    @NonNull
    private int[] buildLinesBefore(@NonNull List<View> validRows) {
        int[] out = new int[validRows.size() + 1];
        for (int i = 0; i < validRows.size(); i++) {
            boolean isLine = !(validRows.get(i) instanceof GapLineView);
            out[i + 1] = out[i] + (isLine ? 1 : 0);
        }
        return out;
    }

    private void handlePreDrawReflowAndSync() {
        if (!springScrollEnabled || scrollView.getHeight() <= 0 || linesContainer.getChildCount() <= 0) return;

        int viewportH = scrollView.getHeight();
        int linesH = linesContainer.getHeight();
        if (shiftSeq == lastReflowShiftSeq && !pendingScrollSync && pendingGapCloseRowIdx < 0
                && linesH == lastContainerHeight && viewportH == lastViewportHeight) {
            return;
        }

        List<View> validRows = collectValidRows();
        int totalH = 0;
        for (int i = 0; i < validRows.size(); i++) {
            totalH += validRows.get(i).getHeight();
        }
        boolean sizeChanged = (lastTotalRowsHeight >= 0 && totalH != lastTotalRowsHeight)
                || (lastViewportHeight >= 0 && viewportH != lastViewportHeight)
                || (shiftSeq != lastReflowShiftSeq);
        boolean layoutDriven = (shiftSeq != lastReflowShiftSeq);
        boolean wasPendingScroll = pendingScrollSync;
        int gapCloseIdx = pendingGapCloseRowIdx;
        lastTotalRowsHeight = totalH;
        lastContainerHeight = linesH;
        lastViewportHeight = viewportH;
        lastReflowShiftSeq = shiftSeq;
        pendingScrollSync = false;
        pendingGapCloseRowIdx = -1;

        boolean userScrolling = callback.isUserScrolling();
        if (!userScrolling && gapCloseIdx >= 0 && (currentStagger == null || nowMs() >= txEndMs)) {
            // When a gap closes (including intro gap at scrollY == 0),
            // arm a staggered cascade for lines below the gap even when net scrollY delta is 0.
            long durationMs = lastDurationMs;
            float delayIncrementMs = LyricsRetiming.calculateScrollDelayIncrementFloat(durationMs, false);
            int[] linesBefore = buildLinesBefore(validRows);
            arm(
                    new LyricsScrollStagger(
                            true,
                            gapCloseIdx,
                            gapCloseIdx,
                            durationMs,
                            delayIncrementMs,
                            false,
                            linesBefore
                    ),
                    0f
            );
        }

        if ((sizeChanged || wasPendingScroll) && !userScrolling
                && (forcedScrollAnimator == null || !forcedScrollAnimator.isRunning())) {
            View targetRow = (lastTargetRowRef != null) ? lastTargetRowRef.get() : null;
            int resolvedRowIdx = -1;
            if (targetRow != null) {
                for (int i = 0; i < validRows.size(); i++) {
                    View r = validRows.get(i);
                    if (r == targetRow || (r instanceof ViewGroup && ((ViewGroup) r).indexOfChild(targetRow) >= 0)) {
                        resolvedRowIdx = i;
                        targetRow = r;
                        break;
                    }
                }
            }
            if (resolvedRowIdx < 0 && lastTargetRowIdx >= 0 && lastTargetRowIdx < validRows.size()) {
                resolvedRowIdx = lastTargetRowIdx;
                targetRow = validRows.get(resolvedRowIdx);
            }
            if (resolvedRowIdx >= 0 && targetRow != null) {
                lastTargetRowIdx = resolvedRowIdx;
                int desiredTargetY = callback.computeReflowScrollTarget(targetRow);
                if (desiredTargetY >= 0) {
                    int currentScrollY = scrollView.getScrollY();
                    float offset = (float) (desiredTargetY - currentScrollY);
                    if (Math.abs(offset) > MIN_FLIP_DISTANCE_PX) {
                        if (!layoutDriven && !wasPendingScroll) {
                            boolean scrollingDown = offset >= 0f;
                            long durationMs = lastDurationMs;
                            float delayIncrementMs = LyricsRetiming.calculateScrollDelayIncrementFloat(durationMs, false);
                            int[] linesBefore = buildLinesBefore(validRows);
                            arm(
                                    new LyricsScrollStagger(
                                            scrollingDown,
                                            resolvedRowIdx,
                                            resolvedRowIdx,
                                            durationMs,
                                            delayIncrementMs,
                                            false,
                                            linesBefore
                                    ),
                                    offset
                            );
                        }
                        callback.setProgrammaticScrolling(true);
                        scrollView.scrollTo(0, desiredTargetY);
                        callback.setProgrammaticScrolling(false);
                    } else if (!layoutDriven && !wasPendingScroll) {
                        long durationMs = lastDurationMs;
                        float delayIncrementMs = LyricsRetiming.calculateScrollDelayIncrementFloat(durationMs, false);
                        int[] linesBefore = buildLinesBefore(validRows);
                        arm(
                                new LyricsScrollStagger(
                                        true,
                                        resolvedRowIdx,
                                        resolvedRowIdx,
                                        durationMs,
                                        delayIncrementMs,
                                        false,
                                        linesBefore
                                ),
                                0f
                        );
                    }
                }
            }
        }

        syncPlacedPositions(!userScrolling, validRows);
    }

    private void syncPlacedPositions(boolean enabled, @NonNull List<View> validRows) {
        if (scrollView.getHeight() <= 0 || validRows.isEmpty()) return;
        float now = nowMs();
        int scrollY = scrollView.getScrollY();
        boolean anyActive = false;

        for (int i = 0; i < validRows.size(); i++) {
            View row = validRows.get(i);
            LyricsItemFlip flip = flipFor(row);
            float visualTop = row.getTop() - scrollY;

            if (!enabled) {
                boolean wasUnplaced = Float.isNaN(flip.lastTop);
                flip.lastTop = visualTop;
                if (wasUnplaced) {
                    flip.seenShiftSeq = shiftSeq;
                } else {
                    float shift = consumeLayoutShift(flip, i);
                    if (Math.abs(shift) >= MIN_FLIP_DISTANCE_PX) {
                        flip.replaceWithSingleSpring(now, -shift, 0f, currentSpringFor(lastDurationMs));
                    }
                }
            } else if (now < suppressedUntilMs) {
                flip.reset(visualTop);
                flip.seenShiftSeq = shiftSeq;
            } else {
                float last = flip.lastTop;
                flip.lastTop = visualTop;
                if (Float.isNaN(last)) {
                    flip.seenShiftSeq = shiftSeq;
                } else {
                    float delta = last - visualTop;
                    if (Math.abs(delta) >= MIN_FLIP_DISTANCE_PX) {
                        LyricsScrollStagger running = (currentStagger != null && now < txEndMs) ? currentStagger : null;
                        long dur = (running != null) ? running.durationMs : lastDurationMs;
                        flip.replaceWithSingleSpring(now, delta, remainingStaggerMs(i, now), currentSpringFor(dur));
                    }
                }
            }

            float trans = flip.translation(now);
            row.setTranslationY(trans);
            if (flip.hasActiveLayers(now)) {
                anyActive = true;
            }
            if (syncInnerFlips(row, i, enabled, now)) {
                anyActive = true;
            }
        }

        if (anyActive && !frameCallbackScheduled) {
            frameCallbackScheduled = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private boolean syncInnerFlips(@NonNull View row, int rowIndex, boolean enabled, float now) {
        boolean active = false;
        if (row instanceof LyricsLineView llv) {
            if (llv.syncInnerFlip(this, rowIndex, enabled, now)) {
                active = true;
            }
        } else if (row instanceof ViewGroup vg) {
            for (int c = 0; c < vg.getChildCount(); c++) {
                View child = vg.getChildAt(c);
                if (child instanceof LyricsLineView llv) {
                    if (llv.syncInnerFlip(this, rowIndex, enabled, now)) {
                        active = true;
                    }
                }
            }
        }
        return active;
    }

    private void updateFlipTranslations() {
        float now = nowMs();
        boolean anyActive = false;
        int childCount = linesContainer.getChildCount();

        for (int i = 0; i < childCount; i++) {
            View child = linesContainer.getChildAt(i);
            LyricsItemFlip flip = itemFlips.get(child);
            if (child.getVisibility() == View.GONE) {
                if (flip != null) flip.layers.clear();
                child.setTranslationY(0f);
                continue;
            }
            if (flip != null) {
                float trans = flip.translation(now);
                child.setTranslationY(trans);
                if (flip.hasActiveLayers(now)) {
                    anyActive = true;
                }
            }
            if (updateInnerFlipTranslations(child, now)) {
                anyActive = true;
            }
        }

        if (anyActive && !frameCallbackScheduled) {
            frameCallbackScheduled = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private boolean updateInnerFlipTranslations(@NonNull View row, float now) {
        boolean active = false;
        if (row instanceof LyricsLineView llv) {
            if (llv.updateInnerFlipTranslation(now)) {
                active = true;
            }
        } else if (row instanceof ViewGroup vg) {
            for (int c = 0; c < vg.getChildCount(); c++) {
                View child = vg.getChildAt(c);
                if (child instanceof LyricsLineView llv && child.getVisibility() == View.VISIBLE) {
                    if (llv.updateInnerFlipTranslation(now)) {
                        active = true;
                    }
                }
            }
        }
        return active;
    }

    public void scrollTo(int targetY,
                         @Nullable View referenceView,
                         int referenceIndex,
                         long lookAheadMs,
                         boolean forceInstant) {
        scrollTo(targetY, referenceView, referenceIndex, null, referenceIndex, referenceView, referenceIndex, lookAheadMs, lookAheadMs, forceInstant);
    }

    public void scrollTo(int targetY,
                         @Nullable View targetView,
                         int targetIndex,
                         @Nullable View referenceView,
                         int referenceIndex,
                         long lookAheadMs,
                         boolean forceInstant) {
        scrollTo(targetY, targetView, targetIndex, null, targetIndex, referenceView, referenceIndex, lookAheadMs, lookAheadMs, forceInstant);
    }

    public void scrollTo(int targetY,
                         @Nullable View targetView,
                         int targetIndex,
                         @Nullable View referenceView,
                         int referenceIndex,
                         long lookAheadMs,
                         long timeGapMs,
                         boolean forceInstant) {
        scrollTo(targetY, targetView, targetIndex, null, targetIndex, referenceView, referenceIndex, lookAheadMs, timeGapMs, forceInstant);
    }

    public void scrollTo(int targetY,
                         @Nullable View targetView,
                         int targetIndex,
                         @Nullable View targetEndView,
                         int targetEndIndex,
                         @Nullable View referenceView,
                         int referenceIndex,
                         long lookAheadMs,
                         long timeGapMs,
                         boolean forceInstant) {
        if (scrollView.getHeight() <= 0 || linesContainer.getChildCount() <= 0) {
            return;
        }
        if (callback.isUserScrolling()) {
            return;
        }

        this.lastTimeGapMs = Math.max(0L, timeGapMs);
        final int target = Math.max(0, targetY);
        final int currentScrollY = scrollView.getScrollY();
        final float requestedDelta = (float) (target - currentScrollY);

        List<View> validRows = collectValidRows();
        int totalRows = validRows.size();
        int resolvedTargetRowIdx = resolveRowIndex(validRows, targetView, targetIndex);
        if (resolvedTargetRowIdx >= 0 && resolvedTargetRowIdx < totalRows) {
            targetView = validRows.get(resolvedTargetRowIdx);
        }
        int resolvedEndRowIdx = (targetEndView != null || targetEndIndex != targetIndex)
                ? resolveRowIndex(validRows, targetEndView, targetEndIndex)
                : resolvedTargetRowIdx;
        if (resolvedEndRowIdx < resolvedTargetRowIdx) {
            resolvedEndRowIdx = resolvedTargetRowIdx;
        }

        if (forceInstant) {
            suppress(0L);
            reset();
            if (resolvedTargetRowIdx >= 0) {
                lastTargetRowIdx = resolvedTargetRowIdx;
                lastTargetRowRef = (targetView != null) ? new WeakReference<>(targetView) : null;
            }
            callback.setProgrammaticScrolling(true);
            scrollView.scrollTo(0, target);
            callback.setProgrammaticScrolling(false);
            if (springScrollEnabled) {
                syncPlacedPositions(false, validRows);
            }
            return;
        }

        if (Math.abs(requestedDelta) <= 1.0f) {
            if (resolvedTargetRowIdx >= 0) {
                lastTargetRowIdx = resolvedTargetRowIdx;
                lastTargetRowRef = (targetView != null) ? new WeakReference<>(targetView) : null;
            }
            return;
        }

        if (!springScrollEnabled) {
            cancelCascade();
            if (resolvedTargetRowIdx >= 0) {
                lastTargetRowIdx = resolvedTargetRowIdx;
                lastTargetRowRef = (targetView != null) ? new WeakReference<>(targetView) : null;
            }
            if (forcedScrollAnimator != null) {
                forcedScrollAnimator.cancel();
                forcedScrollAnimator = null;
            }
            final int duration = Math.min(400, Math.max(250, (int) (Math.abs(requestedDelta) * 0.5f)));
            ValueAnimator scrollAnimator = ValueAnimator.ofInt(currentScrollY, target);
            forcedScrollAnimator = scrollAnimator;
            scrollAnimator.setDuration(duration);
            scrollAnimator.setInterpolator(SpringEasing.SPRING_SCROLL_EASING);
            scrollAnimator.addUpdateListener(animation -> {
                int y = (Integer) animation.getAnimatedValue();
                callback.setProgrammaticScrolling(true);
                scrollView.scrollTo(0, y);
                callback.setProgrammaticScrolling(false);
            });
            scrollAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (forcedScrollAnimator == animation) {
                        forcedScrollAnimator = null;
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (forcedScrollAnimator == animation) {
                        forcedScrollAnimator = null;
                    }
                }
            });
            scrollAnimator.start();
            return;
        }

        if (forcedScrollAnimator != null && forcedScrollAnimator.isRunning()) {
            pendingTargetIndex = targetIndex;
            hasPendingUpdate = true;
            return;
        }

        final int viewportHeight = scrollView.getHeight();

        if (Math.abs(requestedDelta) > viewportHeight * LYRICS_FAR_JUMP_VIEWPORTS) {
            if (forcedScrollAnimator != null) {
                forcedScrollAnimator.cancel();
                forcedScrollAnimator = null;
            }
            suppress(LYRICS_FORCED_SCROLL_MS);
            reset();
            if (resolvedTargetRowIdx >= 0) {
                lastTargetRowIdx = resolvedTargetRowIdx;
                lastTargetRowRef = (targetView != null) ? new WeakReference<>(targetView) : null;
            }
            isLocked = true;
            handler.removeCallbacks(unlockRunnable);
            handler.postDelayed(unlockRunnable, LYRICS_FORCED_SCROLL_MS + 50L);

            ValueAnimator scrollAnimator = ValueAnimator.ofInt(currentScrollY, target);
            forcedScrollAnimator = scrollAnimator;
            scrollAnimator.setDuration(LYRICS_FORCED_SCROLL_MS);
            scrollAnimator.setInterpolator(SpringEasing.SPRING_SCROLL_EASING);
            scrollAnimator.addUpdateListener(animation -> {
                int y = (Integer) animation.getAnimatedValue();
                callback.setProgrammaticScrolling(true);
                scrollView.scrollTo(0, y);
                callback.setProgrammaticScrolling(false);
            });
            scrollAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    forcedScrollAnimator = null;
                    isLocked = false;
                    syncPlacedPositions(false, collectValidRows());
                    if (hasPendingUpdate) {
                        hasPendingUpdate = false;
                        final int targetIdx = pendingTargetIndex;
                        pendingTargetIndex = -1;
                        if (!callback.isUserScrolling()) {
                            callback.onRecomputeAndScroll(targetIdx);
                        }
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    forcedScrollAnimator = null;
                    isLocked = false;
                }
            });
            scrollAnimator.start();
            return;
        }

        if (totalRows == 0) {
            return;
        }

        for (int i = 0; i < totalRows; i++) {
            View row = validRows.get(i);
            LyricsItemFlip flip = flipFor(row);
            if (Float.isNaN(flip.lastTop)) {
                flip.lastTop = row.getTop() - currentScrollY;
                flip.seenShiftSeq = shiftSeq;
            }
        }

        int targetRowIdx = resolvedTargetRowIdx;
        int refRowIdx = (lastTargetRowIdx >= 0)
                ? Math.max(0, Math.min(lastTargetRowIdx, totalRows - 1))
                : resolveRowIndex(validRows, referenceView, referenceIndex);
        if (refRowIdx < 0) {
            refRowIdx = targetRowIdx;
        }

        long durationMs = LyricsRetiming.calculateScrollDuration(lookAheadMs, false);
        float delayIncrementMs = LyricsRetiming.calculateScrollDelayIncrementFloat(durationMs, false);
        boolean scrollingDown;
        if (targetRowIdx > refRowIdx) {
            scrollingDown = true;
        } else if (targetRowIdx < refRowIdx) {
            scrollingDown = false;
        } else {
            scrollingDown = (requestedDelta >= 0f);
        }

        // staggerAfterIndex = Math.max(referenceIndex, lastActiveIndex, lastActiveGroupEndIndex)
        int staggerAfterRowIdx = scrollingDown
                ? Math.max(refRowIdx, Math.max(targetRowIdx, resolvedEndRowIdx))
                : refRowIdx;

        int[] linesBefore = buildLinesBefore(validRows);
        LyricsScrollStagger stagger = new LyricsScrollStagger(
                scrollingDown,
                refRowIdx,
                staggerAfterRowIdx,
                durationMs,
                delayIncrementMs,
                false,
                linesBefore
        );

        arm(stagger, requestedDelta);

        int beforeY = scrollView.getScrollY();
        callback.setProgrammaticScrolling(true);
        scrollView.scrollTo(0, target);
        int afterY = scrollView.getScrollY();
        callback.setProgrammaticScrolling(false);

        float consumed = (float) (afterY - beforeY);
        lastTargetRowIdx = targetRowIdx;
        lastTargetRowRef = (targetView != null) ? new WeakReference<>(targetView) : null;

        if (Math.abs(consumed) > MIN_FLIP_DISTANCE_PX || Math.abs(requestedDelta) > MIN_FLIP_DISTANCE_PX) {
            pendingScrollSync = true;
            linesContainer.invalidate();
            scrollView.invalidate();
        }
    }

    private int resolveRowIndex(@NonNull List<View> validRows, @Nullable View view, int fallbackIndex) {
        int totalRows = validRows.size();
        if (totalRows == 0) return -1;
        if (view != null) {
            for (int i = 0; i < totalRows; i++) {
                View row = validRows.get(i);
                if (row == view || (row instanceof ViewGroup && ((ViewGroup) row).indexOfChild(view) >= 0)) {
                    return i;
                }
            }
        }
        return Math.max(0, Math.min(fallbackIndex, totalRows - 1));
    }
}
