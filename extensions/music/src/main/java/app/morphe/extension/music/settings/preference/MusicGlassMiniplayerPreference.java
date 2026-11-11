/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.MusicGlassMiniplayer;
import app.morphe.extension.music.patches.MusicGlassNavigationBar;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.preference.SeekBarsPreviewPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.glass.GlassCapsuleDrawable;

/**
 * Sets the corner radius, the artwork padding, the rim brightness and the opacity of the glass miniplayer,
 * with a preview of the miniplayer that follows the sliders.
 */
@SuppressWarnings("unused")
public class MusicGlassMiniplayerPreference extends SeekBarsPreviewPreference {

    private static final IntegerSetting[] SETTINGS = {
            Settings.MINIPLAYER_GLASS_CORNER_RADIUS,
            Settings.MINIPLAYER_GLASS_ARTWORK_PADDING,
            Settings.MINIPLAYER_GLASS_RIM,
            Settings.MINIPLAYER_GLASS_OPACITY
    };

    private static final String[] TITLES = {
            "morphe_music_miniplayer_glass_corner_radius_title",
            "morphe_music_miniplayer_glass_artwork_padding_title",
            "morphe_music_miniplayer_glass_rim_title",
            "morphe_music_miniplayer_glass_opacity_title"
    };

    private static final int INDEX_CORNER_RADIUS = 0;
    private static final int INDEX_ARTWORK_PADDING = 1;
    private static final int INDEX_RIM = 2;
    private static final int INDEX_OPACITY = 3;

    public MusicGlassMiniplayerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected IntegerSetting[] getSettings() {
        return SETTINGS;
    }

    @Override
    protected String[] getTitleKeys() {
        return TITLES;
    }

    @Override
    protected View createPreview(Context context, int[] values) {
        return new PreviewView(context, values);
    }

    @Override
    protected void updatePreview(View preview, int[] values) {
        ((PreviewView) preview).update();
    }

    /**
     * The miniplayer at its real size over some content, so the translucency of the glass shows.
     */
    private static final class PreviewView extends View implements GlassCapsuleDrawable.Host {
        private static final int HEIGHT = Dim.dp(120);
        private static final int MARGIN = MusicGlassNavigationBar.MARGIN_HORIZONTAL;

        /**
         * The height of the floating miniplayer of the app.
         */
        private static final int CARD_HEIGHT = Dim.dp(56);
        private static final int BUTTON_SIZE = Dim.dp(42);
        private static final float ICON_HALF_SIZE = Dim.dp(6);

        /**
         * The color of the album art when no song played yet.
         */
        private static final int SAMPLE_ALBUM_COLOR = 0xFF7B3B5E;

        private final int[] values;
        private final GlassCapsuleDrawable glass;
        private final boolean round = Settings.MINIPLAYER_GLASS_SPINNING_ARTWORK.get();
        private final boolean progressBarShown = !Settings.MINIPLAYER_GLASS_HIDE_PROGRESS_BAR.get();
        private final boolean lightTint = Settings.MINIPLAYER_GLASS_LIGHT.get();
        private final int tintColor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Rect card = new Rect();
        private final Path icon = new Path();

        PreviewView(Context context, int[] values) {
            super(context);
            this.values = values;
            final int albumColor = MusicGlassNavigationBar.getAlbumColor();
            tintColor = !Settings.MINIPLAYER_GLASS_ALBUM_TINT.get() ? 0
                    : albumColor != 0 ? albumColor : SAMPLE_ALBUM_COLOR;
            glass = new GlassCapsuleDrawable(this, this, 1);
            glass.setTintColor(tintColor);
        }

