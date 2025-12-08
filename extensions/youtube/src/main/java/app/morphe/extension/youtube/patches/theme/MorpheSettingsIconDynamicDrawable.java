package app.morphe.extension.youtube.patches.theme;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.Utils;

/**
 * Dynamic drawable that is either the regular or bolded Morphe preference icon.
 *
 * This is needed because the YouTube Morphe preference intent is an AndroidX preference,
 * and AndroidX classes are not built into Android which makes programmatically changing
 * the preference thru patching overly complex. This solves the problem by using a drawable
 * wrapper to dynamically pick which icon drawable to use at runtime.
 */
@SuppressWarnings("unused")
public class MorpheSettingsIconDynamicDrawable extends Drawable {

    private final Drawable icon;

    public MorpheSettingsIconDynamicDrawable() {
        final int resId = Utils.getResourceIdentifier(ResourceType.DRAWABLE,
                Utils.appIsUsingBoldIcons()
                        ? "morphe_settings_icon_bold"
                        : "morphe_settings_icon"
        );

        icon = Utils.getContext().getDrawable(resId);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        icon.draw(canvas);
    }

    @Override
    public void setAlpha(int alpha) {
        icon.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        icon.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return icon.getOpacity();
    }

    @Override
    public int getIntrinsicWidth() {
        return icon.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return icon.getIntrinsicHeight();
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        icon.setBounds(left, top, right, bottom);
    }

    @Override
    public void setBounds(@NonNull Rect bounds) {
        super.setBounds(bounds);
        icon.setBounds(bounds);
    }

    @Override
    public void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        icon.setBounds(bounds);
    }
}