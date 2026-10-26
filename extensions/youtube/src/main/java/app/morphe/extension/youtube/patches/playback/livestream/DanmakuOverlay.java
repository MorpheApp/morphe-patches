/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback.livestream;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * A transparent layer that sits on top of the video player and scrolls
 * live chat messages across it, Niconico-style.
 *
 * The layer itself never consumes touch input, so play/pause, seeking and
 * the normal player controls keep working underneath it.
 */
@SuppressWarnings("unused")
public final class DanmakuOverlay extends FrameLayout {

    private static final int MAX_ON_SCREEN = 80;
    private static final int MAX_TEXT_LENGTH = 200;

    /** Minimum gap, in dp, kept behind the previous comment in a lane. */
    private static final float MIN_GAP_DP = 24f;
    /** How long a comment may wait for a lane before it is discarded. */
    private static final long MAX_WAIT_MS = 5_000L;
    /** Upper bound on comments waiting for a lane; excess is dropped. */
    private static final int MAX_WAITING = 50;
    private static final long RETRY_INTERVAL_MS = 200L;

    /** A chat message waiting for a lane to open up, with its arrival time. */
    private static final class WaitingDanmaku {
        final DanmakuMessage message;
        final long enqueuedAtMs;

        WaitingDanmaku(DanmakuMessage message, long enqueuedAtMs) {
            this.message = message;
            this.enqueuedAtMs = enqueuedAtMs;
        }
    }

    private final DanmakuLaneAllocator allocator;
    private final List<DanmakuMessage> pending = new ArrayList<>();
    private final List<WaitingDanmaku> waiting = new ArrayList<>();
    private final List<ObjectAnimator> animators = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean retryScheduled;

    private final Runnable retryWaitingRunnable = new Runnable() {
        @Override
        public void run() {
            retryScheduled = false;
            drainWaiting();
        }
    };

    private ViewGroup attachedParent;

    public DanmakuOverlay(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        allocator = new DanmakuLaneAllocator(Settings.LIVE_CHAT_DANMAKU_MAX_ROWS.get());
    }

    /** Attach this layer to a player container, moving it if it was elsewhere. */
    public void attachTo(ViewGroup parent) {
        if (parent == null) {
            return;
        }
        if (attachedParent == parent && getParent() == parent) {
            return;
        }
        if (getParent() instanceof ViewGroup oldParent) {
            oldParent.removeView(this);
        }
        attachedParent = parent;
        parent.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        bringToFront();
    }

    public void detach() {
        clear();
        if (getParent() instanceof ViewGroup oldParent) {
            oldParent.removeView(this);
        }
        attachedParent = null;
    }

    public boolean isAttached() {
        return getParent() instanceof ViewGroup;
    }

