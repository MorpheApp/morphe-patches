/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4436
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.OfflineUi.ICON_BUTTON_DP;
import static app.morphe.extension.music.patches.downloads.OfflineUi.SEEK_BAR_TOUCH_DP;
import static app.morphe.extension.music.patches.downloads.OfflineUi.background;
import static app.morphe.extension.music.patches.downloads.OfflineUi.foreground;
import static app.morphe.extension.music.patches.downloads.OfflineUi.rippleColor;
import static app.morphe.extension.music.patches.downloads.OfflineUi.secondary;
import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.downloads.OfflinePlaybackService.RepeatMode;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.misc.MediaTime;

/**
 * Full screen page of the downloads screen, with the cover and controls of the playing track and
 * the queue it plays from. Rows of the queue are swiped away, or dragged by their handle.
 * <p>
 * A view inside {@link LocalDownloadsFragment} rather than a fragment of its own, for the same
 * reasons as {@link LocalPlaylistPage}.
 */
final class OfflinePlayerPage {

    private static final int ID_TOOLBAR_PARENT =
            ResourceUtils.getIdentifierOrThrow(ResourceType.ID, "morphe_toolbar_parent");

    private static final long OPEN_ANIMATION_MILLISECONDS = 260;
    private static final long CLOSE_ANIMATION_MILLISECONDS = 230;
    private static final float OPEN_ANIMATION_START_ALPHA = .3f;
    private static final float OPEN_INTERPOLATOR_FACTOR = 1.6f;
    private static final float CLOSE_INTERPOLATOR_FACTOR = 1.5f;
    /** The closed page rests this far down the screen, as a fraction of its height. */
    private static final float CLOSED_OFFSET = .85f;

    private static final int ARTWORK_PIXELS = 1024;
    private static final int SCRIM_TOP_COLOR = 0x55000000;
    private static final int PLAY_BUTTON_DP = 64;
    private static final int PLAY_BUTTON_PADDING_DP = 16;
    private static final int QUEUE_SCROLL_TOP_DP = 8;
    private static final int QUEUE_PADDING_DP = 8;

    /** Shuffle and repeat stay readable while they are off. */
    private static final float INACTIVE_BUTTON_ALPHA = .4f;
    private static final float PLAYED_ROW_ALPHA = .72f;

    /** A swipe has to be this much more sideways than vertical, or the list scrolls instead. */
    private static final float SWIPE_DIRECTION_RATIO = 1.25f;
    /** How far a row has to be swiped to be removed, as a fraction of its width. */
    private static final float SWIPE_REMOVE_FRACTION = .32f;
    /** How far a swiped row has to go to be as faded as it gets. */
    private static final float SWIPE_FADE_DISTANCE = .75f;
    private static final float SWIPE_MAX_FADE = .85f;
    private static final long SWIPE_ANIMATION_MILLISECONDS = 180;

    private static final int DRAG_ANIMATION_MILLISECONDS = 100;
    private static final int AUTO_SCROLL_EDGE_DP = 48;
    private static final int AUTO_SCROLL_STEP_DP = 6;

    private final LocalDownloadsFragment host;
    private final Activity activity;
    private final OfflineUi ui;

    private final LinearLayout view;
    private ImageView artwork;
    private ImageButton menuButton;
    private TextView titleView;
    private TextView artistView;
    private SeekBar seekBar;
    private TextView currentTimeView;
    private TextView totalTimeView;
    private ImageButton previousButton;
    private ImageButton playButton;
    private ImageButton nextButton;
    private ImageButton shuffleButton;
    private ImageButton repeatButton;
    private QueueList queueList;
    private final BaseAdapter queueAdapter = new QueueAdapter();
    /** Follows the finger in place of the row being dragged. */
    private QueueRow dragRow;

    private boolean open;
    private boolean userSeeking;
    private boolean showingPause;
    @Nullable
    private RepeatMode shownRepeatMode;

    private List<String> queuePaths = new ArrayList<>();
    private int queueIndex = -1;

