/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3287
 * https://github.com/MorpheApp/morphe-patches/pull/3451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


/**
 * Applies the player icon style to the app's play, pause and replay button, and to the minimal miniplayer.
 * <p>
 * The app morphs its play shape into its pause shape, which only works for paths made for each other,
 * so a style icon cross-fades into the next one instead.
 */
@SuppressWarnings("unused")
public final class PlayPauseIcons {

    public static final String PLAY = "morphe_player_play";
    public static final String PAUSE = "morphe_player_pause";
    private static final String REPLAY = "morphe_player_replay";

    private PlayPauseIcons() {
    }

    // The injection points replace the app's setImageDrawable call, so its registers keep their types.

    /**
     * Injection point.
     */
    public static void setPlay(ImageView view, Drawable original) {
        view.setImageDrawable(icon(original, null, PLAY));
    }

    /**
     * Injection point.
     */
    public static void setPause(ImageView view, Drawable original) {
        view.setImageDrawable(icon(original, null, PAUSE));
    }

    /**
     * Injection point.
     */
    public static void setPauseToPlay(ImageView view, Drawable original) {
        view.setImageDrawable(icon(original, PAUSE, PLAY));
    }

    /**
     * Injection point.
     */
    public static void setPlayToPause(ImageView view, Drawable original) {
        view.setImageDrawable(icon(original, PLAY, PAUSE));
    }

    /**
     * Injection point.
     */
    public static void setReplay(ImageView view, Drawable original) {
        view.setImageDrawable(icon(original, null, REPLAY));
    }

    private static Drawable icon(Drawable original, @Nullable String fromName, String toName) {
        Drawable to = PlayerIcons.styledDrawable(toName);
        if (to == null) return original;

        Drawable from = fromName == null ? null : PlayerIcons.styledDrawable(fromName);
        return from == null ? to : new Transition(from, to);
    }

    /**
     * Shrinks and fades out one icon while the next one grows in.
     * A new instance is made for every state change, so it starts on its first draw.
     */
    private static final class Transition extends Drawable {
        private static final long DURATION_MS = 200;
        private static final float MIN_SCALE = 0.6f;
        private static final Interpolator INTERPOLATOR = new DecelerateInterpolator();

        private final Drawable from;
        private final Drawable to;
        private long startTime = -1;
        private int alpha = 255;

        Transition(Drawable from, Drawable to) {
            // Both share their state with every other use of the icon, and their alpha changes here.
            this.from = from.mutate();
            this.to = to.mutate();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final long now = SystemClock.uptimeMillis();
            if (startTime < 0) startTime = now;
            final float progress = INTERPOLATOR.getInterpolation(
                    Math.min(1f, (now - startTime) / (float) DURATION_MS));

            drawScaled(canvas, from, 1f - progress);
            drawScaled(canvas, to, progress);

            if (progress < 1f) invalidateSelf();
        }

        private void drawScaled(Canvas canvas, Drawable icon, float visibility) {
            if (visibility <= 0f) return;

            Rect bounds = getBounds();
            final float scale = MIN_SCALE + (1f - MIN_SCALE) * visibility;
            final int save = canvas.save();
            canvas.scale(scale, scale, bounds.exactCenterX(), bounds.exactCenterY());
            icon.setAlpha(Math.round(alpha * visibility));
            icon.draw(canvas);
            canvas.restoreToCount(save);
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            from.setBounds(bounds);
            to.setBounds(bounds);
        }

        @Override
        public int getIntrinsicWidth() {
            return to.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return to.getIntrinsicHeight();
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            from.setColorFilter(colorFilter);
            to.setColorFilter(colorFilter);
        }

        // The miniplayer tints its icons with the theme text color, which is dark in a light theme.
        @Override
        public void setTintList(@Nullable ColorStateList tint) {
            from.setTintList(tint);
            to.setTintList(tint);
        }

        @Override
        public void setTintMode(@Nullable PorterDuff.Mode tintMode) {
            from.setTintMode(tintMode);
            to.setTintMode(tintMode);
        }

        @SuppressWarnings({"deprecation", "RedundantSuppression"})
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
