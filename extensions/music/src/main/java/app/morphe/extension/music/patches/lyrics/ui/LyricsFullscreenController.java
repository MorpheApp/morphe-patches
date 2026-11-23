/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;

import app.morphe.extension.music.patches.applemusicartwork.AppleMusicArtworkPatch;
import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("deprecation")
final class LyricsFullscreenController {

    private static final String ENTER_ICON = "morphe_fullscreen_enter";
    private static final String EXIT_ICON = "morphe_fullscreen_exit";
    private static final int ART_CORNER_DP = 6;

    private final ViewOutlineProvider artOutline = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w <= 0 || h <= 0) {
                outline.setEmpty();
                return;
            }
            outline.setRoundRect(0, 0, w, h, Dim.dp(ART_CORNER_DP));
        }
    };

    private final LyricsPanelView panel;
    private final TextView button;
    private final ImageView artworkView;
    private final TextView titleView;
    private final TextView artistView;
    @Nullable private LinearLayout trackInfo;
    private final AppleMusicArtworkPatch.ArtworkFileListener artworkFileListener =
            this::onArtworkFileChanged;
    private final View.OnTouchListener coverTouchListener = this::onCoverTouch;
    private float coverDownX;
    private float coverDownY;
    private long coverDownTime;

    private boolean isFullscreen;
    @Nullable private ViewGroup originalParent;
    private int originalIndex;
    @Nullable private ViewGroup.LayoutParams originalParams;
    @Nullable private LinearLayout splitRoot;
    @Nullable private FrameLayout artPane;
    private int scrollOriginalIndex;
    @Nullable private ViewGroup.LayoutParams scrollOriginalParams;
    @Nullable private View animatedArtworkView;
    @Nullable private ValueAnimator coverAnimator;
    private boolean splitActive;
    private boolean controlsInRow;
    @Nullable private ViewGroup buttonHome;
    private int buttonHomeIndex;
    @Nullable private ViewGroup.LayoutParams buttonHomeParams;
    @Nullable private ViewGroup jumpHome;
    private int jumpHomeIndex;
    @Nullable private ViewGroup.LayoutParams jumpHomeParams;

    LyricsFullscreenController(LyricsPanelView panel) {
        this.panel = panel;
        Context context = panel.getContext();

        artworkView = new ImageView(context);
        artworkView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkView.setOutlineProvider(artOutline);
        artworkView.setClipToOutline(true);
        artworkView.setVisibility(View.GONE);

        titleView = createMarqueeText(context, 18f, Typeface.BOLD, 0xFFFFFFFF);
        artistView = createMarqueeText(context, 13f, Typeface.NORMAL, 0xFFD2D2DC);

        button = new TextView(context);
        panel.applyButtonStyle(button, ENTER_ICON);
        button.setContentDescription(str("morphe_music_lyrics_fullscreen_enter"));
        button.setVisibility(View.GONE);
        button.setAlpha(0f);
        button.setElevation(Dim.dp(8));
        button.setOnClickListener(v -> toggle());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        params.bottomMargin = Dim.dp(96);
        params.leftMargin = Dim.dp16;
        panel.addView(button, params);
        button.bringToFront();
    }

    boolean isFullscreen() {
        return isFullscreen;
    }

    boolean isTouchInsideButton(MotionEvent event) {
        return panel.isTouchInside(button, event, Dim.dp(8));
    }

    void applySetting() {
        if (!Settings.LYRICS_SHOW_FULLSCREEN_BUTTON.get()) {
            hideButton();
        } else if (panel.isBrowsing()) {
            showButton();
        }
    }

    void showButton() {
        if (!Settings.LYRICS_SHOW_FULLSCREEN_BUTTON.get()) return;
        button.bringToFront();
        button.animate().cancel();
        if (button.getVisibility() == View.VISIBLE && button.getAlpha() == 1f) return;
        button.setAlpha(0f);
        button.setVisibility(View.VISIBLE);
        button.animate().alpha(1f).setDuration(LyricsPanelView.JUMP_BUTTON_FADE_MS).start();
        if (isFullscreen) showToolbar();
    }

    void hideButton() {
        button.animate().cancel();
        button.setAlpha(0f);
        button.setVisibility(View.GONE);
        if (isFullscreen) hideToolbar();
    }

    boolean onBackPressed() {
        if (!isFullscreen) return false;
        exit();
        return true;
    }

    void onConfigurationChanged() {
        if (isFullscreen) applyLayout();
    }

    void onSizeChanged() {
        if (isFullscreen) applyLayout();
    }

    void onDetached() {
        if (!isFullscreen) return;
        setSystemBarsHidden(false);
        if (splitActive) setSplitActive(false);
        applyControlButtonsForSplit(false);
        isFullscreen = false;
        originalParent = null;
        originalParams = null;
    }

    void onLyricsChanged() {
        if (!isFullscreen) return;
        updateArtwork();
        if (splitActive) {
            refreshTrackInfo();
            File file = AppleMusicArtworkPatch.currentArtworkFile();
            if (file == null) {
                unbindArtworkOverlay();
                startCoverAnimator();
            } else if (animatedArtworkView == null) {
                bindArtworkOverlay();
            }
        }
    }

    private void toggle() {
        if (isFullscreen) exit();
        else enter();
    }

    private void enter() {
        Activity activity = Utils.getActivity();
        ViewGroup parent = (ViewGroup) panel.getParent();
        if (activity == null || parent == null) return;
        originalParent = parent;
        originalIndex = parent.indexOfChild(panel);
        originalParams = panel.getLayoutParams();
        parent.removeView(panel);
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        panel.bringToFront();
        panel.setFocusable(true);
        panel.requestFocus();
        isFullscreen = true;
        applyButtonLook();
        hideToolbar();
        setSystemBarsHidden(true);
        applyLayout();
    }

    private void exit() {
        ViewGroup restoreParent = originalParent;
        ViewGroup.LayoutParams restoreParams = originalParams;
        int restoreIndex = originalIndex;
        isFullscreen = false;
        ViewGroup content = (ViewGroup) panel.getParent();
        if (content != null) content.removeView(panel);
        if (restoreParent != null && restoreParams != null) {
            try {
                int index = Math.min(restoreIndex, restoreParent.getChildCount());
                restoreParent.addView(panel, index, restoreParams);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not restore lyrics panel after fullscreen", ex);
            }
        }
        originalParent = null;
        originalParams = null;
        isFullscreen = false;
        applyButtonLook();
        panel.restoreButtonRowAfterFullscreen();
        setSystemBarsHidden(false);
        applyLayout();
    }

    private void applyButtonLook() {
        panel.applyButtonStyle(button, isFullscreen ? EXIT_ICON : ENTER_ICON);
        button.setContentDescription(str(isFullscreen
                ? "morphe_music_lyrics_fullscreen_exit"
                : "morphe_music_lyrics_fullscreen_enter"));
    }

    private void applyLayout() {
        boolean split = isFullscreen
                && panel.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (split != splitActive) {
            setSplitActive(split);
        }
        applyControlButtonsForSplit(split);
        if (split) {
            updateArtwork();
            updateArtworkSize();
        }
    }

    /** Joins the fullscreen and jump buttons to the four-button toolbar so all six sit centred. */
    private void applyControlButtonsForSplit(boolean split) {
        if (split == controlsInRow) {
            return;
        }
        ViewGroup row = panel.getButtonRow();
        View jump = panel.getJumpButton();
        if (split) {
            buttonHome = (ViewGroup) button.getParent();
            buttonHomeIndex = buttonHome.indexOfChild(button);
            buttonHomeParams = button.getLayoutParams();
            buttonHome.removeView(button);
            jumpHome = (ViewGroup) jump.getParent();
            jumpHomeIndex = jumpHome.indexOfChild(jump);
            jumpHomeParams = jump.getLayoutParams();
            jumpHome.removeView(jump);
            LinearLayout.LayoutParams first = rowButtonParams(false);
            first.setMarginEnd(Dim.dp12);
            row.addView(button, 0, first);
            row.addView(jump, rowButtonParams(true));
        } else {
            row.removeView(button);
            row.removeView(jump);
            if (buttonHome != null && buttonHomeParams != null) {
                buttonHome.addView(button,
                        Math.min(buttonHomeIndex, buttonHome.getChildCount()), buttonHomeParams);
            }
            if (jumpHome != null && jumpHomeParams != null) {
                jumpHome.addView(jump,
                        Math.min(jumpHomeIndex, jumpHome.getChildCount()), jumpHomeParams);
            }
            buttonHome = null;
            buttonHomeParams = null;
            jumpHome = null;
            jumpHomeParams = null;
        }
        controlsInRow = split;
    }

    /** Re-inserts the two controls after the toolbar rebuilt its child buttons. */
    void onToolbarRebuilt() {
        if (!controlsInRow) {
            return;
        }
        ViewGroup row = panel.getButtonRow();
        row.addView(button, 0, rowButtonParams(false));
        row.addView(panel.getJumpButton(), rowButtonParams(true));
    }

    private static TextView createMarqueeText(
            Context context, float sizeSp, int style, int color) {
        TextView text = new TextView(context);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp);
        text.setTypeface(null, style);
        text.setTextColor(color);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        text.setMarqueeRepeatLimit(-1);
        text.setFocusable(true);
        text.setFocusableInTouchMode(false);
        text.setHorizontalFadingEdgeEnabled(true);
        text.setFadingEdgeLength(Dim.dp16);
        text.setGravity(Gravity.CENTER);
        text.setVisibility(View.GONE);
        return text;
    }

    /** Builds the cover caption below the artwork, with a fading marquee for long names. */
    private LinearLayout buildTrackInfo() {
        LinearLayout info = new LinearLayout(panel.getContext());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, Dim.dp10, 0, 0);
        info.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        info.addView(artistView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return info;
    }

    /** Refreshes the caption from the current track and restarts the marquee with a fade/slide. */
    private void refreshTrackInfo() {
        LyricsManager manager = LyricsManager.getInstance();
        String title = manager.getCurrentRawTitle();
        String artist = manager.getCurrentRawArtist();
        titleView.setText(title != null ? title : "");
        artistView.setText(artist != null ? artist : "");
        if (titleView.getVisibility() != View.VISIBLE) {
            titleView.setVisibility(View.VISIBLE);
            artistView.setVisibility(View.VISIBLE);
        }
        if (trackInfo == null) {
            return;
        }
        titleView.setSelected(false);
        artistView.setSelected(false);
        titleView.setSelected(true);
        artistView.setSelected(true);
        trackInfo.animate().cancel();
        trackInfo.setAlpha(0f);
        trackInfo.setTranslationY(Dim.dp8);
        trackInfo.animate().alpha(1f).translationY(0f)
                .setDuration(LyricsPanelView.JUMP_BUTTON_FADE_MS).start();
    }

    private void onArtworkFileChanged(@Nullable File file) {
        if (!splitActive) {
            return;
        }
        unbindArtworkOverlay();
        if (file != null) {
            bindArtworkOverlay();
        } else {
            startCoverAnimator();
        }
    }

    private static LinearLayout.LayoutParams rowButtonParams(boolean marginStart) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (marginStart) {
            params.setMarginStart(Dim.dp12);
        }
        return params;
    }

    /** Tap toggles playback; a horizontal swipe skips to the previous or next track. */
    private boolean onCoverTouch(View v, MotionEvent event) {
        panel.requestDisallowInterceptTouchEvent(true);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                coverDownX = event.getX();
                coverDownY = event.getY();
                coverDownTime = SystemClock.uptimeMillis();
                return true;
            case MotionEvent.ACTION_UP: {
                float dx = event.getX() - coverDownX;
                float dy = event.getY() - coverDownY;
                long elapsed = SystemClock.uptimeMillis() - coverDownTime;
                if (Math.abs(dx) > Dim.dp(40) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    dispatchMediaKey(v.getContext(),
                            dx < 0 ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                } else if (Math.abs(dx) < Dim.dp(12) && Math.abs(dy) < Dim.dp(12)
                        && elapsed < 600L) {
                    dispatchMediaKey(v.getContext(), KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    private static void dispatchMediaKey(Context context, int keyCode) {
        if (!(context.getSystemService(Context.AUDIO_SERVICE) instanceof AudioManager audio)) {
            return;
        }
        try {
            long now = SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not dispatch cover playback key", ex);
        }
    }

    private void setSplitActive(boolean active) {
        View scroll = panel.getScrollView();
        if (active) {
            LinearLayout root = new LinearLayout(panel.getContext());
            root.setOrientation(LinearLayout.HORIZONTAL);
            scrollOriginalIndex = panel.indexOfChild(scroll);
            scrollOriginalParams = scroll.getLayoutParams();
            panel.removeView(scroll);
            FrameLayout pane = new FrameLayout(panel.getContext());
            pane.setOnTouchListener(coverTouchListener);
            LinearLayout coverStack = new LinearLayout(panel.getContext());
            coverStack.setOrientation(LinearLayout.VERTICAL);
            coverStack.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout coverFrame = new FrameLayout(panel.getContext());
            coverFrame.addView(artworkView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            coverStack.addView(coverFrame, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            trackInfo = buildTrackInfo();
            coverStack.addView(trackInfo, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            pane.addView(coverStack, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            pane.addOnLayoutChangeListener((v, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> updateArtworkSize());
            root.addView(pane, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 0.45f));
            root.addView(scroll, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 0.55f));
            artworkView.setVisibility(View.VISIBLE);
            splitRoot = root;
            artPane = pane;
            panel.addView(root, 1, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            AppleMusicArtworkPatch.addArtworkFileListener(artworkFileListener);
            refreshTrackInfo();
            bindArtworkOverlay();
        } else {
            unbindArtworkOverlay();
            AppleMusicArtworkPatch.removeArtworkFileListener(artworkFileListener);
            if (splitRoot != null) {
                panel.removeView(splitRoot);
                splitRoot.removeAllViews();
            }
            titleView.setVisibility(View.GONE);
            artistView.setVisibility(View.GONE);
            trackInfo = null;
            artworkView.setVisibility(View.GONE);
            if (artPane != null) artPane.removeAllViews();
            artPane = null;
            panel.addView(scroll, scrollOriginalIndex,
                    scrollOriginalParams != null ? scrollOriginalParams : scroll.getLayoutParams());
            splitRoot = null;
        }
        splitActive = active;
    }

    private void updateArtworkSize() {
        if (artPane == null) return;
        int paneWidth = artPane.getWidth();
        int paneHeight = artPane.getHeight();
        if (paneWidth <= 0 || paneHeight <= 0) return;
        int reserved = trackInfo != null ? trackInfo.getHeight() : 0;
        int usableHeight = Math.max(0, paneHeight - reserved);
        int size = (int) (Math.min(paneWidth, usableHeight) * 0.65f);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) artworkView.getLayoutParams();
        if (params.width != size || params.height != size) {
            params.width = size;
            params.height = size;
            artworkView.setLayoutParams(params);
        }
        if (animatedArtworkView != null) {
            ViewGroup.LayoutParams animParams = animatedArtworkView.getLayoutParams();
            if (animParams == null || animParams.width != size || animParams.height != size) {
                animatedArtworkView.setLayoutParams(new FrameLayout.LayoutParams(size, size));
            }
        }
    }

    private void updateArtwork() {
        Bitmap artwork = LyricsManager.getInstance().getCurrentArtworkBitmap();
        if (artwork != null && !artwork.isRecycled()) {
            artworkView.setImageBitmap(artwork);
        } else {
            artworkView.setImageDrawable(null);
        }
    }

    private void bindArtworkOverlay() {
        unbindArtworkOverlay();
        animatedArtworkView = AppleMusicArtworkPatch.attachAnimatedArtwork(artworkView);
        if (animatedArtworkView != null) {
            animatedArtworkView.setOutlineProvider(artOutline);
            animatedArtworkView.setClipToOutline(true);
            animatedArtworkView.setOnTouchListener(coverTouchListener);
            updateArtworkSize();
        } else {
            startCoverAnimator();
        }
    }

    private void unbindArtworkOverlay() {
        if (animatedArtworkView != null) {
            AppleMusicArtworkPatch.detachAnimatedArtwork(animatedArtworkView);
            animatedArtworkView = null;
        }
        if (coverAnimator != null) {
            coverAnimator.cancel();
            coverAnimator = null;
        }
        artworkView.setScaleX(1f);
        artworkView.setScaleY(1f);
    }

    private void startCoverAnimator() {
        coverAnimator = ValueAnimator.ofFloat(1f, 1.06f);
        coverAnimator.setDuration(9000L);
        coverAnimator.setRepeatCount(ValueAnimator.INFINITE);
        coverAnimator.setRepeatMode(ValueAnimator.REVERSE);
        coverAnimator.addUpdateListener(animation -> {
            float scale = (Float) animation.getAnimatedValue();
            artworkView.setScaleX(scale);
            artworkView.setScaleY(scale);
        });
        coverAnimator.start();
    }

    private void showToolbar() {
        if (panel.isButtonRowHiddenForOffset()) return;
        ViewGroup row = panel.getButtonRow();
        row.animate().cancel();
        if (row.getVisibility() == View.VISIBLE && row.getAlpha() == 1f) return;
        row.setAlpha(0f);
        row.setVisibility(View.VISIBLE);
        row.animate().alpha(1f).setDuration(LyricsPanelView.JUMP_BUTTON_FADE_MS).start();
    }

    private void hideToolbar() {
        ViewGroup row = panel.getButtonRow();
        row.animate().cancel();
        row.setAlpha(0f);
        row.setVisibility(View.GONE);
    }

    private static void setSystemBarsHidden(boolean hidden) {
        Activity activity = Utils.getActivity();
        if (activity == null) return;
        int flags = hidden
                ? View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : 0;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
    }
}
