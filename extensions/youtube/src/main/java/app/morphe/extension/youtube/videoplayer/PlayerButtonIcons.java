/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3287
 * https://github.com/MorpheApp/morphe-patches/pull/3451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

/**
 * Applies the player icon style to player buttons the app sets by resource id, such as skip, settings and captions.
 * <p>
 * Screens outside the player use the same icons, so the resources themselves are left alone.
 */
@SuppressWarnings("unused")
public final class PlayerButtonIcons {

    // Style icon by the resource id of the app icon it replaces.
    private static final Map<Integer, String> styleBaseNames = new HashMap<>();

    static {
        add("yt_fill_experimental_skip_next_black_24", "morphe_player_next");
        add("yt_fill_experimental_skip_previous_black_24", "morphe_player_previous");
        add("yt_outline_experimental_gear_black_24", "morphe_player_settings");
        add("yt_fill_experimental_closed_captions_black_24", "morphe_player_captions_on");
        add("yt_outline_experimental_closed_captions_black_24", "morphe_player_captions_off");
        // Without the bold player.
        add("quantum_ic_closed_caption_white_24", "morphe_player_captions_on");
        add("quantum_ic_closed_caption_off_white_24", "morphe_player_captions_off");
    }

    private PlayerButtonIcons() {
    }

    // Some icons only exist in newer app targets.
    private static void add(String appName, String styleBaseName) {
        final int id = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, appName);
        if (id != 0) styleBaseNames.put(id, styleBaseName);
    }

    /**
     * Injection point.
     * Replaces {@link ImageView#setImageResource(int)}.
     */
    public static void setImageResource(ImageView view, int resId) {
        if (!applyStyle(view, resId)) {
            view.setImageResource(resId);
        }
    }

    /**
     * Injection point.
     * Called after the app has set the icon in its own way.
     *
     * @return If the style replaced the icon.
     */
    public static boolean applyStyle(ImageView view, int resId) {
        Drawable icon = styledIcon(resId);
        if (icon == null) return false;

        view.setImageDrawable(icon);
        return true;
    }

    /**
     * Injection point.
     * Replaces {@link Resources#getDrawable(int)} where the app loads an icon to tint it before setting it.
     */
    @SuppressWarnings("deprecation")
    public static Drawable getDrawable(Resources resources, int resId) {
        Drawable icon = styledIcon(resId);
        return icon != null ? icon : resources.getDrawable(resId);
    }

    @Nullable
    private static Drawable styledIcon(int resId) {
        String styleBaseName = styleBaseNames.get(resId);
        return styleBaseName == null ? null : PlayerIcons.styledDrawable(styleBaseName);
    }
}
