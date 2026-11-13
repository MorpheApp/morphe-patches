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
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming;
import app.morphe.extension.music.patches.lyrics.ui.SpringEasing.LyricsScrollStagger;
import app.morphe.extension.music.patches.lyrics.ui.SpringEasing.LyricsSpring;

/**
 * Scrolls the lyrics with a spring, and moves every row from where it was drawn to where the
 * new layout put it (FLIP), each row a little later than the one before it. Rows also glide
 * when the layout above them changes, such as a gap or a background vocal opening or closing.
 */
final class LyricsScrollEngine {

    interface Callback {
        boolean isUserScrolling();

        void setProgrammaticScrolling(boolean isProgrammatic);

        void onRecomputeAndScroll(int referenceIndex);

        /** {@return where to scroll to keep this row in place after a reflow, or -1} */
        int computeReflowScrollTarget(@Nullable View targetRow);
    }

    private static final int FORCED_SCROLL_MS = 500;
    /** A scroll further than this many screens jumps instead of cascading every row. */
    private static final float FAR_JUMP_VIEWPORTS = 2.0f;
    private static final int PLAIN_SCROLL_MIN_MS = 250;
    private static final int PLAIN_SCROLL_MAX_MS = 400;

    private static final long SUPPRESS_TAIL_MS = 250L;
    private static final long DEFAULT_FLIP_DURATION_MS = 400L;
    private static final float MIN_FLIP_DISTANCE_PX = 0.5f;
    private static final int SHIFT_RING = 32;

