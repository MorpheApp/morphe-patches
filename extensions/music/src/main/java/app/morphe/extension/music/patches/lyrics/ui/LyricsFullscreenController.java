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
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.Objects;

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
    @Nullable
    private LinearLayout trackInfo;
    @Nullable
    private FrameLayout coverFrame;
    private boolean trackInfoVisible = true;
    @Nullable
    private String shownTitle;
    @Nullable
    private String shownArtist;
    @Nullable
    private Bitmap shownArtwork;
    private final AppleMusicArtworkPatch.ArtworkFileListener artworkFileListener =
            this::onArtworkFileChanged;
    private final View.OnTouchListener coverTouchListener = this::onCoverTouch;
    private float coverDownX;
    private float coverDownY;
    private long coverDownTime;

    private boolean isFullscreen;
    @Nullable
    private ViewGroup originalParent;
    private int originalIndex;
    @Nullable
    private ViewGroup.LayoutParams originalParams;
    @Nullable
    private LinearLayout splitRoot;
    @Nullable
    private FrameLayout artPane;
    private int scrollOriginalIndex;
    @Nullable
    private ViewGroup.LayoutParams scrollOriginalParams;
    private int promptOriginalIndex;
    @Nullable
    private ViewGroup.LayoutParams promptOriginalParams;
    @Nullable
    private FrameLayout lyricsPane;
    @Nullable
    private View animatedArtworkView;
    @Nullable
    private ValueAnimator coverAnimator;
    private boolean splitActive;
    private boolean controlsInRow;
    // Where the floating buttons go back to after the landscape split, kept from the start so a
    // toolbar rebuild cannot strand them.
    private final int buttonHomeIndex;
    private final ViewGroup.LayoutParams buttonHomeParams;
    private final int jumpHomeIndex;
    private final ViewGroup.LayoutParams jumpHomeParams;

    LyricsFullscreenController(LyricsPanelView panel) {
        this.panel = panel;
        Context context = panel.getContext();
        panel.setOnApplyWindowInsetsListener((v, insets) -> {
            if (splitActive) applyLyricsCutoutInset(insets);
            return insets;
        });

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
        buttonHomeIndex = panel.indexOfChild(button);
        buttonHomeParams = button.getLayoutParams();
        View jump = panel.getJumpButton();
        jumpHomeIndex = panel.indexOfChild(jump);
        jumpHomeParams = jump.getLayoutParams();
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
        if (button.getParent() == panel) {
            button.bringToFront();
        }
        button.animate().cancel();
        if (button.getVisibility() == View.VISIBLE && button.getAlpha() == 1f) return;
        button.setAlpha(0f);
        button.setVisibility(View.VISIBLE);
        if (controlsInRow) panel.normalizeButtonRowSpacing();
        button.animate().alpha(1f).setDuration(LyricsPanelView.JUMP_BUTTON_FADE_MS).start();
        if (isFullscreen) showToolbar();
    }

    void hideButton() {
        button.animate().cancel();
        button.setAlpha(0f);
        button.setVisibility(View.GONE);
        if (controlsInRow) panel.normalizeButtonRowSpacing();
        if (isFullscreen) hideToolbar();
    }

    void onBackPressed() {
        if (isFullscreen) exit();
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

    /** Picks up metadata that lands after the lyrics did. */
    void onTick() {
        if (!isFullscreen || !splitActive) return;
        LyricsManager manager = LyricsManager.getInstance();
        String title = manager.getCurrentRawTitle();
        String artist = manager.getCurrentRawArtist();
        if (!Objects.equals(title, shownTitle) || !Objects.equals(artist, shownArtist)) {
            refreshTrackInfo();
        }
        if (manager.getCurrentArtworkBitmap() != shownArtwork) {
            updateArtwork();
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

    private void applyControlButtonsForSplit(boolean split) {
        if (split == controlsInRow) {
            return;
        }
        ViewGroup row = panel.getButtonRow();
        View jump = panel.getJumpButton();
        detach(button);
        detach(jump);
        if (split) {
            row.addView(button, 0, rowButtonParams(false));
            row.addView(jump, rowButtonParams(true));
            panel.normalizeButtonRowSpacing();
        } else {
            panel.addView(button, Math.min(buttonHomeIndex, panel.getChildCount()), buttonHomeParams);
            panel.addView(jump, Math.min(jumpHomeIndex, panel.getChildCount()), jumpHomeParams);
        }
        controlsInRow = split;
    }

    void onToolbarRebuilt() {
        if (!controlsInRow) {
            return;
        }
        ViewGroup row = panel.getButtonRow();
        View jump = panel.getJumpButton();
        detach(button);
        detach(jump);
        row.addView(button, 0, rowButtonParams(false));
        row.addView(jump, rowButtonParams(true));
        panel.normalizeButtonRowSpacing();
    }

    private static void detach(@Nullable View view) {
        if (view != null && view.getParent() instanceof ViewGroup parent) {
            parent.removeView(view);
        }
    }

    private static TextView createMarqueeText(
            Context context, float sizeSp, int style, int color) {
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
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

    private LinearLayout buildTrackInfo() {
        LinearLayout info = new LinearLayout(panel.getContext());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, Dim.dp4, 0, 0);
        info.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        info.addView(artistView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return info;
    }

    private void refreshTrackInfo() {
        LyricsManager manager = LyricsManager.getInstance();
        String title = manager.getCurrentRawTitle();
        String artist = manager.getCurrentRawArtist();
        shownTitle = title;
        shownArtist = artist;
        titleView.setText(title != null ? title : "");
        artistView.setText(artist != null ? artist : "");
        if (titleView.getVisibility() != View.VISIBLE && trackInfoVisible) {
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

    private void toggleTrackInfo() {
        if (trackInfo == null) {
            return;
        }
        trackInfoVisible = !trackInfoVisible;
        int visibility = trackInfoVisible ? View.VISIBLE : View.GONE;
        titleView.setVisibility(visibility);
        artistView.setVisibility(visibility);
        trackInfo.setVisibility(visibility);
        if (trackInfoVisible) {
            titleView.setSelected(false);
            artistView.setSelected(false);
            titleView.setSelected(true);
            artistView.setSelected(true);
            trackInfo.animate().cancel();
            trackInfo.setAlpha(0f);
            trackInfo.animate().alpha(1f).setDuration(LyricsPanelView.JUMP_BUTTON_FADE_MS).start();
        }
    }

    private void onArtworkFileChanged(@Nullable File file) {
        if (!isFullscreen || !splitActive || splitRoot == null
                || splitRoot.getParent() == null || artworkView.getParent() == null) {
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
                } else if (Math.abs(dy) > Dim.dp(40) && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                    toggleTrackInfo();
                } else if (Math.abs(dx) < Dim.dp(12) && Math.abs(dy) < Dim.dp(12)
                        && elapsed < 600L) {
                    dispatchMediaKey(v.getContext(), KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                }
                return true;
            }
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
        View prompt = panel.getTranscribePromptView();
        if (active) {
            LinearLayout root = new LinearLayout(panel.getContext());
            root.setOrientation(LinearLayout.HORIZONTAL);
            scrollOriginalIndex = panel.indexOfChild(scroll);
            scrollOriginalParams = scroll.getLayoutParams();
            promptOriginalIndex = panel.indexOfChild(prompt);
            promptOriginalParams = prompt.getLayoutParams();
            detach(scroll);
            detach(prompt);
            FrameLayout pane = new FrameLayout(panel.getContext());
            pane.setOnTouchListener(coverTouchListener);
            LinearLayout coverStack = new LinearLayout(panel.getContext());
            coverStack.setOrientation(LinearLayout.VERTICAL);
            coverStack.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout coverFrame = new FrameLayout(panel.getContext());
            coverFrame.setOutlineProvider(artOutline);
            coverFrame.setClipToOutline(true);
            // Cover views are reused across rotations; clear any leftover parent first.
            detach(artworkView);
            detach(titleView);
            detach(artistView);
            coverFrame.addView(artworkView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            coverStack.addView(coverFrame, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            this.coverFrame = coverFrame;
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
            FrameLayout lyricsPane = new FrameLayout(panel.getContext());
            // Keep the right edge clear of a landscape punch-hole camera.
            lyricsPane.setPadding(0, 0, Dim.dp16, 0);
            lyricsPane.setClipChildren(false);
            lyricsPane.setClipToPadding(false);
            lyricsPane.addView(scroll, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams promptParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            promptParams.leftMargin = Dim.dp32;
            promptParams.rightMargin = Dim.dp32;
            lyricsPane.addView(prompt, promptParams);
            root.addView(lyricsPane, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 0.55f));
            artworkView.setVisibility(View.VISIBLE);
            splitRoot = root;
            artPane = pane;
            this.lyricsPane = lyricsPane;
            applyLyricsCutoutInset(panel.getRootWindowInsets());
            panel.addView(root, 1, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            AppleMusicArtworkPatch.addArtworkFileListener(artworkFileListener);
            trackInfoVisible = true;
            refreshTrackInfo();
            bindArtworkOverlay();
        } else {
            // Unregister first: a queued notification must not re-enter a half-dismantled split.
            splitActive = false;
            AppleMusicArtworkPatch.removeArtworkFileListener(artworkFileListener);
            unbindArtworkOverlay();
            if (splitRoot != null) {
                panel.removeView(splitRoot);
                splitRoot.removeAllViews();
            }
            if (artPane != null) artPane.removeAllViews();
            // removeAllViews leaves the reused views parented to the discarded coverFrame.
            detach(artworkView);
            detach(titleView);
            detach(artistView);
            titleView.setVisibility(View.GONE);
            artistView.setVisibility(View.GONE);
            trackInfo = null;
            coverFrame = null;
            artworkView.setVisibility(View.GONE);
            artPane = null;
            detach(scroll);
            detach(prompt);
            if (lyricsPane != null) lyricsPane.removeAllViews();
            panel.addView(scroll, Math.min(scrollOriginalIndex, panel.getChildCount()),
                    scrollOriginalParams != null ? scrollOriginalParams : scroll.getLayoutParams());
            panel.addView(prompt, Math.min(promptOriginalIndex, panel.getChildCount()),
                    promptOriginalParams != null ? promptOriginalParams : prompt.getLayoutParams());
            lyricsPane = null;
            splitRoot = null;
        }
        splitActive = active;
    }

    /** Reserves the right edge the system says is occupied by a cutout. */
    private void applyLyricsCutoutInset(@Nullable WindowInsets insets) {
        if (lyricsPane == null || insets == null) {
            return;
        }
        int safeRight = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                safeRight = cutout.getSafeInsetRight();
            }
        }
        final int padding = Math.max(Dim.dp16, safeRight + Dim.dp8);
        if (lyricsPane.getPaddingRight() != padding) {
            lyricsPane.setPadding(0, 0, padding, 0);
        }
    }

    private void updateArtworkSize() {
        if (artPane == null) return;
        int paneWidth = artPane.getWidth();
        int paneHeight = artPane.getHeight();
        if (paneWidth <= 0 || paneHeight <= 0) return;
        int size = (int) (Math.min(paneWidth, paneHeight) * 0.65f);
        if (coverFrame != null) {
            ViewGroup.LayoutParams coverParams = coverFrame.getLayoutParams();
            if (coverParams != null && (coverParams.width != size || coverParams.height != size)) {
                coverParams.width = size;
                coverParams.height = size;
                coverFrame.setLayoutParams(coverParams);
            }
        }
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
        if (trackInfo != null) {
            int infoWidth = (int) (paneWidth * 0.8f);
            ViewGroup.LayoutParams infoParams = trackInfo.getLayoutParams();
            if (infoParams != null && infoParams.width != infoWidth) {
                infoParams.width = infoWidth;
                trackInfo.setLayoutParams(infoParams);
            }
        }
    }

    private void updateArtwork() {
        Bitmap artwork = LyricsManager.getInstance().getCurrentArtworkBitmap();
        shownArtwork = artwork;
        if (artwork != null && !artwork.isRecycled()) {
            artworkView.setImageBitmap(artwork);
        } else {
            artworkView.setImageDrawable(null);
        }
    }

    private void bindArtworkOverlay() {
        unbindArtworkOverlay();
        if (artworkView.getParent() == null) {
            return;
        }
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
        try {
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not set system bars", ex);
        }
    }
}
