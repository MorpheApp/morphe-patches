/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4511
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.onthego;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.Arrays;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.misc.MediaTime;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.CustomSeekbarThumbPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.playback.speed.CustomPlaybackSpeedPatch;
import app.morphe.extension.youtube.patches.theme.SeekbarColorPatch;
import app.morphe.extension.youtube.shared.PipDismissHelper;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

/**
 * Panel with large playback controls for the video of the player.
 * The buttons of the player are used for the previous, play/pause and next actions,
 * so they work the same as in the player, such as in playlists and with the queue.
 */
final class OnTheGoSheet {

    @Nullable
    private static WeakReference<OnTheGoSheet> current;

    static void show() {
        try {
            OnTheGoSheet sheet = current != null ? current.get() : null;
            if (sheet != null && sheet.dialog.isShowing()) {
                return;
            }

            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Logger.printException(() -> "Could not show the On-the-go panel, activity is not available");
                return;
            }

            new OnTheGoSheet(activity).create();
        } catch (Exception ex) {
            Logger.printException(() -> "show failure", ex);
        }
    }

    private static final int DIALOG_ANIMATION_DURATION_MILLISECONDS = 300;
    private static final long UPDATE_INTERVAL_MILLISECONDS = 500;
    private static final long SEEK_LENGTH_MILLISECONDS = 10_000;
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10_000;
    private static final float THUMBNAIL_ASPECT_RATIO = 16f / 9;
    /**
     * The panel is made to be used without scrolling, so it can be taller than other sheets.
     */
    private static final int MAX_HEIGHT_PERCENT = 90;
    /**
     * Color of the time bar, if no custom seekbar color is used.
     */
    private static final int TIME_BAR_COLOR = 0xFFFF0033;
    private static final float MUTED_TEXT_ALPHA = 0.62f;
    private static final float BUTTON_BACKGROUND_ALPHA = 0.12f;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int foregroundColor = ThemeUtils.getAppForegroundColor();
    private final int mutedColor = withAlpha(foregroundColor, MUTED_TEXT_ALPHA);
    private final int buttonBackgroundColor = withAlpha(foregroundColor, BUTTON_BACKGROUND_ALPHA);

    private SheetBottomDialog.SlideDialog dialog;
    private ImageView thumbnail;
    private TextView titleView;
    private TextView channelView;
    private TextView timeView;
    private SeekBar timeBar;
    private ImageView playPauseButton;
    private TextView speedButton;

    @Nullable
    private String videoId;
    @Nullable
    private VideoState shownState;
    private boolean userSeeking;
    /**
     * Custom seekbar thumb of the time bar, or null if the default thumb is used.
     */
    @Nullable
    private Drawable customThumb;

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            update();
            if (dialog.isShowing()) {
                handler.postDelayed(this, UPDATE_INTERVAL_MILLISECONDS);
            }
        }
    };

    private OnTheGoSheet(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        Context context = activity;
        SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog.createMainLayout(context, null);
        fillScreenEdges(mainLayout);

        ScrollView scrollView = SheetBottomDialog.createCappedScrollView(context, MAX_HEIGHT_PERCENT);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Dim.dp20, Dim.dp8, Dim.dp20, Dim.dp20);
        scrollView.addView(content);
        mainLayout.addView(scrollView);

        TextView header = new TextView(context);
        header.setText(str("morphe_on_the_go_panel_title"));
        header.setTextColor(foregroundColor);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        content.addView(header, matchWidth(0, Dim.dp16));

        thumbnail = new ImageView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                        Math.round(width / THUMBNAIL_ASPECT_RATIO), MeasureSpec.EXACTLY));
            }
        };
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(buttonBackgroundColor);
        thumbnail.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Dim.dp12);
            }
        });
        thumbnail.setClipToOutline(true);
        content.addView(thumbnail, matchWidth(0, Dim.dp16));

        titleView = new TextView(context);
        titleView.setTextColor(foregroundColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(titleView, matchWidth(0, Dim.dp4));

        channelView = new TextView(context);
        channelView.setTextColor(mutedColor);
        channelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        channelView.setSingleLine(true);
        channelView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(channelView, matchWidth(0, Dim.dp24));

        timeView = new TextView(context);
        timeView.setTextColor(foregroundColor);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        content.addView(timeView, matchWidth(0, Dim.dp4));

        timeBar = new SeekBar(context);
        // The custom seekbar color, if the seekbar color patch is included and the color is used.
        ColorStateList timeBarColor = ColorStateList.valueOf(
                SeekbarColorPatch.isPatchIncluded() && SeekbarColorPatch.isCustomSeekbarColorEnabled()
                        ? SeekbarColorPatch.getSeekbarColor()
                        : TIME_BAR_COLOR);
        timeBar.setProgressTintList(timeBarColor);
        customThumb = CustomSeekbarThumbPatch.createSeekBarThumb();
        if (customThumb != null) {
            timeBar.setThumb(customThumb);
        } else {
            timeBar.setThumbTintList(timeBarColor);
        }
        timeBar.setProgressBackgroundTintList(ColorStateList.valueOf(mutedColor));
        timeBar.setPadding(0, Dim.dp8, 0, Dim.dp8);
        timeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    setTimeText(progress, bar.getMax());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                VideoInformation.seekTo(bar.getProgress());
            }
        });
        content.addView(timeBar, matchWidth(0, Dim.dp16));

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        addControl(controls, createControl("quantum_ic_skip_previous_white_24", "accessibility_previous",
                Dim.dp48, v -> clickPlayerButton("player_control_previous_button")));
        addControl(controls, createControl("quantum_ic_replay_10_white_24", "accessibility_rewind",
                Dim.dp48, v -> VideoInformation.seekToRelative(-SEEK_LENGTH_MILLISECONDS)));
        playPauseButton = createControl("quantum_ic_play_arrow_white_36", "accessibility_play",
                Dim.dp(80), v -> clickPlayerButton("player_control_play_pause_replay_button"));
        GradientDrawable playPauseBackground = new GradientDrawable();
        playPauseBackground.setShape(GradientDrawable.OVAL);
        playPauseBackground.setColor(buttonBackgroundColor);
        playPauseButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(buttonBackgroundColor), playPauseBackground, null));
        addControl(controls, playPauseButton);
        addControl(controls, createControl("quantum_ic_forward_10_white_24", "accessibility_fast_forward",
                Dim.dp48, v -> VideoInformation.seekToRelative(SEEK_LENGTH_MILLISECONDS)));
        addControl(controls, createControl("quantum_ic_skip_next_white_24", "accessibility_next",
                Dim.dp48, v -> clickPlayerButton("player_control_next_button")));
        content.addView(controls, matchWidth(0, Dim.dp24));

        speedButton = new TextView(context);
        speedButton.setTextColor(foregroundColor);
        speedButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        speedButton.setTypeface(speedButton.getTypeface(), Typeface.BOLD);
        speedButton.setGravity(Gravity.CENTER);
        speedButton.setPadding(Dim.dp20, Dim.dp10, Dim.dp20, Dim.dp10);
        GradientDrawable speedBackground = new GradientDrawable();
        speedBackground.setCornerRadius(Dim.dp20);
        speedBackground.setColor(buttonBackgroundColor);
        speedButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(buttonBackgroundColor), speedBackground, null));
        speedButton.setOnClickListener(v ->
                CustomPlaybackSpeedPatch.showModernCustomPlaybackSpeedDialog(activity));
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        speedParams.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(speedButton, speedParams);

        dialog = SheetBottomDialog.createSlideDialog(context, mainLayout, DIALOG_ANIMATION_DURATION_MILLISECONDS);
        current = new WeakReference<>(this);
        PipDismissHelper.dismissOnPip(dialog, () -> {
            handler.removeCallbacks(updater);
            if (customThumb instanceof Animatable animatable) {
                animatable.stop();
            }
            if (current != null && current.get() == this) {
                current = null;
            }
        });

        dialog.show();
        update();
        handler.postDelayed(updater, UPDATE_INTERVAL_MILLISECONDS);
    }

    /**
     * Removes the side and bottom margins of the sheet, so it fills the width and bottom of the screen.
     * Only the top corners are rounded.
     */
    private static void fillScreenEdges(LinearLayout mainLayout) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) mainLayout.getLayoutParams();
        params.setMargins(0, 0, 0, 0);
        mainLayout.setLayoutParams(params);

        float[] corners = Dim.roundedCorners(12);
        // Bottom right and bottom left corners.
        Arrays.fill(corners, 4, 8, 0);
        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(corners, null, null));
        background.getPaint().setColor(ThemeUtils.getDialogBackgroundColor());
        mainLayout.setBackground(background);
    }

    private ImageView createControl(String drawableName, String descriptionName, int size,
                                    View.OnClickListener listener) {
        ImageView button = new ImageView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // Always square, so the round background is a circle.
                // Made smaller if the row is too narrow for the size.
                int side = size;
                if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                    side = Math.min(side, MeasureSpec.getSize(widthMeasureSpec));
                }
                setMeasuredDimension(side, side);
            }
        };
        button.setScaleType(ImageView.ScaleType.CENTER);
        setIcon(button, drawableName, descriptionName);
        button.setImageTintList(ColorStateList.valueOf(foregroundColor));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(buttonBackgroundColor), null, mask));
        button.setOnClickListener(listener);
        return button;
    }

    private static void setIcon(ImageView button, String drawableName, String descriptionName) {
        Drawable icon = ResourceUtils.getDrawable(drawableName);
        if (icon != null) {
            button.setImageDrawable(icon);
        }
        button.setContentDescription(str(descriptionName));
    }

    /**
     * Adds a control centered in an equal share of the row width.
     * The control is not stretched to the share, so it keeps its size.
     */
    private static void addControl(LinearLayout controls, View control) {
        FrameLayout cell = new FrameLayout(controls.getContext());
        cell.addView(control, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.gravity = Gravity.CENTER_VERTICAL;
        controls.addView(cell, params);
    }

    private static LinearLayout.LayoutParams matchWidth(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, topMargin, 0, bottomMargin);
        return params;
    }

    /**
     * Clicks a button of the player, which works while the player controls are hidden.
     */
    private void clickPlayerButton(String idName) {
        final int id = ResourceUtils.getIdentifier(activity, ResourceType.ID, idName);
        // The player can have more than one view with the same id, as the play button has.
        View group = activity.findViewById(ResourceUtils.getIdentifier(
                activity, ResourceType.ID, "controls_button_group_layout"));
        View button = group != null ? group.findViewById(id) : null;
        if (button == null) {
            button = activity.findViewById(id);
        }
        if (button == null) {
            Logger.printException(() -> "Could not find player button: " + idName);
            return;
        }

        button.performClick();
        // Show the new state right away, instead of on the next update.
        handler.postDelayed(this::update, UPDATE_INTERVAL_MILLISECONDS / 2);
    }

    private void update() {
        try {
            if (!dialog.isShowing()) {
                return;
            }
            if (PlayerType.getCurrent().isNoneOrHidden()) {
                dialog.dismiss();
                return;
            }

            String id = VideoInformation.getVideoId();
            if (!id.equals(videoId)) {
                videoId = id;
                thumbnail.setImageDrawable(null);
                if (!id.isEmpty()) {
                    loadThumbnail(id);
                }
            }
            setTextIfChanged(titleView, VideoInformation.getVideoTitle());
            setTextIfChanged(channelView, VideoInformation.getChannelName());

            final boolean live = VideoInformation.isLiveStream();
            timeView.setVisibility(live ? View.GONE : View.VISIBLE);
            timeBar.setVisibility(live ? View.GONE : View.VISIBLE);
            if (!live && !userSeeking) {
                final long length = Math.max(0, VideoInformation.getVideoLength());
                final long time = Math.min(Math.max(0, VideoInformation.getVideoTime()), length);
                timeBar.setMax((int) length);
                timeBar.setProgress((int) time);
                setTimeText(time, length);
            }

            VideoState state = VideoState.getCurrent();
            if (state != shownState) {
                shownState = state;
                if (state == VideoState.PLAYING) {
                    setIcon(playPauseButton, "quantum_ic_pause_white_36", "accessibility_pause");
                } else if (state == VideoState.ENDED) {
                    setIcon(playPauseButton, "quantum_ic_replay_white_36", "accessibility_replay");
                } else {
                    setIcon(playPauseButton, "quantum_ic_play_arrow_white_36", "accessibility_play");
                }
            }

            setTextIfChanged(speedButton, VideoInformation.formatSpeedStringX(
                    VideoInformation.getPlaybackSpeed()));
        } catch (Exception ex) {
            Logger.printException(() -> "update failure", ex);
        }
    }

    private void setTimeText(long time, long length) {
        setTextIfChanged(timeView, MediaTime.format(time) + " / " + MediaTime.format(length));
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) {
            view.setText(text);
        }
    }

    private void loadThumbnail(String id) {
        Utils.runOnBackgroundThread(() -> {
            Bitmap bitmap = downloadThumbnail(id);
            if (bitmap != null) {
                Utils.runOnMainThread(() -> {
                    if (id.equals(videoId)) {
                        thumbnail.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    @Nullable
    private static Bitmap downloadThumbnail(String id) {
        try {
            // The video is cropped to the 16:9 view, which removes the bars of 4:3 thumbnails.
            HttpURLConnection connection = Requester.openConnection(
                    "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg");
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            try (InputStream stream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(stream);
            } finally {
                connection.disconnect();
            }
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not load the thumbnail of: " + id, ex);
        } catch (Exception ex) {
            Logger.printException(() -> "downloadThumbnail failure", ex);
        }
        return null;
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(Color.alpha(color) * alpha),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
