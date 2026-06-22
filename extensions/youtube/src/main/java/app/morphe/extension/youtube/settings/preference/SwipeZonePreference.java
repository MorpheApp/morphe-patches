/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Renders a live preview of the swipe gesture zones inside the preference screen.
 * Shows the brightness (left), dead zone (center), and volume (right) zones
 * proportionally to the current SWIPE_ZONE_WIDTH setting.
 * Adapts colors to the active light / dark theme.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class SwipeZonePreference extends Preference {

    private ZoneView zoneView;

    private boolean lastBrightnessEnabled;
    private boolean lastVolumeEnabled;
    private boolean lastSpeedEnabled;
    private String lastBrightnessColor;
    private String lastVolumeColor;
    private String lastSpeedColor;
    private int lastZoneWidth = -1;
    private int lastSpeedZoneHeight = -1;

    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (sharedPreferences, str) -> Utils.runOnMainThread(this::updateUI);

    private void updateUI() {
        if (zoneView == null || !zoneView.isAttachedToWindow()) return;
        Logger.printDebug(() -> "updateUI");

        String brightnessColor = Settings.SWIPE_OVERLAY_BRIGHTNESS_COLOR.get();
        String volumeColor = Settings.SWIPE_OVERLAY_VOLUME_COLOR.get();
        String speedColor = Settings.SWIPE_OVERLAY_SPEED_COLOR.get();
        final boolean volumeEnabled = Settings.SWIPE_VOLUME.get();
        final boolean speedEnabled = Settings.SWIPE_SPEED.get();
        final boolean brightnessEnabled = Settings.SWIPE_BRIGHTNESS.get();
        final int zoneWidth = Settings.SWIPE_ZONE_WIDTH.get();
        final int speedZoneHeight = Settings.SWIPE_SPEED_ZONE_HEIGHT.get();

        if (brightnessEnabled != lastBrightnessEnabled
                || speedEnabled != lastSpeedEnabled
                || volumeEnabled != lastVolumeEnabled
                || zoneWidth != lastZoneWidth
                || speedZoneHeight != lastSpeedZoneHeight
                || !brightnessColor.equals(lastBrightnessColor)
                || !volumeColor.equals(lastVolumeColor)
                || !speedColor.equals(lastSpeedColor)) {
            lastZoneWidth = zoneWidth;
            lastBrightnessColor = brightnessColor;
            lastVolumeColor = volumeColor;
            lastSpeedColor = speedColor;
            lastBrightnessEnabled = brightnessEnabled;
            lastVolumeEnabled = volumeEnabled;
            lastSpeedEnabled = speedEnabled;
            lastSpeedZoneHeight = speedZoneHeight;
            zoneView.invalidate();
        }
    }

    public SwipeZonePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public SwipeZonePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public SwipeZonePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SwipeZonePreference(Context context) {
        super(context);
        init();
    }

    private void addChangeListener() {
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    private void removeChangeListener() {
        Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        updateUI();
        addChangeListener();
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        removeChangeListener();
    }

    private void init() {
        setSelectable(false);
        setPersistent(false);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    protected View onCreateView(ViewGroup parent) {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp8);

        zoneView = new ZoneView(getContext());
        layout.addView(zoneView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp(130)));

        return layout;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        if (zoneView != null) {
            zoneView.invalidate();
        }
    }

    @SuppressLint("ViewConstructor")
    private static final class ZoneView extends View {

        // Paints are initialized in constructor after theme is known.
        private final Paint fillPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint separatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dashPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint namePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint percentPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF screenRect = new RectF();
        private final RectF zoneRect   = new RectF();
        private final Path  clipPath   = new Path();

        // Theme-resolved colors used in onDraw.
        private final @ColorInt int screenBgColor;
        private final @ColorInt int edgeBgColor;
        private final @ColorInt int fgColor;
        private final @ColorInt int dimTextColor;

        private final String labelBrightness = str("morphe_swipe_zone_label_brightness");
        private final String labelVolume = str("morphe_swipe_zone_label_volume");
        private final String labelNative = str("morphe_swipe_zone_label_native");
        private final String labelSpeed = str("morphe_swipe_zone_label_speed");

        ZoneView(Context context) {
            super(context);

            fgColor = Utils.getAppForegroundColor();
            final int separatorColor = withAlpha(fgColor, 0x33);

            final int bgColor = Utils.getAppBackgroundColor();
            screenBgColor  = bgColor;
            edgeBgColor    = Utils.adjustColorBrightness(bgColor, Utils.isDarkModeEnabled() ? 0.90f : 0.97f);
            dimTextColor   = withAlpha(fgColor, 0x55);

            fillPaint.setStyle(Paint.Style.FILL);

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Dim.dp1);
            borderPaint.setColor(dimTextColor);

            separatorPaint.setStyle(Paint.Style.STROKE);
            separatorPaint.setStrokeWidth(Dim.dp(0.5f));
            separatorPaint.setColor(separatorColor);

            dashPaint.setStyle(Paint.Style.STROKE);
            dashPaint.setStrokeWidth(Dim.dp(0.5f));
            dashPaint.setColor(separatorColor);
            dashPaint.setPathEffect(new DashPathEffect(new float[]{Dim.dp(4), Dim.dp(3)}, 0));

            namePaint.setTextAlign(Paint.Align.CENTER);
            namePaint.setTextSize(Dim.dp(11));

            percentPaint.setTextAlign(Paint.Align.CENTER);
            percentPaint.setTextSize(Dim.dp(10));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);

            final float w = getWidth();
            final float h = getHeight();
            final float padH  = Dim.dp4;
            final float padV  = Dim.dp6;

            final float sRight  = w - padH;
            final float sBottom = h - padV;
            final float sWidth  = sRight - padH;
            final float sHeight = sBottom - padV;
            final float radius  = Dim.dp(5);

            screenRect.set(padH, padV, sRight, sBottom);

            final int zonePercent      = Math.max(5, Math.min(50, Settings.SWIPE_ZONE_WIDTH.get()));
            final int speedZonePercent = Math.max(5, Math.min(75, Settings.SWIPE_SPEED_ZONE_HEIGHT.get()));
            final boolean brightnessOn = Settings.SWIPE_BRIGHTNESS.get();
            final boolean volumeOn     = Settings.SWIPE_VOLUME.get();
            final boolean speedOn      = Settings.SWIPE_SPEED.get();

            final int brightnessColor = toPreviewColor(parseColor(
                    Settings.SWIPE_OVERLAY_BRIGHTNESS_COLOR.get(), 0xFF4FC3F7), 0xFF4FC3F7);
            final int volumeColor     = toPreviewColor(parseColor(
                    Settings.SWIPE_OVERLAY_VOLUME_COLOR.get(),     0xFF81C784), 0xFF81C784);
            final int speedColor      = toPreviewColor(parseColor(
                    Settings.SWIPE_OVERLAY_SPEED_COLOR.get(), 0xFFFF9100), 0xFFFF9100);

            // The 20 dp edge margins (fixed dead areas) are represented as ~6% of total width.
            final float edgeW      = sWidth * 0.06f;
            final float effectiveW = sWidth - 2f * edgeW;
            final float zoneW      = effectiveW * zonePercent / 100f;
            final float centerW    = effectiveW - 2f * zoneW;
            final float speedZoneH = sHeight * speedZonePercent / 100f;

            // Clip all zone fills to the rounded screen rect.
            clipPath.reset();
            clipPath.addRoundRect(screenRect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);

            // Screen background.
            fillPaint.setColor(screenBgColor);
            canvas.drawRect(screenRect, fillPaint);

            // Left edge dead strip.
            zoneRect.set(padH, padV, padH + edgeW, sBottom);
            fillPaint.setColor(edgeBgColor);
            canvas.drawRect(zoneRect, fillPaint);

            // Right edge dead strip.
            zoneRect.set(sRight - edgeW, padV, sRight, sBottom);
            canvas.drawRect(zoneRect, fillPaint);

            // Brightness zone (full height).
            zoneRect.set(padH + edgeW, padV, padH + edgeW + zoneW, sBottom);
            fillPaint.setColor(brightnessOn ? withAlpha(brightnessColor, 0x55) : 0x1AFFFFFF);
            canvas.drawRect(zoneRect, fillPaint);

            // Volume zone (full height).
            zoneRect.set(sRight - edgeW - zoneW, padV, sRight - edgeW, sBottom);
            fillPaint.setColor(volumeOn ? withAlpha(volumeColor, 0x55) : 0x1AFFFFFF);
            canvas.drawRect(zoneRect, fillPaint);

            // Speed zone (top strip, full width between edge dead zones).
            // Drawn last so it blends naturally over brightness/volume in the overlap areas.
            zoneRect.set(padH + edgeW, padV, sRight - edgeW, padV + speedZoneH);
            fillPaint.setColor(speedOn ? withAlpha(speedColor, 0x55) : 0x1AFFFFFF);
            canvas.drawRect(zoneRect, fillPaint);

            // Separator coordinates.
            final float sep1        = padH + edgeW;
            final float sep2        = padH + edgeW + zoneW;
            final float sep3        = sRight - edgeW - zoneW;
            final float sep4        = sRight - edgeW;
            final float speedBottom = padV + speedZoneH;

            // Edge vertical lines (full height, solid).
            canvas.drawLine(sep1, padV, sep1, sBottom, separatorPaint);
            canvas.drawLine(sep4, padV, sep4, sBottom, separatorPaint);

            // Inner vertical lines: dashed inside the speed zone (overlap area), solid below.
            canvas.drawLine(sep2, padV, sep2, speedBottom, dashPaint);
            canvas.drawLine(sep2, speedBottom, sep2, sBottom, separatorPaint);
            canvas.drawLine(sep3, padV, sep3, speedBottom, dashPaint);
            canvas.drawLine(sep3, speedBottom, sep3, sBottom, separatorPaint);

            // Horizontal line at the bottom of the speed zone:
            //   dashed where it crosses brightness/volume (overlap), solid in the center.
            canvas.drawLine(sep1, speedBottom, sep2, speedBottom, dashPaint);
            canvas.drawLine(sep2, speedBottom, sep3, speedBottom, separatorPaint);
            canvas.drawLine(sep3, speedBottom, sep4, speedBottom, dashPaint);

            // Labels: brightness/volume in the non-overlapping lower portion;
            // speed and native in the center column.
            final float lowerH       = sBottom - speedBottom;
            final float lowerCenterY = speedBottom + lowerH / 2f - Dim.dp(3);
            final float lowerPctY    = lowerCenterY + Dim.dp(13);
            final boolean speedShowPct = speedZoneH >= Dim.dp(22);
            final float speedLabelY  = speedShowPct
                    ? padV + speedZoneH / 2f - Dim.dp(3)
                    : padV + speedZoneH / 2f + namePaint.getTextSize() / 3f;
            final float speedPctY    = speedLabelY + Dim.dp(13);

            if (zoneW >= Dim.dp(30)) {
                namePaint.setColor(brightnessOn ? fgColor : dimTextColor);
                percentPaint.setColor(brightnessOn ? fgColor : dimTextColor);
                canvas.drawText(labelBrightness,
                        padH + edgeW + zoneW / 2f, lowerCenterY, namePaint);
                canvas.drawText(zonePercent + "%",
                        padH + edgeW + zoneW / 2f, lowerPctY, percentPaint);

                namePaint.setColor(volumeOn ? fgColor : dimTextColor);
                percentPaint.setColor(volumeOn ? fgColor : dimTextColor);
                canvas.drawText(labelVolume,
                        sRight - edgeW - zoneW / 2f, lowerCenterY, namePaint);
                canvas.drawText(zonePercent + "%",
                        sRight - edgeW - zoneW / 2f, lowerPctY, percentPaint);
            }

            if (centerW >= Dim.dp(55)) {
                float centerX = padH + edgeW + zoneW + centerW / 2f;

                // Speed label (and % if strip is tall enough) centered in the speed strip.
                final int paintColor = speedOn ? fgColor : dimTextColor;
                namePaint.setColor(paintColor);
                percentPaint.setColor(paintColor);
                canvas.drawText(labelSpeed, centerX, speedLabelY, namePaint);
                if (speedShowPct) {
                    canvas.drawText(speedZonePercent + "%", centerX, speedPctY, percentPaint);
                }

                // Native label in the lower portion of the center column.
                canvas.drawText(labelNative, centerX, lowerCenterY, namePaint);
                canvas.drawText((100 - 2 * zonePercent) + "%", centerX, lowerPctY, percentPaint);
            }

            canvas.restore();

            // Screen border on top (after restore so it is unclipped).
            canvas.drawRoundRect(screenRect, radius, radius, borderPaint);
        }

        @ColorInt
        private static int parseColor(String hex, @ColorInt int fallback) {
            try {
                return Color.parseColor(hex);
            } catch (Exception e) {
                return fallback;
            }
        }

        // Strips the stored alpha (overlay colors are designed for video overlays and may be
        // near-white, which becomes invisible on a light background). Uses the fully-opaque RGB
        // for the preview; falls back to a distinct color if contrast with the background is low.
        @ColorInt
        private int toPreviewColor(@ColorInt int overlayColor, @ColorInt int fallback) {
            final int opaque = overlayColor | 0xFF000000;
            final float la = relativeLuminance(opaque);
            final float lb = relativeLuminance(screenBgColor);
            final float contrast = (Math.max(la, lb) + 0.05f) / (Math.min(la, lb) + 0.05f);
            return contrast >= 1.5f ? opaque : fallback;
        }

        private static float relativeLuminance(@ColorInt int color) {
            final float r = Color.red(color)   / 255f;
            final float g = Color.green(color) / 255f;
            final float b = Color.blue(color)  / 255f;
            return 0.2126f * r + 0.7152f * g + 0.0722f * b;
        }

        @ColorInt
        private static int withAlpha(int color, int alpha) {
            return (color & 0x00FFFFFF) | (alpha << 24);
        }
    }
}
