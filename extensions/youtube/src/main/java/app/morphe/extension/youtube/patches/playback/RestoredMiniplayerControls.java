/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.videoplayer.PlayPauseIcons;
import app.morphe.extension.youtube.videoplayer.PlayerIcons;

/** Uses the native modern overlay while a restored video is waiting to play. */
public final class RestoredMiniplayerControls implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
    private static volatile WeakReference<RestoredMiniplayerControls> current = new WeakReference<>(null);

    private final View overlay;
    private final ImageView action;
    private final View badge;
    private final TextView label;
    @Nullable private final View subtitle;
    @Nullable private ViewTreeObserver observer;
    @Nullable private Drawable playIcon;
    @Nullable private Drawable nativeIcon;
    @Nullable private CharSequence nativeDescription;
    @Nullable private CharSequence nativeLabel;
    private int nativeOverlayVisibility, nativeActionVisibility, nativeBadgeVisibility, nativeSubtitleVisibility;
    private float nativeActionAlpha;
    private String playDescription = "", restoredLabel = "";

    private RestoredMiniplayerControls(View overlay, ImageView action, View badge, TextView label,
                                      @Nullable View subtitle) {
        this.overlay = overlay;
        this.action = action;
        this.badge = badge;
        this.label = label;
        this.subtitle = subtitle;
    }

    public static String resumeLabel() {
        return StringRef.str("morphe_miniplayer_restored_label");
    }

    public static void bind(View actionView) {
        if (!(actionView instanceof ImageView action) || !(action.getParent() instanceof View parent)) return;
        var previous = current.get();
        if (previous != null && previous.action == action) return;
        View badge = parent.findViewById(ResourceUtils.getIdIdentifier("modern_miniplayer_continue_watching_premium"));
        if (badge == null || !(badge.findViewById(ResourceUtils.getIdIdentifier("badge_icon_text"))
                instanceof TextView label)) return;
        View subtitle = parent.findViewById(ResourceUtils.getIdIdentifier("modern_miniplayer_subtitle_text"));
        if (previous != null) {
            previous.stop();
            previous.action.removeOnAttachStateChangeListener(previous);
        }
        var controls = new RestoredMiniplayerControls(parent, action, badge, label, subtitle);
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
            playDescription = StringRef.str("accessibility_play");
            restoredLabel = resumeLabel();
            nativeIcon = action.getDrawable();
            nativeDescription = action.getContentDescription();
            nativeLabel = label.getText();
            nativeOverlayVisibility = overlay.getVisibility();
            nativeActionVisibility = action.getVisibility();
            nativeActionAlpha = action.getAlpha();
            nativeBadgeVisibility = badge.getVisibility();
            if (subtitle != null) nativeSubtitleVisibility = subtitle.getVisibility();
            observer = action.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }
        onPreDraw();
    }

    private boolean canShowPausedControls() {
        return PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.getCurrent())
                && PlaybackSessionPatch.ownsMiniplayerControls(action);
    }

    @Override public boolean onPreDraw() {
        if (!canShowPausedControls()) {
            stop();
            return true;
        }

        if (overlay.getVisibility() != View.VISIBLE) nativeOverlayVisibility = overlay.getVisibility();
        if (action.getVisibility() != View.VISIBLE) nativeActionVisibility = action.getVisibility();
        if (action.getAlpha() != 1f) nativeActionAlpha = action.getAlpha();
        if (playIcon != null && action.getDrawable() != playIcon) nativeIcon = action.getDrawable();
        if (!playDescription.equals(action.getContentDescription())) nativeDescription = action.getContentDescription();
        if (!restoredLabel.equals(label.getText())) nativeLabel = label.getText();
        if (badge.getVisibility() != View.VISIBLE) nativeBadgeVisibility = badge.getVisibility();
        if (subtitle != null && subtitle.getVisibility() != View.GONE) {
            nativeSubtitleVisibility = subtitle.getVisibility();
            subtitle.setVisibility(View.GONE);
        }
        showPaused(overlay, action, playIcon, playDescription, badge, label, restoredLabel);
        return true;
    }

    static void showPaused(View overlay, ImageView action, @Nullable Drawable play, CharSequence playDescription,
                           View badge, TextView label, CharSequence resumeLabel) {
        if (overlay.getVisibility() != View.VISIBLE) overlay.setVisibility(View.VISIBLE);
        if (play != null && action.getDrawable() != play) action.setImageDrawable(play);
        if (!playDescription.equals(action.getContentDescription())) action.setContentDescription(playDescription);
        if (action.getVisibility() != View.VISIBLE) action.setVisibility(View.VISIBLE);
        if (action.getAlpha() != 1f) action.setAlpha(1f);
        if (!resumeLabel.equals(label.getText())) label.setText(resumeLabel, TextView.BufferType.NORMAL);
        if (badge.getVisibility() != View.VISIBLE) badge.setVisibility(View.VISIBLE);
    }

    private void stop() {
        if (observer == null) return;
        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        if (playIcon != null && action.getDrawable() == playIcon) action.setImageDrawable(nativeIcon);
        if (playDescription.equals(action.getContentDescription())) {
            action.setContentDescription(nativeDescription);
        }
        if (action.getVisibility() == View.VISIBLE) action.setVisibility(nativeActionVisibility);
        if (action.getAlpha() == 1f) action.setAlpha(nativeActionAlpha);
        if (overlay.getVisibility() == View.VISIBLE) overlay.setVisibility(nativeOverlayVisibility);
        if (badge.getVisibility() == View.VISIBLE) badge.setVisibility(nativeBadgeVisibility);
        if (restoredLabel.equals(label.getText())) label.setText(nativeLabel, TextView.BufferType.NORMAL);
        if (subtitle != null && subtitle.getVisibility() == View.GONE) subtitle.setVisibility(nativeSubtitleVisibility);
    }

    @Override public void onViewAttachedToWindow(View view) { update(); }
    @Override public void onViewDetachedFromWindow(View view) { stop(); }
}