    /** The row being dragged, which moves through the queue as it is dragged, or -1. */
    private int draggedPosition = -1;
    private int dragStartPosition;
    private int dragRowHeight;
    /** How far from the top of the row it was picked up, so it does not jump to the finger. */
    private float dragGrabOffset;
    private final int[] queueLocation = new int[2];

    /** How far from their place the rows just moved past start to slide, by position. */
    private final SparseIntArray slidingRows = new SparseIntArray();

    OfflinePlayerPage(LocalDownloadsFragment host, Activity activity, OfflineUi ui) {
        this.host = host;
        this.activity = activity;
        this.ui = ui;

        view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(background());
        view.setVisibility(View.GONE);
        // The screen behind it must not take the touches that land between the controls.
        view.setClickable(true);

        // The cover and the queue share what the controls leave, so both fit in any window size.
        view.addView(createHero(), new LinearLayout.LayoutParams(-1, 0, 1));

        seekBar = createSeekBar();
        view.addView(seekBar, new LinearLayout.LayoutParams(-1, ui.dp(SEEK_BAR_TOUCH_DP)));
        view.addView(createTimeRow(), new LinearLayout.LayoutParams(-1, -2));
        view.addView(createControls(), new LinearLayout.LayoutParams(-1, -2));
        view.addView(createQueue(), new LinearLayout.LayoutParams(-1, 0, 1));

        updateShuffleAndRepeat();
    }

    View view() {
        return view;
    }

    /**
     * @return If back navigation was used up by this page.
     */
    boolean handleBack() {
        if (!open) return false;
        close();
        return true;
    }

    private float closedTranslation() {
        return ((View) view.getParent()).getHeight() * CLOSED_OFFSET;
    }

    void open(OfflineTrack playing) {
        if (open) return;
        open = true;
        setToolbarVisible(false);
        showTrack(playing);
        refreshQueue(true);

        view.animate().cancel();
        view.setVisibility(View.VISIBLE);
        view.setTranslationY(closedTranslation());
        view.setAlpha(OPEN_ANIMATION_START_ALPHA);
        view.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(OPEN_ANIMATION_MILLISECONDS)
                .setInterpolator(new DecelerateInterpolator(OPEN_INTERPOLATOR_FACTOR))
                .start();
    }

    void close() {
        if (!open) return;
        open = false;
        setToolbarVisible(true);

        view.animate().cancel();
        view.animate()
                .translationY(closedTranslation())
                .alpha(0f)
                .setDuration(CLOSE_ANIMATION_MILLISECONDS)
                .setInterpolator(new AccelerateInterpolator(CLOSE_INTERPOLATOR_FACTOR))
                .withEndAction(() -> view.setVisibility(View.GONE))
                .start();
    }

