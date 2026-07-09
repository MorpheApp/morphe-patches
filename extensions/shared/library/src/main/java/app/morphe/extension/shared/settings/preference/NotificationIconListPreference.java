/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import app.morphe.extension.shared.Utils;

/**
 * An {@link IconListPreference} for notification icon selection.
 * <p>
 * Each entry is rendered as a theme-colored rounded square (black in dark theme, white in light)
 * with the corresponding {@code morphe_notification_icon_{value}} drawable tinted to the
 * contrasting foreground color - mirroring how Android displays notification icons in the shade.
 * <p>
 * ORIGINAL uses the app's real notification icon resource (e.g. {@code ic_stat_yt_notification_logo}),
 * injected at patch time via {@link IconListPreference#setOriginalNotificationIconName}.
 * FOLLOW mirrors the currently selected app icon's notification counterpart.
 */
@SuppressWarnings({"unused", "deprecation"})
public class NotificationIconListPreference extends IconListPreference {

    private static final float NOTIF_ICON_SIZE_FRACTION = 0.6f;

    public NotificationIconListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public NotificationIconListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationIconListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationIconListPreference(Context context) {
        super(context);
    }

    @NonNull
    @Override
    protected Drawable[] resolveIconDrawables() {
        CharSequence[] values = getEntryValues();
        if (values == null) return new Drawable[0];

        Context context = getContext();
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int sizePx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, ICON_SIZE_DP, dm));
        float cornerRadius = sizePx * ICON_CORNER_RADIUS_FRACTION;

        int fgColor = Utils.getAppForegroundColor();
        int bgColor = isLightColor(fgColor) ? 0xFF000000 : 0xFFFFFFFF;

        // Resolve the current app icon style so FOLLOW can mirror it.
        String currentAppIconSuffix = null;
        try {
            String val = getSharedPreferences().getString("morphe_custom_branding_icon", null);
            if (val != null) currentAppIconSuffix = val.toLowerCase(Locale.US);
        } catch (Exception ignored) {}

        Drawable[] drawables = new Drawable[values.length];
        for (int i = 0; i < values.length; i++) {
            String suffix = values[i].toString().toLowerCase(Locale.US);
            if ("follow".equals(suffix) && currentAppIconSuffix != null) {
                suffix = currentAppIconSuffix;
            }
            drawables[i] = buildNotificationIconDrawable(context, suffix, sizePx, cornerRadius, fgColor, bgColor);
        }
        return drawables;
    }

    @Nullable
    private static Drawable buildNotificationIconDrawable(
            Context context, String suffix, int sizePx, float cornerRadius,
            @ColorInt int fgColor, @ColorInt int bgColor) {
        try {
            // ORIGINAL - use the app's dedicated notification icon resource (injected at patch time).
            if ("original".equals(suffix)) {
                String originalNotifName = IconListPreference.getOriginalNotificationIconName();
                if (originalNotifName != null && !originalNotifName.isEmpty()) {
                    int resId = resolveResId(originalNotifName);
                    if (resId != 0) {
                        Drawable notif = context.getDrawable(resId);
                        if (notif != null) {
                            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(bmp);
                            Path clip = new Path();
                            clip.addRoundRect(0, 0, sizePx, sizePx, cornerRadius, cornerRadius, Path.Direction.CW);
                            canvas.clipPath(clip);
                            canvas.drawColor(bgColor);
                            int notifSize = Math.round(sizePx * NOTIF_ICON_SIZE_FRACTION);
                            int notifOffset = (sizePx - notifSize) / 2;
                            notif.mutate().setColorFilter(new PorterDuffColorFilter(fgColor, PorterDuff.Mode.SRC_IN));
                            notif.setBounds(notifOffset, notifOffset, notifOffset + notifSize, notifOffset + notifSize);
                            notif.draw(canvas);
                            return new BitmapDrawable(context.getResources(), bmp);
                        }
                    }
                }
                return null;
            }

            int notifResId = resolveResId("morphe_notification_icon_" + suffix);
            if (notifResId == 0) return null;

            Drawable notif = context.getDrawable(notifResId);
            if (notif == null) return null;

            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);

            Path clip = new Path();
            clip.addRoundRect(0, 0, sizePx, sizePx, cornerRadius, cornerRadius, Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawColor(bgColor);

            int notifSize = Math.round(sizePx * NOTIF_ICON_SIZE_FRACTION);
            int notifOffset = (sizePx - notifSize) / 2;
            notif.mutate().setColorFilter(new PorterDuffColorFilter(fgColor, PorterDuff.Mode.SRC_IN));
            notif.setBounds(notifOffset, notifOffset, notifOffset + notifSize, notifOffset + notifSize);
            notif.draw(canvas);

            return new BitmapDrawable(context.getResources(), bmp);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isLightColor(@ColorInt int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 > 0.5;
    }
}
