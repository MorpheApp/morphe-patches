/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3582
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.LocalQueuePatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.shared.PlayerType;

public final class LocalQueueSheet {

    public static void show(Context context) {
        try {
            new LocalQueueSheet(context).show();
        } catch (Exception ex) {
            Logger.printException(() -> "show failure", ex);
        }
    }

    private static final int REVEAL_COLOR = 0xFFD93025;
    private static final long SETTLE_MILLISECONDS = 160;
    private static final long SHIFT_MILLISECONDS = 120;
    private static final int DIALOG_ANIMATION_DURATION_MILLISECONDS = 300;
    private static final long EQUALIZER_CYCLE_MILLISECONDS = 900;
    /**
     * Rows that load a thumbnail before the list is laid out.
     */
    private static final int INITIAL_THUMBNAIL_ROWS = 8;
    private static final int AUTO_SCROLL_EDGE_DP = 56;
    private static final int AUTO_SCROLL_MAX_STEP_DP = 14;
    private static final float SWIPE_DISMISS_WIDTH_FRACTION = 0.35f;
    private static final int SWIPE_DISMISS_FLING_VELOCITY_DP = 700;

    private final Context context;
    private final int backgroundColor = ThemeUtils.getDialogBackgroundColor();
    private final int foregroundColor = ThemeUtils.getAppForegroundColor();
    private final int mutedColor = withAlpha(foregroundColor, 0.62f);
    private final int placeholderColor = withAlpha(foregroundColor, 0.12f);

    private SheetBottomDialog.SlideDialog dialog;
    private ScrollView scrollView;
    private LinearLayout list;
    private TextView countView;
    private TextView clearButton;

    private final List<Row> rows = new ArrayList<>();
    @Nullable
    private Row nowPlayingRow;

    private int interactions;
    private boolean renderPending;
    @Nullable
    private DragSession drag;

    private LocalQueueSheet(Context context) {
        this.context = context;
    }

    private void show() {
        SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog.createMainLayout(context, null);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Dim.dp20, Dim.dp8, Dim.dp12, Dim.dp4);

