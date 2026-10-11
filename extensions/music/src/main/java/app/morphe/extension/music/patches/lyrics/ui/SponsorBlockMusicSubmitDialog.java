/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3575
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntConsumer;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.SkipSegments;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.sponsorblock.requests.SBRequester;
import app.morphe.extension.shared.sponsorblock.requests.SBRequester.SegmentSubmitAction;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * YouTube Music dialog that submits the span recorded in the lyrics panel as a SponsorBlock
 * segment, so lyrics written against the released audio line up with the music video. While
 * the dialog is open the span is previewed: the lyrics skip it as if it were already stored.
 */
final class SponsorBlockMusicSubmitDialog {

    /** Highlights are a single point in time and cannot shift lyrics. */
    private static final SegmentCategory[] CATEGORIES =
            SegmentCategory.categoriesWithoutHighlights();
    private static final int DEFAULT_CATEGORY_INDEX =
            Arrays.asList(CATEGORIES).indexOf(SegmentCategory.MUSIC_OFFTOPIC);

    /** How long the server takes before a new segment is returned by segment requests. */
    private static final long REFRESH_DELAY_MS = 10_000;

    private SponsorBlockMusicSubmitDialog() {
    }

    static String formatTimeMs(long ms) {
        final long total = Math.max(0, ms);
        return String.format(Locale.US, "%02d:%02d.%03d",
                total / 60000, (total % 60000) / 1000, total % 1000);
    }

    private static String formatSeconds(long ms) {
        return String.format(Locale.US, "%.3f", ms / 1000f);
    }

    static String formatOffsetMs(long offsetMs) {
        return (offsetMs >= 0 ? "+" : "") + offsetMs + "ms";
    }

    static void show(Context context, String videoId, long startMs, long endMs,
                     IntConsumer onOffsetSync, Runnable onExit) {
        final long videoLength = VideoInformation.getVideoLength();
        final long[] times = {clampTime(Math.min(startMs, endMs), videoLength),
                clampTime(Math.max(startMs, endMs), videoLength)};
        final int[] slots = {0, 0};
        final int[] selected = {DEFAULT_CATEGORY_INDEX};
        final boolean[] submitted = {false};
        final LyricsManager manager = LyricsManager.getInstance();

        final TextView startView = createTimeView(context);
        final TextView endView = createTimeView(context);
        final TextView separator = new TextView(context);
        separator.setText(" - ");
        separator.setTextSize(22);
        separator.setTypeface(Typeface.DEFAULT_BOLD);
        separator.setTextColor(ThemeUtils.getAppForegroundColor());

        final OffsetSliderView offsetSlider = new OffsetSliderView(context);
        offsetSlider.setValue(manager.getTemporaryOffsetMs());
        offsetSlider.setOnOffsetChanged(ms -> {
            manager.setTemporaryOffsetMs(ms);
            onOffsetSync.accept(ms);
        });

        final Runnable refreshTimes = () -> {
            startView.setText(formatTimeMs(times[0]));
            endView.setText(formatTimeMs(times[1]));
            // The candidate skips the lyrics the moment it exists and on every resample,
            // so what the dialog shows is what playback already does.
            manager.setDialogSegment(videoId, times[0], times[1]);
            offsetSlider.setValue(manager.getTemporaryOffsetMs());
        };
        bindTimeView(startView, times, slots, 0, refreshTimes);
        bindTimeView(endView, times, slots, 1, refreshTimes);
        refreshTimes.run();

        final LinearLayout timesRow = new LinearLayout(context);
        timesRow.setOrientation(LinearLayout.HORIZONTAL);
        timesRow.setGravity(Gravity.CENTER);
        timesRow.addView(startView);
        timesRow.addView(separator);
        timesRow.addView(endView);

        final Button offsetButtonA = createCircleButton(context, "A");
        final Button offsetButtonB = createCircleButton(context, "B");
        offsetButtonA.setOnClickListener(v ->
                applyOffsetSlot(manager, offsetSlider, times, slots, 0, refreshTimes));
        offsetButtonB.setOnClickListener(v ->
                applyOffsetSlot(manager, offsetSlider, times, slots, 1, refreshTimes));

        final LinearLayout offsetRow = new LinearLayout(context);
        offsetRow.setOrientation(LinearLayout.HORIZONTAL);
        offsetRow.setGravity(Gravity.CENTER_VERTICAL);
        offsetRow.addView(offsetButtonA, new LinearLayout.LayoutParams(
                Dim.dp36, Dim.dp36));
        final LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                0, Dim.dp36, 1f);
        sliderParams.leftMargin = Dim.dp8;
        sliderParams.rightMargin = Dim.dp8;
        offsetRow.addView(offsetSlider, sliderParams);
        offsetRow.addView(offsetButtonB, new LinearLayout.LayoutParams(
                Dim.dp36, Dim.dp36));

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(timesRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(offsetRow, blockParams(Dim.dp28));

        final String[] categoryTitles = new String[CATEGORIES.length];
        final String[] categoryKeys = new String[CATEGORIES.length];
        for (int i = 0; i < CATEGORIES.length; i++) {
            categoryTitles[i] = CATEGORIES[i].title.toString();
            categoryKeys[i] = CATEGORIES[i].keyValue;
        }

        final Button categoryButton = CustomDialog.createButton(context, null,
                categoryTitles[selected[0]], null, false, false);
        categoryButton.setOnClickListener(v -> {
            final Dialog picker = CustomDialogListPreference.createListDialog(
                    context, str("morphe_sb_new_segment_choose_category"),
                    categoryTitles, categoryKeys, categoryKeys[selected[0]],
                    index -> {
                        selected[0] = index;
                        categoryButton.setText(categoryTitles[index]);
                    });
            picker.show();
        });
        content.addView(categoryButton, blockParams(Dim.dp16));

        final Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_sb_new_segment_title"),
                null,
                null,
                str("morphe_sb_new_segment_confirm_submit"),
                () -> {
                    final long start = times[0];
                    final long end = times[1];
                    if (start >= end) {
                        Utils.showToastShort(
                                str("morphe_sb_new_segment_start_is_before_end"));
                        return;
                    }
                    final SegmentCategory category = CATEGORIES[selected[0]];
                    submitted[0] = true;
                    Utils.runOnBackgroundThread(() -> submitSegment(
                            videoId, category, start, end, videoLength, manager));
                },
                () -> { },
                str("morphe_sb_settings_copy"),
                () -> copySpan(context, videoId, times),
                false);

