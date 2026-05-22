/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Renders a live preview of the swipe gesture zones inside the preference screen.
 * Shows the brightness (left), deadzone (center), and volume (right) zones
 * proportionally to the current SWIPE_ZONE_WIDTH setting.
 * Adapts colors to the active light / dark theme.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class SwipeZonePreference extends Preference
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private ZoneView zoneView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int lastZoneWidth = -1;
    private String lastBrightnessColor = null;
    private String lastVolumeColor = null;
    private boolean lastBrightnessEnabled = false;
    private boolean lastVolumeEnabled = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (zoneView == null || !zoneView.isAttachedToWindow()) return;

            int zoneWidth = Settings.SWIPE_ZONE_WIDTH.get();
            String brightnessColor = Settings.SWIPE_OVERLAY_BRIGHTNESS_COLOR.get();
            String volumeColor = Settings.SWIPE_OVERLAY_VOLUME_COLOR.get();
            boolean brightnessEnabled = Settings.SWIPE_BRIGHTNESS.get();
            boolean volumeEnabled = Settings.SWIPE_VOLUME.get();

            if (zoneWidth != lastZoneWidth
                    || !brightnessColor.equals(lastBrightnessColor)
                    || !volumeColor.equals(lastVolumeColor)
                    || brightnessEnabled != lastBrightnessEnabled
                    || volumeEnabled != lastVolumeEnabled) {
                lastZoneWidth = zoneWidth;
                lastBrightnessColor = brightnessColor;
                lastVolumeColor = volumeColor;
                lastBrightnessEnabled = brightnessEnabled;
                lastVolumeEnabled = volumeEnabled;
                zoneView.invalidate();
            }

            handler.postDelayed(this, 500);
        }
    };

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

        handler.postDelayed(pollRunnable, 500);
        return layout;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        if (zoneView != null) {
            zoneView.invalidate();
        }
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        try {
            SharedPreferences prefs = preferenceManager.getSharedPreferences();
            if (prefs != null) {
                prefs.registerOnSharedPreferenceChangeListener(this);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key != null && key.startsWith("morphe_swipe") && zoneView != null) {
            zoneView.postInvalidate();
        }
    }

    @SuppressLint("ViewConstructor")
    private static final class ZoneView extends View {

        // Paints are initialized in constructor after theme is known.
        private final Paint fillPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint separatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint namePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint percentPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF screenRect = new RectF();
        private final RectF zoneRect   = new RectF();
        private final Path  clipPath   = new Path();

        // Theme-resolved colors used in onDraw.
        private final int screenBgColor;
        private final int edgeBgColor;
        private final int dimTextColor;

        private final String labelBrightness;
        private final String labelVolume;
        private final String labelNative;

        ZoneView(Context context) {
            super(context);

            labelBrightness = StringRef.str("morphe_swipe_zone_label_brightness");
            labelVolume     = StringRef.str("morphe_swipe_zone_label_volume");
            labelNative     = StringRef.str("morphe_swipe_zone_label_native");

            int bgColor = Utils.getAppBackgroundColor();
            int fgColor = Utils.getAppForegroundColor();

            screenBgColor  = bgColor;
            edgeBgColor    = Utils.adjustColorBrightness(bgColor, Utils.isDarkModeEnabled() ? 0.90f : 0.97f);
            int borderColor    = withAlpha(fgColor, 0x55);
            int separatorColor = withAlpha(fgColor, 0x33);
            dimTextColor   = withAlpha(fgColor, 0x55);

            fillPaint.setStyle(Paint.Style.FILL);

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Dim.dp1);
            borderPaint.setColor(borderColor);

            separatorPaint.setStyle(Paint.Style.STROKE);
            separatorPaint.setStrokeWidth(Dim.dp(0.5f));
            separatorPaint.setColor(separatorColor);

            namePaint.setTextAlign(Paint.Align.CENTER);
            namePaint.setTextSize(Dim.dp(11));

            percentPaint.setTextAlign(Paint.Align.CENTER);
            percentPaint.setTextSize(Dim.dp(10));
            percentPaint.setColor(dimTextColor);

        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();
            float padH  = Dim.dp4;
            float padV  = Dim.dp6;

            float sRight  = w - padH;
            float sBottom = h - padV;
            float sWidth  = sRight - padH;
            float sHeight = sBottom - padV;
            float radius  = Dim.dp(5);

            screenRect.set(padH, padV, sRight, sBottom);

            int zonePercent   = Math.max(5, Math.min(50, Settings.SWIPE_ZONE_WIDTH.get()));
            boolean brightnessOn = Settings.SWIPE_BRIGHTNESS.get();
            boolean volumeOn     = Settings.SWIPE_VOLUME.get();

            int brightnessColor = toPreviewColor(
                    parseColor(Settings.SWIPE_OVERLAY_BRIGHTNESS_COLOR.get(), 0xFF4FC3F7), 0xFF4FC3F7);
            int volumeColor     = toPreviewColor(
                    parseColor(Settings.SWIPE_OVERLAY_VOLUME_COLOR.get(),     0xFF81C784), 0xFF81C784);

            // The 20 dp edge margins (fixed dead areas) are represented as ~6% of total width.
            float edgeW = sWidth * 0.06f;
            float effectiveW = sWidth - 2f * edgeW;
            float zoneW   = effectiveW * zonePercent / 100f;
            float centerW = effectiveW - 2f * zoneW;

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

            // Brightness zone.
            zoneRect.set(padH + edgeW, padV, padH + edgeW + zoneW, sBottom);
            fillPaint.setColor(brightnessOn ? withAlpha(brightnessColor, 0x55) : 0x1AFFFFFF);
            canvas.drawRect(zoneRect, fillPaint);

            // Volume zone.
            zoneRect.set(sRight - edgeW - zoneW, padV, sRight - edgeW, sBottom);
            fillPaint.setColor(volumeOn ? withAlpha(volumeColor, 0x55) : 0x1AFFFFFF);
            canvas.drawRect(zoneRect, fillPaint);

            // Separator lines.
            float sep1 = padH + edgeW;
            float sep2 = padH + edgeW + zoneW;
            float sep3 = sRight - edgeW - zoneW;
            float sep4 = sRight - edgeW;
            canvas.drawLine(sep1, padV, sep1, sBottom, separatorPaint);
            canvas.drawLine(sep2, padV, sep2, sBottom, separatorPaint);
            canvas.drawLine(sep3, padV, sep3, sBottom, separatorPaint);
            canvas.drawLine(sep4, padV, sep4, sBottom, separatorPaint);

            // Zone name and percentage labels — name above, % below, pair centered vertically.
            float nameY = padV + sHeight / 2f - Dim.dp(3);
            float pctY  = nameY + Dim.dp(13);

            if (zoneW >= Dim.dp(30)) {
                namePaint.setColor(brightnessOn
                        ? withAlpha(brightnessColor, 0xFF) : dimTextColor);
                canvas.drawText(labelBrightness,
                        padH + edgeW + zoneW / 2f, nameY, namePaint);

                percentPaint.setColor(brightnessOn
                        ? withAlpha(brightnessColor, 0xBB) : dimTextColor);
                canvas.drawText(zonePercent + "%",
                        padH + edgeW + zoneW / 2f, pctY, percentPaint);

                namePaint.setColor(volumeOn
                        ? withAlpha(volumeColor, 0xFF) : dimTextColor);
                canvas.drawText(labelVolume,
                        sRight - edgeW - zoneW / 2f, nameY, namePaint);

                percentPaint.setColor(volumeOn
                        ? withAlpha(volumeColor, 0xBB) : dimTextColor);
                canvas.drawText(zonePercent + "%",
                        sRight - edgeW - zoneW / 2f, pctY, percentPaint);
            }

            if (centerW >= Dim.dp(55)) {
                namePaint.setColor(dimTextColor);
                canvas.drawText(labelNative,
                        padH + edgeW + zoneW + centerW / 2f, nameY, namePaint);

                percentPaint.setColor(dimTextColor);
                canvas.drawText((100 - 2 * zonePercent) + "%",
                        padH + edgeW + zoneW + centerW / 2f, pctY, percentPaint);
            }

            canvas.restore();

            // Screen border on top (after restore so it is unclipped).
            canvas.drawRoundRect(screenRect, radius, radius, borderPaint);
        }

        private static int parseColor(String hex, int fallback) {
            try {
                return Color.parseColor(hex);
            } catch (Exception e) {
                return fallback;
            }
        }

        // Strips the stored alpha (overlay colors are designed for video overlays and may be
        // near-white, which becomes invisible on a light background). Uses the fully-opaque RGB
        // for the preview; falls back to a distinct color if contrast with the background is low.
        private int toPreviewColor(int overlayColor, int fallback) {
            int opaque = overlayColor | 0xFF000000;
            float la = relativeLuminance(opaque);
            float lb = relativeLuminance(screenBgColor);
            float contrast = (Math.max(la, lb) + 0.05f) / (Math.min(la, lb) + 0.05f);
            return contrast >= 1.5f ? opaque : fallback;
        }

        private static float relativeLuminance(int color) {
            float r = Color.red(color)   / 255f;
            float g = Color.green(color) / 255f;
            float b = Color.blue(color)  / 255f;
            return 0.2126f * r + 0.7152f * g + 0.0722f * b;
        }

        private static int withAlpha(int color, int alpha) {
            return (color & 0x00FFFFFF) | (alpha << 24);
        }
    }
}
