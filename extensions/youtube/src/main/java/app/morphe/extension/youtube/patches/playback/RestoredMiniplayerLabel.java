/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.LocaleList;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.MiniplayerPatch;
import app.morphe.extension.youtube.shared.PlayerType;

/** Draws the restored session label above the visible floating player. */
public final class RestoredMiniplayerLabel implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
    @Nullable private static volatile RestoredMiniplayerLabel current;

    private final ViewGroup decor;
    private final ViewTreeObserver observer;
    private final int watchPlayerId;
    private final Rect playerBounds = new Rect();
    private final LabelDrawable badgeLabel;
    @Nullable private LocaleList displayLocales;
    private int availableWidth;
    private int layoutDirection = -1;
    private boolean added;

    private RestoredMiniplayerLabel(ViewGroup decor) {
        this.decor = decor;
        observer = decor.getViewTreeObserver();
        watchPlayerId = ResourceUtils.getIdIdentifier("watch_player");
        badgeLabel = new LabelDrawable(createLabel());
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

    static void refresh() {
        if (current == null && !PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.getCurrent())) return;
        Utils.runOnMainThreadNowOrLater(() -> {
            View decor = PlaybackSessionPatch.restoredMiniplayerDecor();
            if (current != null && current.decor != decor) current.stop();
            if (current != null || !(decor instanceof ViewGroup root) || !root.isAttachedToWindow()) return;

            var type = MiniplayerPatch.getCurrentMiniplayerType();
            if (type == MiniplayerPatch.MiniplayerType.MINIMAL_BAR
                    || type == MiniplayerPatch.MiniplayerType.MINIMAL_BAR_2) return;

            var presenter = new RestoredMiniplayerLabel(root);
            current = presenter;
            root.addOnAttachStateChangeListener(presenter);
            presenter.observer.addOnPreDrawListener(presenter);
            root.invalidate();
        });
    }

    private TextView createLabel() {
        var view = new TextView(decor.getContext());
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
        if (PlaybackSessionPatch.restoredMiniplayerDecor() != decor) {
            stop();
            return true;
        }

        View player = watchPlayerId == 0 ? null : decor.findViewById(watchPlayerId);
        if (!getPlayerBounds(player)) {
            player = PlaybackSessionPatch.observedWatchPlayer();
            if (!getPlayerBounds(player)) {
                removeLabel();
                return true;
            }
        }

        int margin = Dim.dp6;
        int width = playerBounds.width() - 2 * margin;
        if (width <= 0 || playerBounds.height() <= 2 * margin) {
            removeLabel();
            return true;
        }
        TextView label = badgeLabel.label;
        LocaleList locales = player.getResources().getConfiguration().getLocales();
        int direction = player.getLayoutDirection();
        if (width != availableWidth || direction != layoutDirection || !locales.equals(displayLocales)) {
            label.setLayoutDirection(direction);
            label.setText(resumeLabel());
            label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            label.layout(0, 0, label.getMeasuredWidth(), label.getMeasuredHeight());
            availableWidth = width;
            layoutDirection = direction;
            displayLocales = locales;
            badgeLabel.invalidateSelf();
        }
        placeLabel(badgeLabel, playerBounds, label.getWidth(), label.getHeight(), margin,
                direction == View.LAYOUT_DIRECTION_RTL);
        if (!added) {
            // The decor overlay stays above video surfaces and has no touch targets.
            decor.getOverlay().add(badgeLabel);
            added = true;
            Logger.printDebug(() -> "Showing restored miniplayer label at " + badgeLabel.getBounds());
        }
        return true;
    }

    static void placeLabel(Drawable badge, Rect player, int width, int height, int margin, boolean rtl) {
        int left = rtl ? player.right - margin - width : player.left + margin;
        int bottom = player.bottom - margin;
        badge.setBounds(left, bottom - height, left + width, bottom);
    }

    private boolean getPlayerBounds(@Nullable View player) {
        return player != null && player.getRootView() == decor && player.isShown()
                && player.getGlobalVisibleRect(playerBounds);
    }

    private void removeLabel() {
        if (!added) return;
        decor.getOverlay().remove(badgeLabel);
        added = false;
    }

    private void stop() {
        removeLabel();
        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        decor.removeOnAttachStateChangeListener(this);
        if (current == this) current = null;
    }

    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { stop(); }

    private static final class LabelDrawable extends Drawable {
        private final TextView label;

        private LabelDrawable(TextView label) {
            this.label = label;
        }

        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            label.draw(canvas);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { label.setAlpha(alpha / 255f); }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
        @SuppressWarnings("deprecation")
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