    /** So the cover reaches the top of the window. The activity is only used by this screen. */
    private void setToolbarVisible(boolean visible) {
        activity.findViewById(ID_TOOLBAR_PARENT).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    void showTrack(OfflineTrack track) {
        titleView.setText(track.displayTitle());
        artistView.setText(track.displayArtist());
        menuButton.setContentDescription(str("morphe_music_downloads_actions_for", track.displayTitle()));
        // The large cover is only decoded for a page that shows it.
        if (open) OfflineArtworkLoader.load(artwork, track.artworkFile(), ARTWORK_PIXELS, ui);
    }

    /**
     * Must be called on the main thread.
     *
     * @param trackChanged If this is the first report of a track, to bring it into view.
     */
    void onPlaybackChanged(boolean playing, int position, int duration, boolean trackChanged) {
        OfflineUi.setButtonEnabled(previousButton, OfflinePlaybackService.hasPrevious());
        OfflineUi.setButtonEnabled(nextButton, OfflinePlaybackService.hasNext());

        if (playing != showingPause) {
            showingPause = playing;
            playButton.setImageDrawable(ResourceUtils.getDrawable(playing
                    ? "yt_fill_experimental_pause_vd_theme_24"
                    : "yt_fill_experimental_play_vd_theme_24"));
        }

        seekBar.setMax(Math.max(1, duration));
        totalTimeView.setText(MediaTime.format(duration));
        if (!userSeeking) {
            seekBar.setProgress(position, true);
            currentTimeView.setText(MediaTime.format(position));
        }
        updateShuffleAndRepeat();

        if (open && (trackChanged || OfflinePlaybackService.getQueueIndex() != queueIndex)) {
            refreshQueue(trackChanged);
        }
    }

    /** This runs on every playback tick, so the icon is only replaced when the mode changes. */
    private void updateShuffleAndRepeat() {
        shuffleButton.setAlpha(OfflinePlaybackService.isShuffleEnabled() ? 1f : INACTIVE_BUTTON_ALPHA);

        RepeatMode mode = OfflinePlaybackService.getRepeatMode();
        if (mode == shownRepeatMode) return;
        shownRepeatMode = mode;

        repeatButton.setImageDrawable(ResourceUtils.getDrawable(mode == RepeatMode.ONE
                ? "yt_outline_experimental_repeat1_vd_theme_24"
                : "yt_outline_experimental_repeat_vd_theme_24"));
        repeatButton.setAlpha(mode == RepeatMode.OFF ? INACTIVE_BUTTON_ALPHA : 1f);
    }

    private void refreshQueue(boolean scrollToCurrent) {
        queuePaths = OfflinePlaybackService.getQueue();
        queueIndex = OfflinePlaybackService.getQueueIndex();
        queueAdapter.notifyDataSetChanged();

        if (scrollToCurrent && draggedPosition < 0 && queueIndex >= 0) {
            final int current = queueIndex;
            queueList.post(() -> queueList.setSelectionFromTop(current, ui.dp(QUEUE_SCROLL_TOP_DP)));
        }
    }

    private View createHero() {
        FrameLayout hero = new FrameLayout(activity);

        // Square and placed at the top, so a cover taller than the space only loses its bottom.
        artwork = new ImageView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, widthMeasureSpec);
            }
        };
        artwork.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.15f));
        ui.showArtwork(artwork, null);
        hero.addView(artwork, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Keeps the buttons readable on a light cover, and the title on any cover.
        View scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{SCRIM_TOP_COLOR, Color.TRANSPARENT, background()}));
        hero.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        hero.addView(createTopBar(), new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(10));

        titleView = ui.text("", 22, foreground());
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        labels.addView(titleView);

        artistView = ui.text("", 14, secondary());
        artistView.setSingleLine(true);
        labels.addView(artistView);

        hero.addView(labels, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return hero;
    }

    private View createTopBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), 0);

        ImageButton minimizeButton = ui.icon("yt_outline_experimental_chevron_down_vd_theme_24",
                str("morphe_music_downloads_minimize"));
        minimizeButton.setOnClickListener(v -> close());
        bar.addView(minimizeButton, iconButtonParams());

        bar.addView(new View(activity), spacerParams());

        menuButton = ui.icon("yt_outline_experimental_overflow_vertical_vd_theme_24", "");
        menuButton.setOnClickListener(v -> host.showPlayingTrackMenu(menuButton));
        bar.addView(menuButton, iconButtonParams());
        return bar;
    }

    private SeekBar createSeekBar() {
        SeekBar bar = ui.seekBar(20);
        ui.showSeekThumb(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                OfflinePlaybackService.seekTo(seekBar.getProgress());
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) currentTimeView.setText(MediaTime.format(value));
            }
        });
        return bar;
    }

    private View createTimeRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(4));

        currentTimeView = ui.text(MediaTime.format(0), 12, secondary());
        row.addView(currentTimeView, new LinearLayout.LayoutParams(0, -2, 1));

        totalTimeView = ui.text(MediaTime.format(0), 12, secondary());
        row.addView(totalTimeView, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private View createControls() {
        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(10));

        shuffleButton = ui.icon("yt_outline_experimental_shuffle_vd_theme_24",
                str("morphe_music_downloads_shuffle"));
        shuffleButton.setOnClickListener(v -> {
            OfflinePlaybackService.toggleShuffle();
            refreshQueue(true);
        });
        controls.addView(shuffleButton, iconButtonParams());
        controls.addView(new View(activity), spacerParams());

        previousButton = ui.icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        previousButton.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(activity));
        controls.addView(previousButton, iconButtonParams());
        controls.addView(new View(activity), spacerParams());

        controls.addView(createPlayButton(), new LinearLayout.LayoutParams(ui.dp(PLAY_BUTTON_DP), ui.dp(PLAY_BUTTON_DP)));
        controls.addView(new View(activity), spacerParams());

        nextButton = ui.icon("yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"));
        nextButton.setOnClickListener(v -> OfflinePlaybackService.skipNext(activity));
        controls.addView(nextButton, iconButtonParams());
        controls.addView(new View(activity), spacerParams());

        // The icon is set with the current mode.
        repeatButton = ui.icon("yt_outline_experimental_repeat_vd_theme_24",
                str("morphe_music_downloads_repeat"));
        repeatButton.setOnClickListener(v -> OfflinePlaybackService.cycleRepeatMode());
        controls.addView(repeatButton, iconButtonParams());
        return controls;
    }

    private View createPlayButton() {
        playButton = ui.icon("yt_fill_experimental_play_vd_theme_24",
                str("morphe_music_downloads_play_pause"));
        playButton.setColorFilter(background());
        playButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final int padding = ui.dp(PLAY_BUTTON_PADDING_DP);
        playButton.setPadding(padding, padding, padding, padding);

        ShapeDrawable circle = new ShapeDrawable(new OvalShape());
        circle.getPaint().setColor(foreground());
        playButton.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), circle, circle));
        playButton.setOnClickListener(v -> OfflinePlaybackService.toggle(activity));
        return playButton;
    }

    private LinearLayout.LayoutParams iconButtonParams() {
        return new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP));
    }

    private LinearLayout.LayoutParams spacerParams() {
        return new LinearLayout.LayoutParams(0, 0, 1);
    }

    private View createQueue() {
        FrameLayout container = new FrameLayout(activity);

        queueList = new QueueList(activity);
        queueList.setDivider(null);
        queueList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        queueList.setClipToPadding(false);
        final int padding = ui.dp(QUEUE_PADDING_DP);
        queueList.setPadding(padding, ui.dp(4), padding, ui.dp(16));
        // Two fingers would otherwise swipe or drag two rows at once.
        queueList.setMotionEventSplittingEnabled(false);
        queueList.setAdapter(queueAdapter);
        container.addView(queueList, new FrameLayout.LayoutParams(-1, -1));

        dragRow = new QueueRow();
        dragRow.row.setVisibility(View.GONE);
        dragRow.row.setElevation(ui.dp(OfflineUi.DRAG_ELEVATION_DP));
        FrameLayout.LayoutParams dragRowParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        dragRowParams.setMarginStart(padding);
        dragRowParams.setMarginEnd(padding);
        container.addView(dragRow.row, dragRowParams);
        return container;
    }

    private float rowAlpha(int position) {
        return position < queueIndex ? PLAYED_ROW_ALPHA : 1f;
    }

    /**
     * Keeps every touch while a row is dragged. Rows leave the list as it scrolls and are reused
     * for other tracks, so a drag can not depend on the row that it started on.
     */
    private final class QueueList extends ListView {
        QueueList(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (draggedPosition < 0) return super.dispatchTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE -> moveDragRow(event.getRawY());
                case MotionEvent.ACTION_UP -> finishDrag(true);
                case MotionEvent.ACTION_CANCEL -> finishDrag(false);
                default -> {
                }
            }
            return true;
        }
    }

    private void startDrag(QueueRow source, float rawY) {
        if (queuePaths.size() < 2) return;

        draggedPosition = source.position;
        dragStartPosition = source.position;
        dragRowHeight = source.row.getHeight();

        int[] rowLocation = new int[2];
        source.row.getLocationOnScreen(rowLocation);
        dragGrabOffset = rawY - rowLocation[1];

        dragRow.bind(source.position);
        dragRow.row.setAlpha(1f);
        dragRow.row.setBackground(ui.rowBackground(true));
        dragRow.highlighted = true;
        dragRow.row.setVisibility(View.VISIBLE);
        source.row.setVisibility(View.INVISIBLE);
        moveDragRow(rawY);
    }

    private void moveDragRow(float rawY) {
        queueList.getLocationOnScreen(queueLocation);
        final int maxTop = queueList.getHeight() - dragRowHeight;
        final int top = (int) Math.max(0, Math.min(maxTop, rawY - dragGrabOffset - queueLocation[1]));
        dragRow.row.setTranslationY(top);

        final int edge = ui.dp(AUTO_SCROLL_EDGE_DP);
        final boolean scrollUp = top < edge && queueList.canScrollList(-1);
        final boolean scrollDown = top > maxTop - edge && queueList.canScrollList(1);
        if (scrollUp || scrollDown) {
            queueList.scrollListBy(ui.dp(scrollUp ? -AUTO_SCROLL_STEP_DP : AUTO_SCROLL_STEP_DP));
        }

        final int target = queuePositionAt(top + dragRowHeight / 2);
        if (target == AdapterView.INVALID_POSITION || target == draggedPosition) return;

        final int from = draggedPosition;
        // Rows passing by on a scrolling list would overlap the rows that slide.
        if (!scrollUp && !scrollDown) slideRowsBetween(from, target);
        draggedPosition = target;
        OfflinePlaybackService.moveQueueItem(from, target);
        refreshQueue(false);
    }

    /**
     * The position of the row laid out at a height of the list. A sliding row must not count as
     * being where it is drawn: the row that just made room is still under the finger, and would
     * be swapped back.
     */
    private int queuePositionAt(int y) {
        for (int i = queueList.getChildCount() - 1; i >= 0; i--) {
            View child = queueList.getChildAt(i);
            if (y >= child.getTop() && y < child.getBottom()) {
                return queueList.getFirstVisiblePosition() + i;
            }
        }
        return AdapterView.INVALID_POSITION;
    }

    /** Each row between the two positions takes the place next to it, from where it is drawn. */
    private void slideRowsBetween(int from, int target) {
        final int firstVisible = queueList.getFirstVisiblePosition();
        final int step = target > from ? 1 : -1;
        for (int position = from; position != target; position += step) {
            View incoming = queueList.getChildAt(position + step - firstVisible);
            if (incoming != null && queueList.getChildAt(position - firstVisible) != null) {
                slidingRows.put(position, step * dragRowHeight + (int) incoming.getTranslationY());
            }
        }
    }

    /**
     * @param keep If the row stays where it was dragged to, or goes back to where it started.
     */
    private void finishDrag(boolean keep) {
        final int dragged = draggedPosition;
        draggedPosition = -1;
        dragRow.row.setVisibility(View.GONE);

        if (!keep) OfflinePlaybackService.moveQueueItem(dragged, dragStartPosition);
        refreshQueue(false);
    }

    private final class QueueAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return queuePaths.size();
        }

        @Override
        public String getItem(int position) {
            return queuePaths.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, @Nullable View convertView, ViewGroup parent) {
            QueueRow row = convertView != null && convertView.getTag() instanceof QueueRow reused
                    ? reused
                    : new QueueRow();
            row.bind(position);
            return row.row;
        }
    }

    /** The views of one row of the queue, reused while scrolling through it. */
    private final class QueueRow {
        final LinearLayout row = ui.trackRow();
        final OfflineUi.TrackInfoViews info = ui.createTrackInfo(row);
        int position;
        @Nullable
        String path;
        boolean highlighted;

        @SuppressLint("ClickableViewAccessibility")
        QueueRow() {
            row.setBackground(ui.rowBackground(false));
            row.setTag(this);
            row.setOnClickListener(v -> OfflinePlaybackService.playQueueIndex(position));
            row.setOnTouchListener(new SwipeToRemove(this));

            ImageButton handle = ui.icon(ui.dragHandle(), str("morphe_music_downloads_playlist_drag_track"));
            handle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) startDrag(this, event.getRawY());
                return true;
            });
            row.addView(handle, iconButtonParams());
        }

        void bind(int position) {
            final String newPath = queuePaths.get(position);
            // Every row is bound again for each move of a drag, and this one is not affected.
            final boolean keepSliding = position == this.position && newPath.equals(path)
                    && row.getTranslationY() != 0f;
            this.position = position;
            path = newPath;
            if (!keepSliding) {
                row.animate().cancel();
                row.setTranslationX(0f);
                row.setTranslationY(0f);
            }
            row.setVisibility(position == draggedPosition ? View.INVISIBLE : View.VISIBLE);

            ui.bindTrackInfo(info, host.trackFor(newPath));

            final boolean current = position == queueIndex;
            if (highlighted != current) {
                highlighted = current;
                row.setBackground(ui.rowBackground(current));
            }
            row.setAlpha(rowAlpha(position));

            final int slideDistance = slidingRows.get(position);
            if (slideDistance != 0) {
                slidingRows.delete(position);
                row.setTranslationY(slideDistance);
                row.animate().translationY(0f).setDuration(DRAG_ANIMATION_MILLISECONDS).start();
            }
        }
    }

    /**
     * Follows a sideways swipe of a row, and leaves tapping it and scrolling the list as they are
     * until the touch has become a swipe.
     */
    private final class SwipeToRemove implements View.OnTouchListener {
        private final QueueRow queueRow;
        private final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        private float downRawX;
        private float downRawY;
        private boolean swiping;

        SwipeToRemove(QueueRow queueRow) {
            this.queueRow = queueRow;
        }

        @Override
        public boolean onTouch(View row, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    swiping = false;
                    return false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    final float distance = event.getRawX() - downRawX;
                    if (!swiping && isSwipe(distance, event.getRawY() - downRawY)) {
                        swiping = true;
                        queueList.requestDisallowInterceptTouchEvent(true);
                        cancelTap(row, event);
                    }
                    if (!swiping) return false;

                    row.setTranslationX(distance);
                    final float fade = Math.min(1f, Math.abs(distance) / (row.getWidth() * SWIPE_FADE_DISTANCE));
                    row.setAlpha(rowAlpha(queueRow.position) * (1f - fade * SWIPE_MAX_FADE));
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!swiping) return false;
                    swiping = false;
                    finishSwipe(event.getRawX() - downRawX,
                            event.getActionMasked() == MotionEvent.ACTION_UP);
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private boolean isSwipe(float distanceX, float distanceY) {
            return Math.abs(distanceX) > touchSlop
                    && Math.abs(distanceX) > Math.abs(distanceY) * SWIPE_DIRECTION_RATIO;
        }

        /** The row has seen the touch so far, so it has to be told that this is no tap. */
        private void cancelTap(View row, MotionEvent event) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            row.onTouchEvent(cancel);
            cancel.recycle();
        }

        private void finishSwipe(float distance, boolean released) {
            View row = queueRow.row;
            ViewPropertyAnimator animator = row.animate().setDuration(SWIPE_ANIMATION_MILLISECONDS);
            if (released && Math.abs(distance) > row.getWidth() * SWIPE_REMOVE_FRACTION) {
                final int removed = queueRow.position;
                animator.translationX(Math.signum(distance) * row.getWidth())
                        .alpha(0f)
                        .withEndAction(() -> {
                            OfflinePlaybackService.removeQueueItem(removed);
                            refreshQueue(false);
                        });
            } else {
                animator.translationX(0f).alpha(rowAlpha(queueRow.position));
            }
            animator.start();
        }
    }
}
