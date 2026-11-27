/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback;

import android.graphics.drawable.Drawable;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.videoplayer.PlayPauseIcons;
import app.morphe.extension.youtube.videoplayer.PlayerIcons;

/** Keeps the native modern controls visible for the restored video. */
public final class RestoredMiniplayerControls implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
    private static volatile WeakReference<RestoredMiniplayerControls> current = new WeakReference<>(null);

    private final View overlay;
    private final ImageView action;
    @Nullable private ViewTreeObserver observer;
    @Nullable private Drawable playIcon;
    @Nullable private Drawable nativeIcon;
    @Nullable private CharSequence nativeDescription;
    private int nativeOverlayVisibility, nativeActionVisibility;
    private float nativeActionAlpha;
    private boolean forcingPlay;
    private String playDescription = "";
    @Nullable private LocaleList displayLocales;

    private RestoredMiniplayerControls(View overlay, ImageView action) {
        this.overlay = overlay;
        this.action = action;
    }

    public static void bind(View actionView) {
        if (!(actionView instanceof ImageView action) || !(action.getParent() instanceof View parent)) return;
        var previous = current.get();
        if (previous != null && previous.action == action) return;
        if (previous != null) {
            previous.stop();
            previous.action.removeOnAttachStateChangeListener(previous);
        }
        var controls = new RestoredMiniplayerControls(parent, action);
        current = new WeakReference<>(controls);
        action.addOnAttachStateChangeListener(controls);
        controls.update();
    }

    static void refresh() {
        if (current.get() == null) return;
        Utils.runOnMainThreadNowOrLater(() -> {
            var controls = current.get();
            if (controls != null) controls.update();
        });
    }

    private void update() {
        if (!canShowPausedControls()) {
            stop();
            return;
        }
        if (observer == null) {
            playIcon = PlayerIcons.styledDrawable(PlayPauseIcons.PLAY);
            int iconId = ResourceUtils.getDrawableIdentifier("player_play");
            if (playIcon == null && iconId != 0) playIcon = action.getContext().getDrawable(iconId);
            updatePlayDescription();
            nativeOverlayVisibility = overlay.getVisibility();
            nativeActionVisibility = action.getVisibility();
            nativeActionAlpha = action.getAlpha();
            observer = action.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }
        onPreDraw();
    }

    private boolean canShowPausedControls() {
        return PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.getCurrent())
                && PlaybackSessionPatch.ownsMiniplayerControls(action);
    }

    private void updatePlayDescription() {
        LocaleList locales = action.getResources().getConfiguration().getLocales();
        if (locales.equals(displayLocales)) return;
        playDescription = ResourceUtils.getStringOrThrow("accessibility_play");
        displayLocales = locales;
    }

    @Override public boolean onPreDraw() {
        if (!canShowPausedControls()) {
            stop();
            return true;
        }

        if (overlay.getVisibility() != View.VISIBLE) nativeOverlayVisibility = overlay.getVisibility();
        if (action.getVisibility() != View.VISIBLE) nativeActionVisibility = action.getVisibility();
        if (action.getAlpha() != 1f) nativeActionAlpha = action.getAlpha();
        if (PlaybackSessionPatch.restoredWatchAwaitingPlayback()) {
            if (!forcingPlay || action.getDrawable() != playIcon) nativeIcon = action.getDrawable();
            if (!forcingPlay || !playDescription.equals(action.getContentDescription())) {
                nativeDescription = action.getContentDescription();
            }
            forcingPlay = true;
            updatePlayDescription();
            showControls(overlay, action, playIcon, playDescription);
        } else {
            restoreNativeIcon();
            showControls(overlay, action, null, null);
        }
        return true;
    }

    static void showControls(View overlay, ImageView action, @Nullable Drawable play,
                             @Nullable CharSequence playDescription) {
        if (overlay.getVisibility() != View.VISIBLE) overlay.setVisibility(View.VISIBLE);
        if (play != null && action.getDrawable() != play) action.setImageDrawable(play);
        if (playDescription != null && !playDescription.equals(action.getContentDescription())) {
            action.setContentDescription(playDescription);
        }
        if (action.getVisibility() != View.VISIBLE) action.setVisibility(View.VISIBLE);
        if (action.getAlpha() != 1f) action.setAlpha(1f);
    }

    private void restoreNativeIcon() {
        if (!forcingPlay) return;
        forcingPlay = false;
        if (playIcon != null && action.getDrawable() == playIcon) action.setImageDrawable(nativeIcon);
        if (playDescription.equals(action.getContentDescription())) {
            action.setContentDescription(nativeDescription);
        }
    }

    private void stop() {
        if (observer == null) return;
        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        displayLocales = null;
        restoreNativeIcon();
        if (action.getVisibility() == View.VISIBLE) action.setVisibility(nativeActionVisibility);
        if (action.getAlpha() == 1f) action.setAlpha(nativeActionAlpha);
        if (overlay.getVisibility() == View.VISIBLE) overlay.setVisibility(nativeOverlayVisibility);
    }

    @Override public void onViewAttachedToWindow(View view) { update(); }
    @Override public void onViewDetachedFromWindow(View view) { stop(); }
}
