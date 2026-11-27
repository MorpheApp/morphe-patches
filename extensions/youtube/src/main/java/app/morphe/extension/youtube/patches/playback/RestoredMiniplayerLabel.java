/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.LocaleList;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.shared.PlayerType;

/** The floating miniplayer label uses the player overlay even when native controls are hidden. */
public final class RestoredMiniplayerLabel implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
    private static volatile WeakReference<RestoredMiniplayerLabel> current = new WeakReference<>(null);

    private final ViewGroup player;
    @Nullable private TextView label;
    @Nullable private ViewTreeObserver observer;
    @Nullable private LocaleList displayLocales;
    private int availableWidth;

    private RestoredMiniplayerLabel(ViewGroup player) {
        this.player = player;
    }

    public static String resumeLabel() {
        int morpheLabel = ResourceUtils.getStringIdentifier("morphe_miniplayer_restored_label");
        String translation = morpheLabel == 0 ? null : ResourceUtils.getTranslation(morpheLabel);
        if (translation != null) return translation;

        int nativeLabel = ResourceUtils.getStringIdentifier("continue_watching_ready_to_resume");
        if (nativeLabel != 0) return ResourceUtils.getString(nativeLabel);
        return morpheLabel == 0 ? "Continue watching" : ResourceUtils.getString(morpheLabel);
    }

    public static void setBadge(TextView label, boolean visible) {
        int badge = visible ? ResourceUtils.getDrawableIdentifier("morphe_miniplayer_restore_badge") : 0;
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(badge, 0, 0, 0);
        label.setCompoundDrawablePadding(visible ? Dim.dp4 : 0);
    }

    static void bind(View view) {
        if (!(view instanceof ViewGroup player)) return;
        Utils.runOnMainThreadNowOrLater(() -> {
            var previous = current.get();
            if (previous != null) {
                if (previous.player == player) return;
                previous.stop();
                previous.player.removeOnAttachStateChangeListener(previous);
            }
            var presenter = new RestoredMiniplayerLabel(player);
            current = new WeakReference<>(presenter);
            player.addOnAttachStateChangeListener(presenter);
            presenter.update();
        });
    }

    static void refresh() {
        if (current.get() == null) return;
        Utils.runOnMainThreadNowOrLater(() -> {
            var presenter = current.get();
            if (presenter != null) presenter.update();
        });
    }

    private boolean canShow() {
        return PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.getCurrent())
                && PlaybackSessionPatch.ownsMiniplayerControls(player);
    }

    private void update() {
        if (!canShow()) {
            stop();
            return;
        }
        if (observer != null) return;
        observer = player.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        onPreDraw();
    }

    private TextView createLabel() {
        var view = new TextView(player.getContext());
        view.setTextColor(Color.WHITE);
        view.setTextSize(10);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        view.setPadding(Dim.dp4, Dim.dp2, Dim.dp4, Dim.dp2);
        var background = new GradientDrawable();
        background.setColor(0xE6212121);
        background.setCornerRadius(Dim.dp4);
        view.setBackground(background);
        setBadge(view, true);
        return view;
    }

    @Override public boolean onPreDraw() {
        if (!canShow()) {
            stop();
            return true;
        }
        int margin = Dim.dp6;
        int width = player.getWidth() - 2 * margin;
        if (width <= 0 || player.getHeight() <= 2 * margin) return true;

        boolean addLabel = label == null;
        if (addLabel) label = createLabel();
        LocaleList locales = player.getResources().getConfiguration().getLocales();
        int direction = player.getLayoutDirection();
        if (width != availableWidth || direction != label.getLayoutDirection() || !locales.equals(displayLocales)) {
            label.setLayoutDirection(direction);
            label.setText(resumeLabel());
            label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            availableWidth = width;
            displayLocales = locales;
        }
        int left = direction == View.LAYOUT_DIRECTION_RTL ? margin + width - label.getMeasuredWidth() : margin;
        int bottom = player.getHeight() - margin;
        label.layout(left, bottom - label.getMeasuredHeight(), left + label.getMeasuredWidth(), bottom);
        // Overlay views are drawn above the video and are excluded from layout and touch dispatch.
        if (addLabel) player.getOverlay().add(label);
        return true;
    }

    private void stop() {
        if (observer == null) return;
        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        if (label != null) player.getOverlay().remove(label);
        label = null;
        displayLocales = null;
    }

    @Override public void onViewAttachedToWindow(View view) { update(); }
    @Override public void onViewDetachedFromWindow(View view) { stop(); }
}
