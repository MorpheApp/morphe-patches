/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4511
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.onthego;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.misc.MediaTime;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.AddToQueuePatch;
import app.morphe.extension.youtube.patches.CustomSeekbarThumbPatch;
import app.morphe.extension.youtube.patches.DownloadsPatch;
import app.morphe.extension.youtube.patches.LocalQueuePatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.playback.speed.CustomPlaybackSpeedPatch;
import app.morphe.extension.youtube.patches.theme.SeekbarColorPatch;
import app.morphe.extension.youtube.patches.utils.LocalQueueSheet;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

/**
 * Panel with large playback controls for the video of the player.
 * The buttons of the player are used for the previous, play/pause and next actions,
 * so they work the same as in the player, such as in playlists and with the queue.
 */
@SuppressWarnings("unused")
final class OnTheGoSheet {

    @Nullable
    private static WeakReference<OnTheGoSheet> current;

    static void show() {
        try {
            OnTheGoSheet sheet = current != null ? current.get() : null;
            if (sheet != null && sheet.isShowing()) {
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

    private static final int ANIMATION_DURATION_MILLISECONDS = 300;
    private static final long UPDATE_INTERVAL_MILLISECONDS = 500;
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10_000;
    private static final float THUMBNAIL_ASPECT_RATIO = 16f / 9;
    /**
     * Share of the panel height it must be dragged down to close.
     */
    private static final float DISMISS_DRAG_FRACTION = 0.25f;
    /**
     * Downward speed in pixels per second that closes the panel when the drag ends.
     */
    private static final int DISMISS_FLING_VELOCITY = 800;
    /**
     * Color of the time bar, if no custom seekbar color is used.
     */
    private static final int TIME_BAR_COLOR = 0xFFFF0033;
    private static final float MUTED_TEXT_ALPHA = 0.62f;
    private static final float BUTTON_BACKGROUND_ALPHA = 0.12f;
    private static final float DISABLED_ALPHA = 0.38f;
    private static final int CONTENT_SIDE_PADDING = Dim.dp16;
    private static final int CONTROL_ICON_SIZE = Dim.dp36;
    private static final int PLAY_PAUSE_SIZE = Dim.dp(72);
    /**
     * Seek lengths in seconds that the app has seek icons for.
     */
    private static final int[] SEEK_ICON_SECONDS = {5, 10, 15, 20, 30, 60};

    /**
     * If the panel shows the thumbnail instead of the video, as the audio mode of the app.
     * Kept while the app runs, so the panel opens in the mode it was last closed in.
     */
    private static boolean audioMode;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int foregroundColor = ThemeUtils.getAppForegroundColor();
    private final int mutedColor = withAlpha(foregroundColor, MUTED_TEXT_ALPHA);
    private final int buttonBackgroundColor = withAlpha(foregroundColor, BUTTON_BACKGROUND_ALPHA);

    /**
     * The panel is a view of the activity, as the panel of the app, so the video above it
     * stays visible and can be used, and the panel can be placed right below the video.
     */
    private LinearLayout panel;
    private ImageView thumbnail;
    private TextView titleView;
    private TextView channelView;
    private TextView timeView;
    private SeekBar timeBar;
    private ImageView previousButton;
    private ImageView seekBackButton;
    private ImageView playPauseButton;
    private ImageView seekForwardButton;
    private ImageView audioModeButton;
    /**
     * Speed button that shows the speed, if the app has no speed icon.
     */
    @Nullable
    private TextView speedText;

    private boolean showing;
    private boolean dismissing;
    /**
     * Largest thumbnail height, which is less than the full height if the panel is too short.
     */
    private int thumbnailMaxHeight = Integer.MAX_VALUE;
    @Nullable
    private String videoId;
    /**
     * Video of the thumbnail that is shown or loading, or null if no thumbnail is loaded.
     */
    @Nullable
    private String thumbnailVideoId;
    @Nullable
    private VideoState shownState;
    private long shownSeekLength;
    private boolean userSeeking;
    /**
     * Custom seekbar thumb of the time bar, or null if the default thumb is used.
     */
    @Nullable
    private Drawable customThumb;
    /**
     * Back callback of Android 13 and later, or null if none is registered.
     */
    @Nullable
    private Object backCallback;

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            update();
            if (isShowing()) {
                handler.postDelayed(this, UPDATE_INTERVAL_MILLISECONDS);
            }
        }
    };

    private final Consumer<PlayerType> playerTypeObserver = type -> {
        // The panel is part of the activity, so it would be shown in the Picture-in-Picture window.
        if (type == PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE) {
            removePanel();
        } else if (!type.isMaximizedOrFullscreen()) {
            // The player is swiped down to the miniplayer, or is closed.
            Logger.printDebug(() -> "Closing the On-the-go panel, player type: " + type);
            dismiss();
        }
    };

    private final View.OnLayoutChangeListener rootLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updatePanelHeight();

    private OnTheGoSheet(Activity activity) {
        this.activity = activity;
    }

    private boolean isShowing() {
        return showing && !dismissing;
    }

    @SuppressLint("ClickableViewAccessibility")
    @SuppressWarnings("WrongThread")
    private void create() {
        Context context = activity;
        if (!(activity.findViewById(android.R.id.content) instanceof FrameLayout root)) {
            Logger.printException(() -> "Could not show the On-the-go panel, content view not found");
            return;
        }

        final int backgroundColor = ThemeUtils.getDialogBackgroundColor();
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(backgroundColor);
        // Touches of the panel do not reach the views below it.
        panel.setClickable(true);

        // Drag handle and header, which can be dragged down to close the panel.
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setOnTouchListener(new DragToDismissListener());
        panel.addView(top, matchWidth(0));

        View handle = new View(context);
        ShapeDrawable handleBackground = new ShapeDrawable(new RoundRectShape(Dim.roundedCorners(4), null, null));
        handleBackground.getPaint().setColor(Utils.adjustColorBrightness(backgroundColor, 0.9f, 1.25f));
        handle.setBackground(handleBackground);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(Dim.dp40, Dim.dp4);
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = Dim.dp8;
        top.addView(handle, handleParams);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(CONTENT_SIDE_PADDING, 0, Dim.dp4, 0);
        TextView headerTitle = new TextView(context);
        headerTitle.setText(str("morphe_on_the_go_panel_title"));
        headerTitle.setTextColor(foregroundColor);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        headerTitle.setTypeface(headerTitle.getTypeface(), Typeface.BOLD);
        header.addView(headerTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView closeButton = createIconButton(Dim.dp48, Dim.dp12, v -> dismiss());
        setIcon(closeButton, "accessibility_bottom_sheet_close_button",
                "yt_outline_experimental_x_vd_theme_24", "yt_outline_x_white_24", "quantum_ic_close_white_24");
        header.addView(closeButton);
        top.addView(header, matchWidth(0));

        View divider = new View(context);
        divider.setBackgroundColor(buttonBackgroundColor);
        panel.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp1));

        ScrollView scrollView = createScrollView(context);
        panel.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(CONTENT_SIDE_PADDING, Dim.dp16, CONTENT_SIDE_PADDING, Dim.dp16);
        // The time bar extends into the side padding.
        body.setClipToPadding(false);
        scrollView.addView(body);

        thumbnail = new ImageView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // 16:9, made smaller if the panel is too short for the full width.
                int width = MeasureSpec.getSize(widthMeasureSpec);
                int height = Math.round(width / THUMBNAIL_ASPECT_RATIO);
                if (height > thumbnailMaxHeight) {
                    height = thumbnailMaxHeight;
                    width = Math.round(height * THUMBNAIL_ASPECT_RATIO);
                }
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
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
        LinearLayout.LayoutParams thumbnailParams = matchWidth(Dim.dp16);
        thumbnailParams.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(thumbnail, thumbnailParams);

        titleView = new TextView(context);
        titleView.setTextColor(foregroundColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setMaxLines(3);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(titleView, matchWidth(Dim.dp4));

        channelView = new TextView(context);
        channelView.setTextColor(mutedColor);
        channelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        channelView.setSingleLine(true);
        channelView.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(channelView, matchWidth(0));

        // Takes the free height of the panel in audio mode.
        View spacer = new View(context);
        spacer.setMinimumHeight(Dim.dp24);
        body.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        timeView = new TextView(context);
        timeView.setTextColor(foregroundColor);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.addView(timeView, matchWidth(Dim.dp4));

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
        // The thumb extends past the ends of the bar by the thumb offset, so the side padding
        // must be at least that, or the thumb is cut off at the start and end of the video.
        final int thumbOffset = timeBar.getThumbOffset();
        timeBar.setPadding(thumbOffset, Dim.dp8, thumbOffset, Dim.dp8);
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
        // Move the padding into the side padding of the panel, so the bar lines up with the text.
        LinearLayout.LayoutParams timeBarParams = matchWidth(Dim.dp24);
        final int timeBarMargin = -Math.min(thumbOffset, CONTENT_SIDE_PADDING);
        timeBarParams.leftMargin = timeBarMargin;
        timeBarParams.rightMargin = timeBarMargin;
        body.addView(timeBar, timeBarParams);

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        final int controlPadding = Dim.dp6;
        final int controlSize = CONTROL_ICON_SIZE + 2 * controlPadding;
        previousButton = createIconButton(controlSize, controlPadding,
                v -> clickPlayerButton("player_control_previous_button"));
        setIcon(previousButton, "accessibility_previous",
                "yt_fill_experimental_skip_previous_vd_theme_24", "quantum_ic_skip_previous_white_24");
        addCell(controls, previousButton);
        seekBackButton = createIconButton(controlSize, controlPadding,
                v -> VideoInformation.seekToRelative(-OnTheGoPatch.getSeekLengthMilliseconds()));
        addCell(controls, seekBackButton);
        playPauseButton = createIconButton(PLAY_PAUSE_SIZE, (PLAY_PAUSE_SIZE - CONTROL_ICON_SIZE) / 2,
                v -> clickPlayerButton("player_control_play_pause_replay_button"));
        GradientDrawable playPauseBackground = new GradientDrawable();
        playPauseBackground.setShape(GradientDrawable.OVAL);
        playPauseBackground.setColor(buttonBackgroundColor);
        playPauseButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(buttonBackgroundColor), playPauseBackground, null));
        addCell(controls, playPauseButton);
        seekForwardButton = createIconButton(controlSize, controlPadding,
                v -> VideoInformation.seekToRelative(OnTheGoPatch.getSeekLengthMilliseconds()));
        addCell(controls, seekForwardButton);
        ImageView nextButton = createIconButton(controlSize, controlPadding,
                v -> clickPlayerButton("player_control_next_button"));
        setIcon(nextButton, "accessibility_next",
                "yt_fill_experimental_skip_next_vd_theme_24", "quantum_ic_skip_next_white_24");
        addCell(controls, nextButton);
        body.addView(controls, matchWidth(Dim.dp20));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        // Download and Queue are only shown if the patches of these features are included.
        if (DownloadsPatch.canDownloadWithoutAppButton()) {
            ImageView downloadButton = createIconButton(Dim.dp48, Dim.dp12, v -> downloadVideo());
            setIcon(downloadButton, "morphe_on_the_go_download",
                    "yt_outline_experimental_download_vd_theme_24", "yt_outline_download_black_24");
            addCell(actions, downloadButton);
        }
        audioModeButton = createIconButton(Dim.dp48, Dim.dp12, v -> setAudioMode(!audioMode));
        addCell(actions, audioModeButton);
        View speedButton = createSpeedButton();
        speedButton.setOnClickListener(v ->
                CustomPlaybackSpeedPatch.showModernCustomPlaybackSpeedDialog(activity));
        addCell(actions, speedButton);
        if (AddToQueuePatch.isCustomQueueEnabled()) {
            ImageView queueButton = createIconButton(Dim.dp48, Dim.dp12, v -> openQueue());
            setIcon(queueButton, "morphe_on_the_go_queue",
                    "yt_outline_experimental_queue_vd_theme_24", "yt_outline_list_view_black_24");
            addCell(actions, queueButton);
        }
        body.addView(actions, matchWidth(0));


