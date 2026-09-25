/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3287
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Picks the player icon for the selected style, a suffix on the base name such as {@code morphe_yt_copy_fluent}.
 * A style does not have to cover every icon, a missing variant falls back to what {@link Style#AUTO} shows.
 */
public final class PlayerIcons {

    public enum Style {
        AUTO(null),
        REGULAR(""),
        BOLD("_bold"),
        FLUENT("_fluent"),
        PHOSPHOR("_phosphor"),
        PHOSPHOR_LIGHT("_phosphor_light"),
        PHOSPHOR_FILL("_phosphor_fill"),
        PHOSPHOR_DUOTONE("_phosphor_duotone"),
        IONICONS("_ionicons"),
        SHARP("_sharp");

        @Nullable
        public final String suffix;

        Style(@Nullable String suffix) {
            this.suffix = suffix;
        }
    }

    private static final String AUTO_SUFFIX =
            LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS ? "" : "_bold";

    // Buttons keep the drawable they were created with, so a style change only applies after a restart.
    private static final Style STYLE = Settings.PLAYER_ICON_STYLE.get();
    private static final Style SHORTS_STYLE = Settings.SHORTS_ICON_STYLE.get();

    // Some buttons resolve their icon on every tap.
    private static final Map<String, String> names = new ConcurrentHashMap<>();

    private PlayerIcons() {
    }

    /**
     * @return Drawable name of a player button or swipe controls icon.
     */
    public static String name(String baseName) {
        return names.computeIfAbsent(baseName, base -> resolve(STYLE, base));
    }

    public static int id(String baseName) {
        return ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, name(baseName));
    }

    /**
     * For an icon the app itself provides in the thin and bold styles.
     *
     * @return Drawable name of the selected style variant of {@code baseName},
     *         or the app icon that matches the thin or bold style.
     */
    public static String name(String baseName, String appRegularName, String appBoldName) {
        String styled = styledVariant(STYLE, baseName);
        if (styled != null) return styled;

        final boolean useRegular = STYLE == Style.REGULAR
                || (STYLE != Style.BOLD && AUTO_SUFFIX.isEmpty());
        return useRegular ? appRegularName : appBoldName;
    }

    /**
     * For a Shorts icon the app itself provides.
     *
     * @return Drawable name of the selected Shorts style variant of {@code baseName}, or {@code appName}.
     */
    public static String shorts(String baseName, String appName) {
        return shorts(SHORTS_STYLE, baseName, appName);
    }

    public static String shorts(Style style, String baseName, String appName) {
        String styled = styledVariant(style, baseName);
        return styled != null ? styled : appName;
    }

    public static String resolve(Style style, String baseName) {
        String styled = styledVariant(style, baseName);
        if (styled != null) return styled;

        String auto = baseName + AUTO_SUFFIX;
        if (exists(auto)) return auto;

        return baseName;
    }

    @Nullable
    private static String styledVariant(Style style, String baseName) {
        if (style.suffix == null) return null;

        String styled = baseName + style.suffix;
        return exists(styled) ? styled : null;
    }

    /**
     * Starts the tap animation of the icon, if it is animated.
     * Call it after the click listener, so a button that swaps its icon animates the new one.
     */
    public static void animate(View button) {
        if (!(button instanceof ImageView imageView)) return;

        Drawable icon = imageView.getDrawable();
        if (icon instanceof DrawableWrapper wrapper) {
            icon = wrapper.getDrawable();
        }
        if (icon instanceof AnimatedVectorDrawable animated) {
            animated.start();
        }
    }

    public static boolean exists(String drawableName) {
        return ResourceUtils.getIdentifier(ResourceType.DRAWABLE, drawableName) != 0;
    }
}