        void update() {
            glass.setStyle(values[INDEX_OPACITY] / 50f, lightTint, values[INDEX_RIM] / 50f);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec), HEIGHT);
        }

        @Override
        public void getCapsule(Rect bounds, RectF capsule) {
            capsule.set(bounds);
        }

        @Nullable
        @Override
        public View getSelectedTabView() {
            return null;
        }

        @Override
        public float getCornerRadius(RectF capsule) {
            return MusicGlassMiniplayer.getCornerRadius(Dim.dp(values[INDEX_CORNER_RADIUS]), capsule.height());
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final boolean dark = Utils.isDarkModeEnabled();
            final int foreground = ThemeUtils.getAppForegroundColor();
            final float width = getWidth();
            final float height = getHeight();
            final float corner = Dim.dp12;

            // The screen, with content behind the miniplayer.
            rect.set(0, 0, width, height);
            paint.setColor(dark ? Color.rgb(24, 24, 24) : Color.rgb(236, 236, 236));
            canvas.drawRoundRect(rect, corner, corner, paint);
            paint.setColor(dark ? Color.rgb(70, 90, 130) : Color.rgb(150, 180, 230));
            rect.set(width * 0.06f, height * 0.12f, width * 0.55f, height * 0.92f);
            canvas.drawRoundRect(rect, corner, corner, paint);
            paint.setColor(dark ? Color.rgb(130, 70, 70) : Color.rgb(240, 170, 160));
            rect.set(width * 0.6f, height * 0.12f, width * 0.94f, height * 0.92f);
            canvas.drawRoundRect(rect, corner, corner, paint);

            final int top = Math.round((height - CARD_HEIGHT) / 2);
            card.set(MARGIN, top, Math.round(width) - MARGIN, top + CARD_HEIGHT);
            glass.setBounds(card);
            glass.draw(canvas);

            // The artwork, inset and with corners that follow the card, or round.
            final float padding = Dim.dp(values[INDEX_ARTWORK_PADDING]);
            final float cardRadius = MusicGlassMiniplayer.getCornerRadius(Dim.dp(values[INDEX_CORNER_RADIUS]), CARD_HEIGHT);
            final float artworkSize = CARD_HEIGHT - 2 * padding;
            final float artworkRadius = round ? artworkSize / 2
                    : MusicGlassMiniplayer.getArtworkRadius(cardRadius, padding);
            rect.set(card.left + padding, card.top + padding, card.left + padding + artworkSize, card.bottom - padding);
            paint.setColor(tintColor != 0 ? tintColor | 0xFF000000 : dark ? 0xFF5A78A8 : 0xFF7898C8);
            canvas.drawRoundRect(rect, artworkRadius, artworkRadius, paint);
            if (round) {
                // The hole of a record.
                paint.setColor(dark ? Color.rgb(24, 24, 24) : Color.rgb(236, 236, 236));
                canvas.drawCircle(rect.centerX(), rect.centerY(), artworkSize * 0.16f, paint);
            }

            // The title and the artist.
            final float textLeft = rect.right + Dim.dp10;
            final float lineHeight = Dim.dp6;
            final float titleTop = card.top + CARD_HEIGHT * 0.36f - lineHeight / 2;
            rect.set(textLeft, titleTop, textLeft + card.width() * 0.3f, titleTop + lineHeight);
            paint.setColor(foreground);
            paint.setAlpha(225);
            canvas.drawRoundRect(rect, lineHeight / 2, lineHeight / 2, paint);
            final float subtitleTop = card.top + CARD_HEIGHT * 0.64f - lineHeight / 2;
            rect.set(textLeft, subtitleTop, textLeft + card.width() * 0.2f, subtitleTop + lineHeight);
            paint.setAlpha(140);
            canvas.drawRoundRect(rect, lineHeight / 2, lineHeight / 2, paint);

            // The previous, the pause and the next buttons, away from the rounded end.
            paint.setColor(foreground);
            final float centerY = card.exactCenterY();
            final float nextX = card.right - Dim.dp8 - BUTTON_SIZE / 2f;
            final float pauseX = nextX - BUTTON_SIZE;
            final float previousX = pauseX - BUTTON_SIZE;
            drawSkip(canvas, nextX, centerY, 1);
            drawSkip(canvas, previousX, centerY, -1);
            final float bar = Dim.dp(2.5f);
            canvas.drawRect(pauseX - ICON_HALF_SIZE * 0.65f, centerY - ICON_HALF_SIZE,
                    pauseX - ICON_HALF_SIZE * 0.65f + bar, centerY + ICON_HALF_SIZE, paint);
            canvas.drawRect(pauseX + ICON_HALF_SIZE * 0.65f - bar, centerY - ICON_HALF_SIZE,
                    pauseX + ICON_HALF_SIZE * 0.65f, centerY + ICON_HALF_SIZE, paint);

            // The progress bar, which ends before the rounded corners.
            if (progressBarShown) {
                final float barHeight = Dim.dp2;
                final float left = card.left + cardRadius;
                final float right = card.right - cardRadius;
                rect.set(left, card.bottom - barHeight, right, card.bottom);
                paint.setAlpha(56);
                canvas.drawRect(rect, paint);
                rect.right = left + (right - left) * 0.45f;
                paint.setAlpha(220);
                canvas.drawRect(rect, paint);
            }
        }

        /**
         * A triangle and a bar, pointing right for the next button and left for the previous button.
         */
        private void drawSkip(Canvas canvas, float x, float y, int direction) {
            final float half = ICON_HALF_SIZE;
            icon.reset();
            icon.moveTo(x - direction * half, y - half);
            icon.lineTo(x - direction * half, y + half);
            icon.lineTo(x + direction * half * 0.5f, y);
            icon.close();
            canvas.drawPath(icon, paint);
            final float barLeft = x + direction * half * 0.5f;
            final float barRight = x + direction * half;
            canvas.drawRect(Math.min(barLeft, barRight), y - half, Math.max(barLeft, barRight), y + half, paint);
        }
    }
}