        current = new WeakReference<>(this);
        showing = true;
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View view) {
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View view) {
                onPanelRemoved(root);
            }
        });
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, root.getHeight(), Gravity.BOTTOM));
        root.addOnLayoutChangeListener(rootLayoutListener);
        PlayerType.getOnChange().addObserver(playerTypeObserver);
        registerBackHandling();
        setAudioMode(audioMode);

        panel.setTranslationY(root.getHeight());
        panel.animate().translationY(0).setDuration(ANIMATION_DURATION_MILLISECONDS).start();

        OnTheGoPatch.setOnTheGoActive(true);
        update();
        handler.postDelayed(updater, UPDATE_INTERVAL_MILLISECONDS);
    }

    /**
     * @return The scroll view of the body, which stretches it to the panel height,
     *         and makes the thumbnail smaller if the controls do not fit.
     */
    private ScrollView createScrollView(Context context) {
        ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                thumbnailMaxHeight = Integer.MAX_VALUE;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                // In audio mode the thumbnail is made smaller if the controls do not fit,
                // so the controls stay visible without scrolling.
                if (getChildCount() > 0 && thumbnail.getVisibility() == View.VISIBLE) {
                    final int overflow = getChildAt(0).getMeasuredHeight() - getMeasuredHeight();
                    if (overflow > 0) {
                        thumbnailMaxHeight = Math.max(0, thumbnail.getMeasuredHeight() - overflow);
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    }
                }
            }
        };
        scrollView.setVerticalScrollBarEnabled(false);
        // Stretches the content to the panel height, so the controls are at the bottom.
        scrollView.setFillViewport(true);
        return scrollView;
    }

    /**
     * Closes the panel with the slide animation.
     */
    private void dismiss() {
        if (!isShowing()) {
            return;
        }
        dismissing = true;
        panel.animate().cancel();
        panel.animate().translationY(panel.getHeight()).setDuration(ANIMATION_DURATION_MILLISECONDS)
                .withEndAction(this::removePanel).start();
    }

    /**
     * Closes the panel at once.
     */
    private void removePanel() {
        panel.animate().cancel();
        if (panel.getParent() instanceof ViewGroup parent) {
            parent.removeView(panel);
        }
    }

    /**
     * Called when the panel is closed, or when the activity is closed.
     */
    private void onPanelRemoved(ViewGroup root) {
        if (!showing) {
            return;
        }
        showing = false;
        try {
            OnTheGoPatch.setOnTheGoActive(false);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not turn off On-the-go media controls", ex);
        }
        handler.removeCallbacks(updater);
        root.removeOnLayoutChangeListener(rootLayoutListener);
        PlayerType.getOnChange().removeObserver(playerTypeObserver);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            unregisterBackCallback();
        }
        if (customThumb instanceof Animatable animatable) {
            animatable.stop();
        }
        if (current != null && current.get() == this) {
            current = null;
        }
    }

    /**
     * Back closes the panel. Android 13 and later can send back through a callback
     * instead of a key event, depending on the app, so both are handled.
     */
    private void registerBackHandling() {
        panel.setFocusableInTouchMode(true);
        panel.requestFocus();
        panel.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                dismiss();
            }
            return true;
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerBackCallback();
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void registerBackCallback() {
        OnBackInvokedCallback callback = this::dismiss;
        activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
        backCallback = callback;
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void unregisterBackCallback() {
        if (backCallback instanceof OnBackInvokedCallback callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        }
        backCallback = null;
    }

    /**
     * Sets the panel height. In video mode the panel starts at the bottom of the video,
     * as the panel of the app, and in audio mode it starts at the top of the video, so it covers it.
     */
    private void updatePanelHeight() {
        try {
            if (!(panel.getParent() instanceof ViewGroup root) || root.getHeight() == 0) {
                return;
            }
            final int rootHeight = root.getHeight();
            int[] rootLocation = new int[2];
            root.getLocationOnScreen(rootLocation);

            // System bar insets of the parts of the screen the content view is drawn below.
            int topInset = 0;
            int bottomInset = 0;
            WindowInsets insets = root.getRootWindowInsets();
            if (insets != null) {
                final int screenHeight = root.getRootView().getHeight();
                topInset = Math.max(0, getTopInset(insets) - rootLocation[1]);
                bottomInset = Math.max(0, getBottomInset(insets)
                        - (screenHeight - rootLocation[1] - rootHeight));
            }

            int panelTop = topInset;
            View player = activity.findViewById(ResourceUtils.getIdentifier(
                    activity, ResourceType.ID, "watch_player"));
            if (player != null && player.isShown()) {
                int[] location = new int[2];
                player.getLocationOnScreen(location);
                final int playerTop = location[1] - rootLocation[1];
                final int playerBottom = playerTop + player.getHeight();
                if (audioMode) {
                    panelTop = Math.max(topInset, playerTop);
                } else if (rootHeight - playerBottom >= rootHeight / 3) {
                    // Otherwise the video fills the screen, such as in landscape, and the panel covers it.
                    panelTop = playerBottom;
                }
            }

            if (panel.getPaddingBottom() != bottomInset) {
                panel.setPadding(0, 0, 0, bottomInset);
            }
            final int height = rootHeight - panelTop;
            ViewGroup.LayoutParams params = panel.getLayoutParams();
            if (params.height != height) {
                params.height = height;
                panel.setLayoutParams(params);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updatePanelHeight failure", ex);
        }
    }

    @SuppressWarnings("deprecation")
    private static int getTopInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top;
        }
        return insets.getSystemWindowInsetTop();
    }

    @SuppressWarnings("deprecation")
    private static int getBottomInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.systemBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    /**
     * Moves the panel with the finger, and closes it if it is dragged far or fast enough.
     */
    private final class DragToDismissListener implements View.OnTouchListener {
        private float startY;
        /** If the finger moved more than the touch slop, so the touch is not a tap. */
        private boolean dragged;
        @Nullable
        private VelocityTracker velocityTracker;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (dismissing) {
                return true;
            }
            // Raw coordinates, as the view moves with the finger.
            MotionEvent screenEvent = MotionEvent.obtain(event);
            screenEvent.setLocation(event.getRawX(), event.getRawY());
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        startY = event.getRawY();
                        dragged = false;
                        panel.animate().cancel();
                        if (velocityTracker != null) {
                            velocityTracker.recycle();
                        }
                        velocityTracker = VelocityTracker.obtain();
                        velocityTracker.addMovement(screenEvent);
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        if (velocityTracker != null) {
                            velocityTracker.addMovement(screenEvent);
                        }
                        if (Math.abs(event.getRawY() - startY)
                                > ViewConfiguration.get(view.getContext()).getScaledTouchSlop()) {
                            dragged = true;
                        }
                        panel.setTranslationY(Math.max(0, event.getRawY() - startY));
                    }
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        float velocity = 0;
                        if (velocityTracker != null) {
                            velocityTracker.addMovement(screenEvent);
                            velocityTracker.computeCurrentVelocity(1000);
                            velocity = velocityTracker.getYVelocity();
                            velocityTracker.recycle();
                            velocityTracker = null;
                        }
                        if (panel.getTranslationY() > panel.getHeight() * DISMISS_DRAG_FRACTION
                                || velocity > DISMISS_FLING_VELOCITY) {
                            dismiss();
                        } else {
                            panel.animate().translationY(0).setDuration(ANIMATION_DURATION_MILLISECONDS).start();
                        }
                        if (event.getActionMasked() == MotionEvent.ACTION_UP && !dragged) {
                            // A tap. The header has no click action, so this only reports the click
                            // to accessibility services, as any view does.
                            view.performClick();
                        }
                    }
                }
            } finally {
                screenEvent.recycle();
            }
            return true;
        }
    }

    /**
     * Audio mode shows the thumbnail and covers the video, as the audio mode of the app.
     * Video mode leaves the video of the player visible above the panel.
     */
    private void setAudioMode(boolean enabled) {
        audioMode = enabled;
        thumbnail.setVisibility(enabled ? View.VISIBLE : View.GONE);
        updatePanelHeight();
        if (enabled) {
            setIcon(audioModeButton, "morphe_on_the_go_video_mode",
                    "yt_outline_experimental_video_vd_theme_24", "quantum_ic_play_arrow_white_24");
            loadThumbnailIfNeeded();
        } else {
            setIcon(audioModeButton, "morphe_on_the_go_audio_mode",
                    "yt_outline_experimental_headset_vd_theme_24", "quantum_ic_headset_white_24");
        }
    }

    /**
     * Downloads the video with the download mode of the downloads patch.
     */
    private void downloadVideo() {
        try {
            String videoId = VideoInformation.getVideoId();
            if (videoId.isEmpty()) {
                Logger.printDebug(() -> "Cannot download, no video id");
                return;
            }
            DownloadsPatch.downloadVideoWithoutAppButton(videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "downloadVideo failure", ex);
        }
    }

    /**
     * Opens the queue of the queue type of the add to queue patch.
     * The local queue is shown, and the Morphe queue shows its menu for the video.
     */
    private void openQueue() {
        try {
            if (LocalQueuePatch.isEnabled()) {
                LocalQueueSheet.show(activity);
            } else {
                PlaylistPatch.prepareDialogBuilder(activity, VideoInformation.getVideoId());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "openQueue failure", ex);
        }
    }

    /**
     * @return The speed icon of the app, or a text with the speed if the app has no speed icon.
     */
    private View createSpeedButton() {
        final String iconName = "yt_outline_experimental_speedometer_vd_theme_24";
        if (ResourceUtils.getDrawableIdentifier(iconName) != 0) {
            ImageView icon = createIconButton(Dim.dp48, Dim.dp12, null);
            setIcon(icon, "playback_rate_title", iconName);
            return icon;
        }

        speedText = new TextView(activity);
        speedText.setTextColor(foregroundColor);
        speedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        speedText.setTypeface(speedText.getTypeface(), Typeface.BOLD);
        speedText.setGravity(Gravity.CENTER);
        speedText.setMinHeight(Dim.dp48);
        speedText.setPadding(Dim.dp16, 0, Dim.dp16, 0);
        speedText.setContentDescription(str("playback_rate_title"));
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(Dim.dp24);
        mask.setColor(Color.WHITE);
        speedText.setBackground(new RippleDrawable(ColorStateList.valueOf(buttonBackgroundColor), null, mask));
        return speedText;
    }

    /**
     * @param padding Padding around the icon, which is scaled to fit the rest of the size.
     */
    private ImageView createIconButton(int size, int padding, @Nullable View.OnClickListener listener) {
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
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(padding, padding, padding, padding);
        button.setImageTintList(ColorStateList.valueOf(foregroundColor));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(buttonBackgroundColor), null, mask));
        button.setOnClickListener(listener);
        return button;
    }

    /**
     * Sets the first icon of the app that exists.
     */
    private static void setIcon(ImageView button, String descriptionName, String... drawableNames) {
        button.setContentDescription(str(descriptionName));
        setFirstIcon(button, drawableNames);
    }

    /**
     * Sets the first icon of the app that exists, without changing the content description.
     */
    private static void setFirstIcon(ImageView button, String... drawableNames) {
        for (String name : drawableNames) {
            if (ResourceUtils.getDrawableIdentifier(name) != 0) {
                Drawable icon = ResourceUtils.getDrawable(name);
                if (icon != null) {
                    button.setImageDrawable(icon);
                    return;
                }
            }
        }
        Logger.printException(() -> "Could not find any icon of: " + Arrays.toString(drawableNames));
    }

    /**
     * Sets the icons of the seek buttons for the seek length of the app settings.
     */
    private void setSeekIcons(long seekLength) {
        final int seconds = (int) (seekLength / 1000);
        boolean hasNumberIcon = false;
        for (int iconSeconds : SEEK_ICON_SECONDS) {
            if (iconSeconds == seconds) {
                hasNumberIcon = true;
                break;
            }
        }

        if (hasNumberIcon) {
            setFirstIcon(seekBackButton,
                    "yt_outline_experimental_skip" + seconds + "_back_vd_theme_24",
                    "yt_fill_skip_back_" + seconds + "_vd_theme_24",
                    "quantum_ic_fast_rewind_white_24");
            setFirstIcon(seekForwardButton,
                    "yt_outline_experimental_skip" + seconds + "_forward_vd_theme_24",
                    "yt_fill_skip_forward_" + seconds + "_vd_theme_24",
                    "quantum_ic_fast_forward_white_24");
        } else {
            setFirstIcon(seekBackButton, "quantum_ic_fast_rewind_white_24");
            setFirstIcon(seekForwardButton, "quantum_ic_fast_forward_white_24");
        }
        seekBackButton.setContentDescription(str("double_tap_seek_rewind_accessibility_text", seconds));
        seekForwardButton.setContentDescription(str("double_tap_seek_forward_accessibility_text", seconds));
    }

    /**
     * Adds a view centered in an equal share of the row width.
     * The view is not stretched to the share, so it keeps its size.
     */
    private static void addCell(LinearLayout row, View view) {
        FrameLayout cell = new FrameLayout(row.getContext());
        cell.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.gravity = Gravity.CENTER_VERTICAL;
        row.addView(cell, params);
    }

    private static LinearLayout.LayoutParams matchWidth(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, bottomMargin);
        return params;
    }

    /**
     * @return The button of the player, which can be clicked while the player controls are hidden.
     */
    @Nullable
    private View findPlayerButton(String idName) {
        final int id = ResourceUtils.getIdentifier(activity, ResourceType.ID, idName);
        // The player can have more than one view with the same id, as the play button has.
        View group = activity.findViewById(ResourceUtils.getIdentifier(
                activity, ResourceType.ID, "controls_button_group_layout"));
        View button = group != null ? group.findViewById(id) : null;
        if (button == null) {
            button = activity.findViewById(id);
        }
        return button;
    }

    private void clickPlayerButton(String idName) {
        View button = findPlayerButton(idName);
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
            if (!isShowing()) {
                return;
            }
            if (PlayerType.getCurrent().isNoneOrHidden()) {
                dismiss();
                return;
            }
            updatePanelHeight();

            String id = VideoInformation.getVideoId();
            if (!id.equals(videoId)) {
                videoId = id;
                loadThumbnailIfNeeded();
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
                    setIcon(playPauseButton, "accessibility_pause", "yt_fill_experimental_pause_vd_theme_36",
                            "yt_fill_experimental_pause_vd_theme_24", "quantum_ic_pause_white_36");
                } else if (state == VideoState.ENDED) {
                    setIcon(playPauseButton, "accessibility_replay", "yt_outline_experimental_replay_vd_theme_36",
                            "yt_outline_experimental_replay_vd_theme_24", "quantum_ic_replay_white_36");
                } else {
                    setIcon(playPauseButton, "accessibility_play", "yt_fill_experimental_play_vd_theme_36",
                            "yt_fill_experimental_play_vd_theme_24", "quantum_ic_play_arrow_white_36");
                }
            }

            final long seekLength = OnTheGoPatch.getSeekLengthMilliseconds();
            if (seekLength != shownSeekLength) {
                shownSeekLength = seekLength;
                setSeekIcons(seekLength);
            }

            // The player hides the previous button if there is no previous video.
            View playerPrevious = findPlayerButton("player_control_previous_button");
            final boolean hasPrevious = playerPrevious != null
                    && playerPrevious.getVisibility() == View.VISIBLE && playerPrevious.isEnabled();
            previousButton.setEnabled(hasPrevious);
            previousButton.setAlpha(hasPrevious ? 1 : DISABLED_ALPHA);

            if (speedText != null) {
                setTextIfChanged(speedText, VideoInformation.formatSpeedStringX(
                        VideoInformation.getPlaybackSpeed()));
            }
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

    /**
     * Loads the thumbnail of the video, if audio mode is on and the thumbnail is not loaded yet.
     */
    private void loadThumbnailIfNeeded() {
        String id = videoId;
        if (!audioMode || id == null || id.equals(thumbnailVideoId)) {
            return;
        }

        thumbnailVideoId = id;
        thumbnail.setImageDrawable(null);
        if (!id.isEmpty()) {
            loadThumbnail(id);
        }
    }

    private void loadThumbnail(String id) {
        Utils.runOnBackgroundThread(() -> {
            Bitmap bitmap = downloadThumbnail(id);
            if (bitmap != null) {
                Utils.runOnMainThread(() -> {
                    if (id.equals(thumbnailVideoId)) {
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