    private record FlipLayer(float offset, float startMs, float waitMs, float remainingMs,
                             float skipMs, LyricsSpring spring) {

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

    static final class LyricsItemFlip {
        float lastTop = Float.NaN;
        long seenShiftSeq = 0L;
        final List<FlipLayer> layers = new ArrayList<>(4);

        float translation(float nowMs) {
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

        boolean hasActiveLayers(float nowMs) {
            if (layers.isEmpty()) return false;
            for (int i = layers.size() - 1; i >= 0; i--) {
                if (layers.get(i).isDone(nowMs)) {
                    layers.remove(i);
                }
            }
            return !layers.isEmpty();
        }

        void reset(float top) {
            lastTop = top;
            layers.clear();
        }

        private void add(float nowMs, float offset, float delayMs, LyricsSpring spring) {
            if (Math.abs(offset) < MIN_FLIP_DISTANCE_PX) return;
            float skip = Math.max(0f, (float) 0.0 - delayMs);
            float wait = Math.max(0f, delayMs - (float) 0.0);
            if (skip >= spring.totalMs) return;
            float remaining = Math.max(1f, spring.totalMs - skip);
            layers.add(new FlipLayer(offset, nowMs, wait, remaining, skip, spring));
        }

        /**
         * Replaces any in-flight spring layers with a single continuous spring layer
         * starting from the element's exact current visual offset (delta + inFlight).
         */
        void replaceWithSingleSpring(float nowMs, float layoutDelta, float delayMs, LyricsSpring spring) {
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
            add(nowMs, totalDelta, effectiveDelay, spring);
        }
    }

    private final ScrollView scrollView;
    private final LinearLayout linesContainer;
    private final View footerContainer;
    private final Callback callback;
    private final WeakHashMap<View, LyricsItemFlip> itemFlips = new WeakHashMap<>();

    private final long originNano = SystemClock.elapsedRealtimeNanos();
    private float suppressedUntilMs = 0f;
    @Nullable
    private LyricsScrollStagger currentStagger = null;
    private float txStartMs = 0f;
    private float txEndMs = 0f;
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
    private boolean hasPendingUpdate = false;
    private int pendingTargetIndex = -1;
    private int lastTargetRowIdx = -1;
    @Nullable
    private WeakReference<View> lastTargetRowRef = null;
    private boolean isPreDrawAttached = false;
    private boolean frameCallbackScheduled = false;

    @Nullable
    private ValueAnimator forcedScrollAnimator = null;

    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        frameCallbackScheduled = false;
        updateFlipTranslations();
    };

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = () -> {
        handlePreDrawReflowAndSync();
        return true;
    };

    LyricsScrollEngine(ScrollView scrollView, LinearLayout linesContainer,
                       View footerContainer, Callback callback) {
        this.scrollView = scrollView;
        this.linesContainer = linesContainer;
        this.footerContainer = footerContainer;
        this.callback = callback;
        attach();
    }

    void setSpringScrollEnabled(boolean enabled) {
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

    private float nowMs() {
        return (SystemClock.elapsedRealtimeNanos() - originNano) / 1_000_000f;
    }

    long getScrollDurationMs() {
        return lastDurationMs;
    }

    private LyricsSpring currentSpringFor(long durationMs) {
        return SpringEasing.springFor(durationMs, lastTimeGapMs);
    }

    private boolean isForcedScrollRunning() {
        return forcedScrollAnimator != null && forcedScrollAnimator.isRunning();
    }

    private void cancelForcedScroll() {
        if (forcedScrollAnimator != null) {
            forcedScrollAnimator.cancel();
            forcedScrollAnimator = null;
        }
    }

    private void programmaticScrollTo(int y) {
        callback.setProgrammaticScrolling(true);
        scrollView.scrollTo(0, y);
        callback.setProgrammaticScrolling(false);
    }

    private void rememberTarget(int rowIdx, @Nullable View targetView) {
        if (rowIdx >= 0) {
            lastTargetRowIdx = rowIdx;
            lastTargetRowRef = targetView != null ? new WeakReference<>(targetView) : null;
        }
    }

    /**
     * {@return how much later the line at the scroll anchor starts moving than the first line
     * on screen, so the scroll can start that much earlier}
     */
    long computeStaggerLeadMs(long scrollLookAheadMs, int anchorPx) {
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
        long durationMs = LyricsRetiming.calculateScrollDuration(scrollLookAheadMs);
        float delayInc = LyricsRetiming.calculateScrollDelayIncrement(durationMs);
        return (long) (clampedSteps * delayInc);
    }

    /** Stops rows from gliding for a while, for layout changes that should land in place. */
    void suppress(long forMs) {
        suppressedUntilMs = nowMs() + forMs + SUPPRESS_TAIL_MS;
        currentStagger = null;
        pendingScrollSync = false;
        pendingGapCloseRowIdx = -1;
    }

    /** Records that this view grew or shrank, so the rows below it glide to their new place. */
    void recordLayoutShift(View sourceView, float delta) {
        if (Math.abs(delta) < MIN_FLIP_DISTANCE_PX) return;
        final int rowIdx = indexOfRow(collectValidRows(), sourceView);
        if (rowIdx < 0) return;
        if (sourceView instanceof GapLineView && delta < 0f) {
            pendingGapCloseRowIdx = rowIdx;
        }
        long seq = ++shiftSeq;
        int slot = (int) (seq % SHIFT_RING);
        shiftIndex[slot] = rowIdx;
        shiftDelta[slot] = delta;
        shiftSeqs[slot] = seq;
    }

    private float consumeLayoutShift(LyricsItemFlip flip, int index) {
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

    void onInnerPlaced(LyricsItemFlip flip, int rowIndex, float topInParent, boolean enabled) {
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

    private void arm(LyricsScrollStagger stagger) {
        lastDurationMs = stagger.durationMs;
        float now = nowMs();
        if (now < suppressedUntilMs) return;
        this.currentStagger = stagger;
        this.txStartMs = now;
        this.txEndMs = now
                + LyricsScrollStagger.MAX_STAGGER_STEPS * stagger.delayIncrementMs
                + currentSpringFor(stagger.durationMs).totalMs;
    }

    /** Stops every running scroll and glide, and leaves each row where its layout puts it. */
    void reset() {
        cancelForcedScroll();
        if (frameCallbackScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallbackScheduled = false;
        }
        hasPendingUpdate = false;
        pendingTargetIndex = -1;
        lastTargetRowIdx = -1;
        lastTargetRowRef = null;
        lastTotalRowsHeight = -1;
        lastContainerHeight = -1;
        lastViewportHeight = -1;
        currentStagger = null;
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

    void attach() {
        if (!isPreDrawAttached) {
            linesContainer.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
            isPreDrawAttached = true;
        }
    }

    void cleanup() {
        reset();
        if (isPreDrawAttached) {
            ViewTreeObserver vto = linesContainer.getViewTreeObserver();
            if (vto.isAlive()) {
                vto.removeOnPreDrawListener(preDrawListener);
            }
            isPreDrawAttached = false;
        }
    }

    private static void resetInnerFlips(View row) {
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

    private LyricsItemFlip flipFor(View view) {
        LyricsItemFlip flip = itemFlips.get(view);
        if (flip == null) {
            flip = new LyricsItemFlip();
            itemFlips.put(view, flip);
        }
        return flip;
    }

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

    private static int[] buildLinesBefore(List<View> validRows) {
        int[] out = new int[validRows.size() + 1];
        for (int i = 0; i < validRows.size(); i++) {
            boolean isLine = !(validRows.get(i) instanceof GapLineView);
            out[i + 1] = out[i] + (isLine ? 1 : 0);
        }
        return out;
    }

    private void armStagger(List<View> validRows, boolean scrollingDown, int referenceIndex,
                            int staggerAfterIndex, long durationMs) {
        arm(new LyricsScrollStagger(scrollingDown, referenceIndex, staggerAfterIndex, durationMs,
                LyricsRetiming.calculateScrollDelayIncrement(durationMs),
                buildLinesBefore(validRows)));
    }

    private void handlePreDrawReflowAndSync() {
        if (!springScrollEnabled || scrollView.getHeight() <= 0 || linesContainer.getChildCount() <= 0)
            return;

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
        boolean layoutDriven = shiftSeq != lastReflowShiftSeq;
        boolean sizeChanged = layoutDriven
                || (lastTotalRowsHeight >= 0 && totalH != lastTotalRowsHeight)
                || (lastViewportHeight >= 0 && viewportH != lastViewportHeight);
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
            // A closing gap, even the intro gap at scroll position 0, cascades the lines below
            // it although the scroll position may not change.
            armStagger(validRows, true, gapCloseIdx, gapCloseIdx, lastDurationMs);
        }

        if ((sizeChanged || wasPendingScroll) && !userScrolling && !isForcedScrollRunning()) {
            View targetRow = lastTargetRowRef != null ? lastTargetRowRef.get() : null;
            int resolvedRowIdx = targetRow != null ? indexOfRow(validRows, targetRow) : -1;
            if (resolvedRowIdx < 0 && lastTargetRowIdx >= 0 && lastTargetRowIdx < validRows.size()) {
                resolvedRowIdx = lastTargetRowIdx;
            }
            if (resolvedRowIdx >= 0) {
                targetRow = validRows.get(resolvedRowIdx);
                lastTargetRowIdx = resolvedRowIdx;
                int desiredTargetY = callback.computeReflowScrollTarget(targetRow);
                if (desiredTargetY >= 0) {
                    float offset = desiredTargetY - scrollView.getScrollY();
                    final boolean moves = Math.abs(offset) > MIN_FLIP_DISTANCE_PX;
                    if (!layoutDriven && !wasPendingScroll) {
                        armStagger(validRows, !moves || offset >= 0f, resolvedRowIdx, resolvedRowIdx,
                                lastDurationMs);
                    }
                    if (moves) {
                        programmaticScrollTo(desiredTargetY);
                    }
                }
            }
        }

        syncPlacedPositions(!userScrolling, validRows);
    }

    private void syncPlacedPositions(boolean enabled, List<View> validRows) {
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

    private boolean syncInnerFlips(View row, int rowIndex, boolean enabled, float now) {
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

    private boolean updateInnerFlipTranslations(View row, float now) {
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

    void scrollTo(int targetY,
                  @Nullable View targetView,
                  int targetIndex,
                  @Nullable View referenceView,
                  int referenceIndex,
                  long lookAheadMs,
                  boolean forceInstant) {
        scrollTo(targetY, targetView, targetIndex, null, targetIndex, referenceView, referenceIndex,
                lookAheadMs, lookAheadMs, forceInstant);
    }

    /**
     * @param targetEndView The last row of the group of lines sung together that starts at the
     *                      target, which moves along with it.
     * @param referenceView The row the scroll comes from, which the delays count from.
     * @param timeGapMs     The pause before the target line, which sets the spring's damping.
     */
    void scrollTo(int targetY,
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
        final float requestedDelta = target - currentScrollY;

        List<View> validRows = collectValidRows();
        int totalRows = validRows.size();
        int targetRowIdx = resolveRowIndex(validRows, targetView, targetIndex);
        if (targetRowIdx >= 0) {
            targetView = validRows.get(targetRowIdx);
        }
        int resolvedEndRowIdx = (targetEndView != null || targetEndIndex != targetIndex)
                ? resolveRowIndex(validRows, targetEndView, targetEndIndex)
                : targetRowIdx;
        if (resolvedEndRowIdx < targetRowIdx) {
            resolvedEndRowIdx = targetRowIdx;
        }

        if (forceInstant) {
            suppress(0L);
            reset();
            rememberTarget(targetRowIdx, targetView);
            programmaticScrollTo(target);
            if (springScrollEnabled) {
                syncPlacedPositions(false, validRows);
            }
            return;
        }

        if (Math.abs(requestedDelta) <= 1.0f) {
            rememberTarget(targetRowIdx, targetView);
            return;
        }

        if (!springScrollEnabled) {
            reset();
            rememberTarget(targetRowIdx, targetView);
            final int duration = Math.min(PLAIN_SCROLL_MAX_MS,
                    Math.max(PLAIN_SCROLL_MIN_MS, (int) (Math.abs(requestedDelta) * 0.5f)));
            startScrollAnimator(currentScrollY, target, duration, false);
            return;
        }

        if (isForcedScrollRunning()) {
            pendingTargetIndex = targetIndex;
            hasPendingUpdate = true;
            return;
        }

        if (Math.abs(requestedDelta) > scrollView.getHeight() * FAR_JUMP_VIEWPORTS) {
            suppress(FORCED_SCROLL_MS);
            reset();
            rememberTarget(targetRowIdx, targetView);
            startScrollAnimator(currentScrollY, target, FORCED_SCROLL_MS, true);
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

        int refRowIdx = (lastTargetRowIdx >= 0)
                ? Math.max(0, Math.min(lastTargetRowIdx, totalRows - 1))
                : resolveRowIndex(validRows, referenceView, referenceIndex);
        if (refRowIdx < 0) {
            refRowIdx = targetRowIdx;
        }

        final boolean scrollingDown = targetRowIdx != refRowIdx
                ? targetRowIdx > refRowIdx
                : requestedDelta >= 0f;
        // Every line of the group sung together moves first, the lines after it follow.
        final int staggerAfterRowIdx = scrollingDown
                ? resolvedEndRowIdx
                : refRowIdx;
        armStagger(validRows, scrollingDown, refRowIdx, staggerAfterRowIdx,
                LyricsRetiming.calculateScrollDuration(lookAheadMs));

        programmaticScrollTo(target);
        rememberTarget(targetRowIdx, targetView);
        // The rows glide from where they were drawn on the next pre-draw.
        pendingScrollSync = true;
        linesContainer.invalidate();
        scrollView.invalidate();
    }

    /**
     * Scrolls with a plain animation instead of moving every row.
     *
     * @param resumePending Places the rows and runs a scroll asked for meanwhile once done.
     */
    private void startScrollAnimator(int from, int to, int durationMs, boolean resumePending) {
        cancelForcedScroll();
        ValueAnimator scrollAnimator = ValueAnimator.ofInt(from, to);
        forcedScrollAnimator = scrollAnimator;
        scrollAnimator.setDuration(durationMs);
        scrollAnimator.setInterpolator(SpringEasing.SPRING_SCROLL_EASING);
        scrollAnimator.addUpdateListener(animation ->
                programmaticScrollTo((Integer) animation.getAnimatedValue()));
        scrollAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (forcedScrollAnimator != animation) {
                    return;
                }
                forcedScrollAnimator = null;
                if (!resumePending || cancelled) {
                    return;
                }
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
        });
        scrollAnimator.start();
    }

    /** {@return the row that is this view or holds it, or -1} */
    private static int indexOfRow(List<View> validRows, View view) {
        for (int i = 0; i < validRows.size(); i++) {
            View row = validRows.get(i);
            if (row == view || (row instanceof ViewGroup group && group.indexOfChild(view) >= 0)) {
                return i;
            }
        }
        return -1;
    }

    private static int resolveRowIndex(List<View> validRows, @Nullable View view, int fallbackIndex) {
        final int totalRows = validRows.size();
        if (totalRows == 0) return -1;
        final int index = view != null ? indexOfRow(validRows, view) : -1;
        return index >= 0 ? index : Math.max(0, Math.min(fallbackIndex, totalRows - 1));
    }
}