        TextView title = new TextView(context);
        title.setText(str("morphe_local_queue_sheet_title"));
        title.setTextColor(foregroundColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        header.addView(title);

        countView = new TextView(context);
        countView.setTextColor(mutedColor);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.setMarginStart(Dim.dp8);
        header.addView(countView, countParams);

        header.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));

        clearButton = new TextView(context);
        clearButton.setText(str("morphe_local_queue_clear"));
        clearButton.setTextColor(foregroundColor);
        clearButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clearButton.setTypeface(clearButton.getTypeface(), Typeface.BOLD);
        clearButton.setPadding(Dim.dp12, Dim.dp10, Dim.dp12, Dim.dp10);
        clearButton.setBackground(createRipple(Color.TRANSPARENT, Dim.dp(18)));
        clearButton.setOnClickListener(v -> LocalQueuePatch.clear());
        header.addView(clearButton);
        mainLayout.addView(header);

        scrollView = SheetBottomDialog.createCappedScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, Dim.dp12);
        scrollView.addView(list);
        scrollView.setOnScrollChangeListener((v, x, y, oldX, oldY) -> loadVisibleThumbnails());
        mainLayout.addView(scrollView);

        dialog = SheetBottomDialog.createSlideDialog(context, mainLayout, DIALOG_ANIMATION_DURATION_MILLISECONDS);
        render();

        LocalQueuePatch.setChangeListener(this::requestRender);
        LocalQueuePatch.setMetadataListener(this::onMetadata);
        dialog.setOnDismissListener(d -> {
            LocalQueuePatch.setChangeListener(null);
            LocalQueuePatch.setMetadataListener(null);
            scrollView.setOnScrollChangeListener(null);
            drag = null;
        });
        dialog.show();
    }

    private void requestRender() {
        if (interactions > 0) {
            renderPending = true;
            return;
        }
        render();
    }

    private void beginInteraction() {
        interactions++;
    }

    private void endInteraction() {
        interactions = Math.max(0, interactions - 1);
        if (interactions == 0 && renderPending) {
            renderPending = false;
            render();
        }
    }

    private void render() {
        renderPending = false;
        list.removeAllViews();
        rows.clear();
        nowPlayingRow = null;

        List<LocalQueuePatch.Item> items = LocalQueuePatch.getItems();

        countView.setText(items.isEmpty() ? "" : String.valueOf(items.size()));
        clearButton.setVisibility(!items.isEmpty() ? View.VISIBLE : View.GONE);

        String nowPlayingId = VideoInformation.getVideoId();
        if (!PlayerType.getCurrent().isNoneOrHidden() && !nowPlayingId.isEmpty()) {
            nowPlayingRow = createNowPlayingRow(nowPlayingId);
            list.addView(nowPlayingRow.root);
        }

        if (items.isEmpty()) {
            list.addView(createEmptyState());
        }

        for (int i = 0, size = items.size(); i < size; i++) {
            Row row = createRow(items.get(i), i);
            rows.add(row);
            list.addView(row.root);
        }

        scrollView.post(this::loadVisibleThumbnails);
    }

    private View createEmptyState() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(Dim.dp32, Dim.dp36, Dim.dp32, Dim.dp28);

        TextView title = new TextView(context);
        title.setText(str("morphe_local_queue_empty"));
        title.setTextColor(foregroundColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView hint = new TextView(context);
        hint.setText(str("morphe_local_queue_empty_hint"));
        hint.setTextColor(mutedColor);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Dim.dp6, 0, 0);
        layout.addView(hint);
        return layout;
    }

    private final class Row {
        final String videoId;
        final int index;
        /**
         * Null for the now playing row.
         */
        @Nullable
        final LocalQueuePatch.Item item;
        final SwipeRevealRow root;
        final LinearLayout foreground;
        final ImageView thumbnail;
        final TextView titleView;
        final TextView channelView;
        final View titleBar;
        final View channelBar;
        float shift;

        Row(String videoId, int index, @Nullable LocalQueuePatch.Item item,
            SwipeRevealRow root, LinearLayout foreground, ImageView thumbnail, TextView titleView,
            TextView channelView, View titleBar, View channelBar) {
            this.videoId = videoId;
            this.index = index;
            this.item = item;
            this.root = root;
            this.foreground = foreground;
            this.thumbnail = thumbnail;
            this.titleView = titleView;
            this.channelView = channelView;
            this.titleBar = titleBar;
            this.channelBar = channelBar;
        }

        void bindText(@Nullable String title, @Nullable String channel) {
            boolean hasTitle = title != null && !title.isEmpty();
            titleView.setText(hasTitle ? title : "");
            titleView.setVisibility(hasTitle ? View.VISIBLE : View.GONE);
            titleBar.setVisibility(hasTitle ? View.GONE : View.VISIBLE);

            boolean hasChannel = channel != null && !channel.isEmpty();
            channelView.setText(hasChannel ? channel : "");
            channelView.setVisibility(hasChannel ? View.VISIBLE : View.GONE);
            channelBar.setVisibility(hasChannel ? View.GONE : View.VISIBLE);
        }
    }

    private Row createRow(LocalQueuePatch.Item item, int index) {
        return buildRow(item.videoId, index, item);
    }

    private Row createNowPlayingRow(String videoId) {
        return buildRow(videoId, -1, null);
    }

    private Row buildRow(String videoId, int index, @Nullable LocalQueuePatch.Item item) {
        final boolean nowPlaying = item == null;
        final int rowColor = nowPlaying ? blend(backgroundColor, foregroundColor, 0.08f) : backgroundColor;

        SwipeRevealRow root = new SwipeRevealRow(context);
        FrameLayout reveal = new FrameLayout(context);
        reveal.setBackgroundColor(REVEAL_COLOR);
        reveal.setVisibility(View.INVISIBLE);
        TrashIconView trash = new TrashIconView(context);
        trash.setContentDescription(str("morphe_local_queue_remove"));
        FrameLayout.LayoutParams trashParams = new FrameLayout.LayoutParams(Dim.dp24, Dim.dp24,
                Gravity.CENTER_VERTICAL | Gravity.END);
        trashParams.setMarginEnd(Dim.dp28);
        reveal.addView(trash, trashParams);
        root.addView(reveal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout foreground = new LinearLayout(context);
        foreground.setOrientation(LinearLayout.HORIZONTAL);
        foreground.setGravity(Gravity.CENTER_VERTICAL);
        foreground.setPadding(Dim.dp16, Dim.dp8, Dim.dp4, Dim.dp8);
        foreground.setBackground(createRipple(rowColor, 0));
        root.addView(foreground, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout thumbnailFrame = new FrameLayout(context);
        thumbnailFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Dim.dp8);
            }
        });
        thumbnailFrame.setClipToOutline(true);
        thumbnailFrame.setBackgroundColor(placeholderColor);
        ImageView thumbnail = new ImageView(context);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnailFrame.addView(thumbnail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (nowPlaying) {
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(Dim.dp24, Dim.dp(18),
                    Gravity.BOTTOM | Gravity.START);
            badgeParams.setMargins(Dim.dp6, 0, 0, Dim.dp6);
            thumbnailFrame.addView(new EqualizerView(context), badgeParams);
        }
        foreground.addView(thumbnailFrame, new LinearLayout.LayoutParams(Dim.dp(104), Dim.dp(58)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textsParams.setMarginStart(Dim.dp12);
        textsParams.setMarginEnd(Dim.dp4);
        foreground.addView(texts, textsParams);

        if (nowPlaying) {
            TextView label = new TextView(context);
            label.setText(str("morphe_local_queue_now_playing"));
            label.setTextColor(mutedColor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTypeface(label.getTypeface(), Typeface.BOLD);
            texts.addView(label);
        }

        TextView titleView = new TextView(context);
        titleView.setTextColor(foregroundColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView);
        View titleBar = createPlaceholderBar(Dim.dp(150), Dim.dp12);
        texts.addView(titleBar);

        TextView channelView = new TextView(context);
        channelView.setTextColor(mutedColor);
        channelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        channelView.setMaxLines(1);
        channelView.setEllipsize(TextUtils.TruncateAt.END);
        channelView.setPadding(0, Dim.dp2, 0, 0);
        texts.addView(channelView);
        View channelBar = createPlaceholderBar(Dim.dp(90), Dim.dp10);
        texts.addView(channelBar);

        Row row = new Row(videoId, index, item, root, foreground, thumbnail, titleView,
                channelView, titleBar, channelBar);
        if (nowPlaying) {
            row.bindText(VideoInformation.getVideoTitle(), VideoInformation.getChannelName());
            return row;
        }

        row.bindText(item.title, item.author);

        DragHandleView handle = new DragHandleView(context, mutedColor);
        handle.setContentDescription(str("morphe_local_queue_reorder"));
        handle.setOnTouchListener((v, event) -> onHandleTouch(row, event));
        foreground.addView(handle, new LinearLayout.LayoutParams(Dim.dp(44), Dim.dp(44)));

        foreground.setOnClickListener(v -> {
            dialog.dismiss();
            LocalQueuePatch.playItem(row.index);
        });
        root.configure(foreground, reveal, () -> LocalQueuePatch.remove(row.index));

        return row;
    }

    private View createPlaceholderBar(int width, int height) {
        View bar = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(placeholderColor);
        shape.setCornerRadius(Dim.dp4);
        bar.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = Dim.dp4;
        bar.setLayoutParams(params);
        return bar;
    }

    private void onMetadata(String videoId) {
        for (Row row : rows) {
            if (row.videoId.equals(videoId) && row.item != null) {
                row.bindText(row.item.title, row.item.author);
            }
        }
    }

    private void loadVisibleThumbnails() {
        final int height = scrollView.getHeight();
        final int margin = height / 2;
        final int top = scrollView.getScrollY() - margin;
        final int bottom = scrollView.getScrollY() + height + margin;

        if (nowPlayingRow != null) {
            bindThumbnail(nowPlayingRow);
        }
        for (Row row : rows) {
            boolean visible = height == 0
                    ? row.index < INITIAL_THUMBNAIL_ROWS
                    : row.root.getBottom() >= top && row.root.getTop() <= bottom;
            if (visible) {
                bindThumbnail(row);
            }
        }
    }

    private void bindThumbnail(Row row) {
        if (row.thumbnail.getDrawable() != null) return;

        Bitmap bitmap = QueueThumbnails.get(row.videoId);
        if (bitmap != null) {
            row.thumbnail.setImageBitmap(bitmap);
        } else {
            QueueThumbnails.load(row.videoId, this::onThumbnailLoaded);
        }
    }

    private void onThumbnailLoaded(String videoId) {
        if (!dialog.isShowing()) return;

        Bitmap bitmap = QueueThumbnails.get(videoId);
        if (bitmap == null) return;

        if (nowPlayingRow != null && nowPlayingRow.videoId.equals(videoId)) {
            nowPlayingRow.thumbnail.setImageBitmap(bitmap);
        }
        for (Row row : rows) {
            if (row.videoId.equals(videoId)) {
                row.thumbnail.setImageBitmap(bitmap);
            }
        }
    }

    private final class DragSession {
        final Row row;
        final int origin;
        final float[] tops;
        final float[] heights;
        final float downRawY;
        final int startScroll;
        float lastRawY;
        int target;
        final Runnable autoScroll = new Runnable() {
            @Override
            public void run() {
                if (drag != DragSession.this) return;

                int[] location = new int[2];
                scrollView.getLocationOnScreen(location);
                final float edge = Dim.dp(AUTO_SCROLL_EDGE_DP);
                final float top = location[1];
                final float bottom = top + scrollView.getHeight();
                int step = 0;
                if (lastRawY < top + edge) {
                    step = -Math.round((top + edge - lastRawY) / edge * Dim.dp(AUTO_SCROLL_MAX_STEP_DP));
                } else if (lastRawY > bottom - edge) {
                    step = Math.round((lastRawY - (bottom - edge)) / edge * Dim.dp(AUTO_SCROLL_MAX_STEP_DP));
                }
                if (step != 0) {
                    scrollView.scrollBy(0, step);
                    updateDrag(DragSession.this);
                }
                scrollView.postOnAnimation(this);
            }
        };

        DragSession(Row row, float downRawY) {
            this.row = row;
            this.origin = row.index;
            this.downRawY = downRawY;
            this.lastRawY = downRawY;
            this.startScroll = scrollView.getScrollY();
            this.target = row.index;
            tops = new float[rows.size()];
            heights = new float[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                tops[i] = rows.get(i).root.getTop();
                heights[i] = rows.get(i).root.getHeight();
            }
        }
    }

    private boolean onHandleTouch(Row row, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (drag != null || row.root.getParent() == null) return false;

                row.root.getParent().requestDisallowInterceptTouchEvent(true);
                beginInteraction();
                drag = new DragSession(row, event.getRawY());
                row.foreground.setElevation(Dim.dp8);
                row.root.setScaleX(1.02f);
                row.root.setScaleY(1.02f);
                scrollView.postOnAnimation(drag.autoScroll);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (drag == null || drag.row != row) return false;

                drag.lastRawY = event.getRawY();
                updateDrag(drag);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drag == null || drag.row != row) return false;

                finishDrag(drag);
                return true;

            default:
                return false;
        }
    }

    private void updateDrag(DragSession session) {
        final Row dragged = session.row;
        final int count = rows.size();
        final int origin = session.origin;
        final int last = count - 1;

        float finger = (session.lastRawY - session.downRawY) + (scrollView.getScrollY() - session.startScroll);
        final float minY = session.tops[0] - session.tops[origin];
        final float maxY = session.tops[last] + session.heights[last] - session.heights[origin] - session.tops[origin];
        finger = Math.max(minY, Math.min(finger, maxY));
        dragged.root.setTranslationY(finger);

        final float center = session.tops[origin] + finger + session.heights[origin] / 2f;
        float[] others = new float[Math.max(0, last)];
        int n = 0;
        for (int j = 0; j <= last; j++) {
            if (j != origin) {
                others[n++] = session.tops[j] + session.heights[j] / 2f;
            }
        }
        session.target = dropIndex(center, others);

        final float draggedHeight = session.heights[origin];
        for (int j = 0; j <= last; j++) {
            if (j == origin) continue;

            float shift = 0;
            if (origin < j && j <= session.target) {
                shift = -draggedHeight;
            } else if (session.target <= j && j < origin) {
                shift = draggedHeight;
            }

            Row other = rows.get(j);
            if (other.shift != shift) {
                other.shift = shift;
                other.root.animate().translationY(shift).setDuration(SHIFT_MILLISECONDS).start();
            }
        }
    }

    private void finishDrag(DragSession session) {
        final int origin = session.origin;
        final int target = session.target;

        float settle = 0;
        if (target > origin) {
            for (int j = origin + 1; j <= target; j++) settle += session.heights[j];
        } else if (target < origin) {
            for (int j = target; j < origin; j++) settle -= session.heights[j];
        }

        session.row.root.animate()
                .translationY(settle)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SETTLE_MILLISECONDS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        session.row.root.animate().setListener(null);
                        for (Row row : rows) {
                            row.root.animate().cancel();
                            row.root.setTranslationY(0);
                            row.shift = 0;
                        }
                        session.row.foreground.setElevation(0);
                        if (drag == session) {
                            drag = null;
                        }
                        if (target != origin) {
                            LocalQueuePatch.moveTo(origin, target);
                            renderPending = true;
                        }
                        endInteraction();
                    }
                })
                .start();
    }

    private final class SwipeRevealRow extends FrameLayout {
        private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        /**
         * Null if the row cannot be swiped away.
         */
        @Nullable
        private View foreground;
        private View reveal;
        private Runnable onDismiss;
        private float downX;
        private float downY;
        private boolean swiping;
        @Nullable
        private VelocityTracker velocityTracker;

        SwipeRevealRow(Context context) {
            super(context);
        }

        void configure(View foreground, View reveal, Runnable onDismiss) {
            this.foreground = foreground;
            this.reveal = reveal;
            this.onDismiss = onDismiss;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (foreground == null) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    swiping = false;
                    recycleTracker();
                    velocityTracker = VelocityTracker.obtain();
                    velocityTracker.addMovement(event);
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (velocityTracker != null) velocityTracker.addMovement(event);
                    if (drag != null) return false;

                    final float dx = event.getRawX() - downX;
                    final float dy = event.getRawY() - downY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        swiping = true;
                        beginInteraction();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        reveal.setVisibility(View.VISIBLE);
                        foreground.setPressed(false);
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    recycleTracker();
                    return false;

                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!swiping) return super.onTouchEvent(event);

            if (velocityTracker != null) velocityTracker.addMovement(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    foreground.setTranslationX(dx);
                    foreground.setAlpha(1f - Math.min(0.5f, Math.abs(dx) / Math.max(1, getWidth())));
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float offset = foreground.getTranslationX();
                    float velocity = 0;
                    if (velocityTracker != null) {
                        velocityTracker.computeCurrentVelocity(1000);
                        velocity = velocityTracker.getXVelocity();
                    }
                    recycleTracker();
                    swiping = false;

                    final boolean far = Math.abs(offset) > getWidth() * SWIPE_DISMISS_WIDTH_FRACTION;
                    final boolean fling = Math.abs(velocity) > Dim.dp(SWIPE_DISMISS_FLING_VELOCITY_DP) && velocity * offset > 0;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && (far || fling)) {
                        dismiss(offset >= 0 ? 1 : -1);
                    } else {
                        foreground.animate().translationX(0).alpha(1f).setDuration(SETTLE_MILLISECONDS)
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        foreground.animate().setListener(null);
                                        reveal.setVisibility(View.INVISIBLE);
                                        endInteraction();
                                    }
                                }).start();
                    }
                    return true;

                default:
                    return true;
            }
        }

        private void dismiss(int direction) {
            foreground.animate().translationX(direction * getWidth()).setDuration(SETTLE_MILLISECONDS)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            foreground.animate().setListener(null);
                            collapse();
                        }
                    }).start();
        }

        private void collapse() {
            final int startHeight = getHeight();
            ValueAnimator animator = ValueAnimator.ofInt(startHeight, 0);
            animator.setDuration(SHIFT_MILLISECONDS);
            animator.addUpdateListener(a -> {
                ViewGroup.LayoutParams params = getLayoutParams();
                params.height = (int) a.getAnimatedValue();
                setLayoutParams(params);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    endInteraction();
                    onDismiss.run();
                }
            });
            animator.start();
        }

        private void recycleTracker() {
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }
    }

    private static final class DragHandleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DragHandleView(Context context, int color) {
            super(context);
            paint.setColor(color);
            paint.setStrokeWidth(2.2f * context.getResources().getDisplayMetrics().density);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float half = getWidth() * 0.2f;
            final float gap = getHeight() * 0.13f;
            for (int i = -1; i <= 1; i++) {
                canvas.drawLine(cx - half, cy + i * gap, cx + half, cy + i * gap, paint);
            }
        }
    }

    private static final class TrashIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        TrashIconView(Context context) {
            super(context);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * context.getResources().getDisplayMetrics().density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth();
            final float h = getHeight();
            path.reset();
            path.moveTo(w * 0.15f, h * 0.24f);
            path.lineTo(w * 0.85f, h * 0.24f);
            path.moveTo(w * 0.38f, h * 0.24f);
            path.lineTo(w * 0.38f, h * 0.12f);
            path.lineTo(w * 0.62f, h * 0.12f);
            path.lineTo(w * 0.62f, h * 0.24f);
            path.moveTo(w * 0.24f, h * 0.24f);
            path.lineTo(w * 0.29f, h * 0.88f);
            path.lineTo(w * 0.71f, h * 0.88f);
            path.lineTo(w * 0.76f, h * 0.24f);
            canvas.drawPath(path, paint);
        }
    }

    private static final class EqualizerView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        @Nullable
        private ValueAnimator animator;
        private float phase;

        EqualizerView(Context context) {
            super(context);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(EQUALIZER_CYCLE_MILLISECONDS);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(null);
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth();
            final float h = getHeight();
            paint.setColor(0x99000000);
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, h * 0.25f, h * 0.25f, paint);

            paint.setColor(Color.WHITE);
            final float barWidth = w * 0.16f;
            for (int i = 0; i < 3; i++) {
                float level = 0.35f + 0.65f * Math.abs((float) Math.sin((phase + i * 0.31f) * Math.PI * 2));
                float barHeight = (h - h * 0.4f) * level;
                float left = w * 0.2f + i * (barWidth + w * 0.1f);
                rect.set(left, h * 0.8f - barHeight, left + barWidth, h * 0.8f);
                canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint);
            }
        }
    }

    /**
     * Index a dragged row ends at, from the center of the dragged row and the centers of the other
     * rows in their current order.
     */
    private static int dropIndex(float draggedCenter, float[] otherCenters) {
        int before = 0;
        for (float center : otherCenters) {
            if (center < draggedCenter) {
                before++;
            }
        }
        return before;
    }

    private RippleDrawable createRipple(int color, int radius) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(color);
        content.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(foregroundColor, 0.16f)), content, null);
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int blend(int base, int over, float amount) {
        return Color.rgb(
                Math.round(Color.red(base) * (1 - amount) + Color.red(over) * amount),
                Math.round(Color.green(base) * (1 - amount) + Color.green(over) * amount),
                Math.round(Color.blue(base) * (1 - amount) + Color.blue(over) * amount));
    }
}