        final Dialog dialog = dialogPair.first;
        dialog.setOnDismissListener(d -> {
            if (!submitted[0]) {
                manager.clearDialogSegment();
            }
            onExit.run();
        });
        final LinearLayout mainLayout = dialogPair.second;
        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the submit dialog", ex);
            // No dismissal listener will ever fire, so the preview must be dropped here.
            manager.clearDialogSegment();
        }
    }

    /** Submits on a background thread. {@link SBRequester} reports the outcome to the user. */
    private static void submitSegment(String videoId, SegmentCategory category,
                                      long startMs, long endMs, long videoLength,
                                      LyricsManager manager) {
        boolean stored = false;
        try {
            stored = SBRequester.submitSegments(videoId, category, SegmentSubmitAction.SKIP,
                    startMs, endMs, videoLength);
        } catch (Exception ex) {
            Logger.printException(() -> "submitSegment failure", ex);
        }
        if (stored) {
            scheduleSegmentRefresh(videoId, manager);
        } else {
            dropDialogPreview(manager, videoId, startMs, endMs);
        }
    }

    private static void scheduleSegmentRefresh(String videoId, LyricsManager manager) {
        Utils.runOnMainThreadDelayed(() -> {
            if (videoId.equals(manager.skipSegmentsVideoId())) {
                SkipSegments.invalidate(videoId);
                SkipSegments.prefetch(videoId);
            }
        }, REFRESH_DELAY_MS);
    }

    private static void dropDialogPreview(LyricsManager manager, String videoId,
                                          long startMs, long endMs) {
        Utils.runOnMainThread(() -> manager.clearDialogSegment(videoId, startMs, endMs));
    }

    private static void copySpan(Context context, String videoId, long[] times) {
        final long offsetMs = LyricsManager.getInstance().getTemporaryOffsetMs();
        final String text = videoId + " [" + formatSeconds(times[0]) + ", "
                + formatSeconds(times[1]) + "] " + (offsetMs > 0 ? "+" : "") + offsetMs;
        final ClipboardManager clipboard = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("sponsorblock", text));
            Utils.showToastShort(str("morphe_music_lyrics_copied"));
        }
    }

    private static TextView createTimeView(Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(22);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(ThemeUtils.getAppForegroundColor());
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static LinearLayout.LayoutParams blockParams(int topMargin) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
        params.topMargin = topMargin;
        return params;
    }

    private static void bindTimeView(TextView view, long[] times, int[] slots, int index,
                                     Runnable refreshTimes) {
        final long[] downSample = new long[1];
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downSample[0] = LyricsManager.getInstance().getVideoPositionMs();
            }
            return false;
        });
        view.setOnLongClickListener(v -> {
            times[index] = clampTime(downSample[0] + slots[index],
                    VideoInformation.getVideoLength());
            if (times[0] > times[1]) {
                swapSides(times, slots);
            }
            refreshTimes.run();
            return true;
        });
        view.setOnClickListener(v -> VideoInformation.seekTo(times[index]));
    }

    private static long clampTime(long value, long videoLength) {
        if (value < 0) {
            return 0;
        }
        return videoLength > 0 && value > videoLength ? videoLength : value;
    }

    private static void swapSides(long[] times, int[] slots) {
        final long swapTime = times[0];
        times[0] = times[1];
        times[1] = swapTime;
        final int swapSlot = slots[0];
        slots[0] = slots[1];
        slots[1] = swapSlot;
    }

    private static Button createCircleButton(Context context, String text) {
        final Button button = CustomDialog.createButton(context, null, text, null,
                false, false);
        final GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ThemeUtils.getCancelOrNeutralButtonBackgroundColor());
        button.setBackground(circle);
        return button;
    }

    private static void applyOffsetSlot(LyricsManager manager, OffsetSliderView slider,
                                        long[] times, int[] slots, int index,
                                        Runnable refreshTimes) {
        final int delta = manager.getTemporaryOffsetMs();
        final long target = clampTime(times[index] + delta,
                VideoInformation.getVideoLength());
        final long applied = target - times[index];
        if (applied != 0) {
            slots[index] += (int) applied;
            times[index] += applied;
            if (times[0] > times[1]) {
                swapSides(times, slots);
            }
            refreshTimes.run();
        }
        slider.apply(delta - (int) applied);
    }

    /**
     * The offset block: a rounded rectangle the size of the category picker that is
     * dragged as a whole - no anti-mistouch thresholds - to move the temporary offset
     * through the same range and step as the panel's ruler. Holding it for a second
     * resets the offset to zero.
     */
    private static final class OffsetSliderView extends View {
        private static final int RANGE_MS = 30000;
        private static final int STEP_MS = 10;
        private static final float DRAG_DIVISOR = 10f;
        private static final long LONG_PRESS_MS = 1000;

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int touchSlop;

        private int offsetMs;
        private float downX;
        private int downValue;
        private boolean dragging;

        @Nullable
        private IntConsumer listener;

        private final Runnable resetRunnable = () -> {
            downValue = 0;
            apply(0);
        };

        OffsetSliderView(Context context) {
            super(context);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setColor(ThemeUtils.getAppForegroundColor());
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setBackground(CustomDialog.createRoundedBackground(20,
                    ThemeUtils.getCancelOrNeutralButtonBackgroundColor()));
        }

        void setOnOffsetChanged(IntConsumer listener) {
            this.listener = listener;
        }

        void setValue(int ms) {
            offsetMs = clamp(ms);
            invalidate();
        }

        private static int clamp(int ms) {
            return Math.max(-RANGE_MS, Math.min(RANGE_MS, ms));
        }

        private void apply(int ms) {
            final int stepped = clamp(Math.round(ms / (float) STEP_MS) * STEP_MS);
            if (stepped == offsetMs) {
                return;
            }
            offsetMs = stepped;
            invalidate();
            if (listener != null) {
                listener.accept(stepped);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int w = MeasureSpec.getSize(widthMeasureSpec);
            final int h = (int) (36 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(w, h);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            final float x = event.getX();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    setValue(LyricsManager.getInstance().getTemporaryOffsetMs());
                    downX = x;
                    downValue = offsetMs;
                    dragging = false;
                    postDelayed(resetRunnable, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(x - downX) > touchSlop) {
                        dragging = true;
                        removeCallbacks(resetRunnable);
                    }
                    if (dragging) {
                        final int delta = Math.round(
                                (x - downX) / Math.max(1, getWidth())
                                        * (2f * RANGE_MS) / DRAG_DIVISOR);
                        apply(downValue - delta);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(resetRunnable);
                    dragging = false;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            textPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()));
            final Paint.FontMetrics metrics = textPaint.getFontMetrics();
            canvas.drawText(formatOffsetMs(offsetMs),
                    getWidth() / 2f,
                    getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f, textPaint);
        }
    }
}