    public void clear() {
        mainHandler.removeCallbacks(retryWaitingRunnable);
        retryScheduled = false;
        waiting.clear();
        for (ObjectAnimator animator : new ArrayList<>(animators)) {
            animator.cancel();
        }
        animators.clear();
        pending.clear();
        allocator.reset();
        removeAllViews();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && !pending.isEmpty()) {
            List<DanmakuMessage> queued = new ArrayList<>(pending);
            pending.clear();
            for (DanmakuMessage message : queued) {
                spawn(message);
            }
        }
    }

    /** Queue or immediately render one chat message as danmaku. */
    public void spawn(DanmakuMessage message) {
        try {
            if (message == null || message.isEmpty()) {
                return;
            }
            if (!Settings.LIVE_CHAT_DANMAKU.get()) {
                return;
            }
            if (getChildCount() >= MAX_ON_SCREEN) {
                return; // Burst protection: drop instead of piling up.
            }
            if (getWidth() <= 0) {
                pending.add(message);
                return;
            }
            if (!trySpawnInternal(message)) {
                // No lane can take this comment yet without overlapping:
                // let it wait for a lane to open instead of dropping it.
                enqueueWaiting(message);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Danmaku spawn failure", ex);
        }
    }

    private void enqueueWaiting(DanmakuMessage message) {
        if (waiting.size() >= MAX_WAITING) {
            return; // Burst protection: the waiting room itself is full.
        }
        waiting.add(new WaitingDanmaku(message, System.currentTimeMillis()));
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (!retryScheduled && !waiting.isEmpty()) {
            retryScheduled = true;
            mainHandler.postDelayed(retryWaitingRunnable, RETRY_INTERVAL_MS);
        }
    }

    /**
     * Retry waiting comments in arrival order. A comment that has waited
     * longer than {@link #MAX_WAIT_MS} is discarded; one that still finds
     * no lane keeps waiting for the next retry.
     */
    private void drainWaiting() {
        if (waiting.isEmpty() || getWidth() <= 0) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        for (WaitingDanmaku entry : new ArrayList<>(waiting)) {
            if (nowMs - entry.enqueuedAtMs > MAX_WAIT_MS) {
                waiting.remove(entry); // Waited too long: discard.
            } else if (trySpawnInternal(entry.message)) {
                waiting.remove(entry);
            }
        }
        scheduleRetry();
    }

    /**
     * @return true if the message was handled (shown, or intentionally
     * skipped), false only when no lane can take it yet
     */
    private boolean trySpawnInternal(DanmakuMessage message) {
        int containerWidth = getWidth();
        int crossingSeconds = DanmakuLaneAllocator.clampCrossingSeconds(
                Settings.LIVE_CHAT_DANMAKU_CROSSING_SECONDS.get());
        int opacity = DanmakuLaneAllocator.clampOpacityPercent(Settings.LIVE_CHAT_DANMAKU_OPACITY.get());
        int columns = DanmakuLaneAllocator.clampColumns(Settings.LIVE_CHAT_DANMAKU_COLUMNS.get());
        allocator.setMaxRows(Settings.LIVE_CHAT_DANMAKU_MAX_ROWS.get());

        // Fullscreen-only is approximated by landscape: in portrait the
        // video area is not fullscreen, and danmaku would spill over the
        // chat and description below the player.
        if (Settings.LIVE_CHAT_DANMAKU_FULLSCREEN_ONLY.get() && getHeight() >= containerWidth) {
            return true;
        }

        String text = message.displayText(Settings.LIVE_CHAT_DANMAKU_SHOW_AUTHOR.get());
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        Context context = getContext();
        TextView view = new TextView(context);
        view.setText(text);
        // Text size is managed by column count: this many full-width
        // characters fit across the container width, so the size scales
        // proportionally between portrait and fullscreen widths.
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) containerWidth / columns);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.WHITE);
        view.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        view.setSingleLine(true);
        view.setClickable(false);
        view.setFocusable(false);
        view.setAlpha(opacity / 100f);

        if (message.superChat && Settings.LIVE_CHAT_DANMAKU_SUPERCHAT_HIGHLIGHT.get()) {
            int color = message.highlightColor != 0 ? message.highlightColor : 0xFFE91E63;
            view.setBackgroundColor(Color.argb(160, Color.red(color), Color.green(color), Color.blue(color)));
            int padding = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 4, context.getResources().getDisplayMetrics());
            view.setPadding(padding, padding, padding, padding);
        }

        int widthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        view.measure(widthSpec, heightSpec);
        int textWidth = Math.max(1, view.getMeasuredWidth());
        int textHeight = Math.max(1, view.getMeasuredHeight());

        int minGapPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, MIN_GAP_DP,
                context.getResources().getDisplayMetrics());
        int lane = allocator.chooseLane(
                textWidth, containerWidth, crossingSeconds, minGapPx, System.currentTimeMillis());
        if (lane < 0) {
            return false; // No lane qualifies yet: the caller lets it wait.
        }

        int lineHeight = textHeight + (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4, context.getResources().getDisplayMetrics());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = lane * lineHeight;
        addView(view, params);

        long duration = DanmakuLaneAllocator.travelTimeMs(containerWidth, textWidth, crossingSeconds);
        ObjectAnimator animator = ObjectAnimator.ofFloat(
                view, "translationX", containerWidth, -textWidth);
        animator.setDuration(duration);
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                animators.remove(animator);
                removeView(view);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                animators.remove(animator);
                removeView(view);
            }
        });
        animators.add(animator);
        animator.start();
        return true;
    }
}
