/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2636
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.theme;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeColorPatch;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * Container of the header of a playlist, album or artist page.
 * <p>
 * The app ends the artwork of the header in a translucent black, which only blends into a pure
 * black background. With any other background the header ends in a hard edge against the page,
 * so a fade into the background color is drawn over the lower part of the artwork.
 */
@SuppressWarnings("unused")
public class HeaderFadeLayout extends FrameLayout {

    /**
     * Tells a fade of this class apart from a foreground of the app.
     */
    private static class HeaderFadeDrawable extends GradientDrawable {
        HeaderFadeDrawable(@ColorInt int backgroundColor) {
            // The fade starts at the middle of the artwork, which the app has darkened
            // to almost nothing by then.
            super(Orientation.TOP_BOTTOM, new int[]{
                    // Not a transparent black, which would darken the middle of the fade.
                    backgroundColor & 0x00FFFFFF,
                    backgroundColor & 0x00FFFFFF,
                    backgroundColor
            });
        }
    }

    public HeaderFadeLayout(Context context) {
        super(context);
    }

    public HeaderFadeLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public HeaderFadeLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // The fade is created once for a view and not for every layout pass.
    @SuppressLint("DrawAllocation")
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        try {
            // The app colors are left alone, otherwise the header of an unpatched app changes.
            if (ThemeColorPatch.isAppDefaultColor()) {
                return;
            }

            ImageView artwork = findArtwork(this, 0, 0);
            if (artwork == null || artwork.getForeground() instanceof HeaderFadeDrawable) {
                return;
            }

            // The app always shows its dark theme, while the shared background color
            // follows the night mode of the device.
            @ColorInt final int backgroundColor = ThemeUtils.getThemeDarkColor();
            artwork.setForeground(new HeaderFadeDrawable(backgroundColor));

            // Litho reuses the views of its components, and a fade left behind shows up
            // on whatever the view is used for next.
            artwork.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View view) {
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View view) {
                    view.removeOnAttachStateChangeListener(this);

                    Drawable foreground = view.getForeground();
                    if (foreground instanceof HeaderFadeDrawable) {
                        view.setForeground(null);
                    }
                }
            });

            Logger.printDebug(() -> "Header fade applied: "
                    + Utils.getColorHexString(backgroundColor));
        } catch (Exception ex) {
            Logger.printException(() -> "onLayout failure", ex);
        }
    }

    /**
     * The artwork of the header, which is the view that covers the top of the header.
     *
     * @param offsetX Position of the group in this container.
     * @param offsetY Position of the group in this container.
     */
    @Nullable
    private ImageView findArtwork(ViewGroup group, int offsetX, int offsetY) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            final int childX = offsetX + child.getLeft();
            final int childY = offsetY + child.getTop();

            if (child instanceof ImageView) {
                if (childX == 0 && childY == 0
                        && child.getWidth() == getWidth()
                        && child.getHeight() > 0
                        && child.getHeight() < getHeight()) {
                    return (ImageView) child;
                }
            } else if (child instanceof ViewGroup) {
                ImageView artwork = findArtwork((ViewGroup) child, childX, childY);
                if (artwork != null) {
                    return artwork;
                }
            }
        }

        return null;
    }
}
