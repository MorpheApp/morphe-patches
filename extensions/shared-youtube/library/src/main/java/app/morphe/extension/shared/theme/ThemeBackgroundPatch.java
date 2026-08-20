/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

/**
 * Changes the app background color while the app runs.
 * <p>
 * The background of the app is not painted by app code, it is resolved by the resource system from
 * XML: the window background is {@code ?ytBaseBackground}, which resolves to a color resource.
 * Nothing in the app can be hooked to change that, but the resource system picks a resource
 * variant using the {@link Configuration} of the context it is resolved with.
 * <p>
 * The patch writes one {@code values-mccNNN/colors.xml} for every dark background and one
 * {@code values-mncNNN/colors.xml} for every light background, and this class selects one of them
 * by overriding {@link Configuration#mcc} and {@link Configuration#mnc} of every context the app
 * attaches. Both qualifiers are ignored by the app itself and affect no other resource.
 * <p>
 * The config value of a background is its ordinal plus one, and {@link #APP_DEFAULT_CONFIG_VALUE}
 * is used for the unpatched colors of the app. The patch relies on the same numbering.
 */
@SuppressWarnings("unused")
public class ThemeBackgroundPatch {

    public interface Background {
        /**
         * If the color of this background is a Material You system color,
         * which only exists on Android 12 and later.
         */
        boolean isMaterialYou();
    }

    /**
     * Important: Existing values cannot be renamed or removed, and new values can only be appended.
     * The ordinal of a value selects its resource variant, so changing the order of this enum
     * changes the background of everyone who already selected one.
     */
    public enum DarkThemeBackground implements Background {
        APP_DEFAULT(false),
        PURE_BLACK(false),
        MATERIAL_YOU_NEUTRAL(true),
        MATERIAL_YOU_PRIMARY(true),
        MATERIAL_YOU_SECONDARY(true),
        MATERIAL_YOU_TERTIARY(true),
        MODERN_YOUTUBE(false),
        CLASSIC_YOUTUBE(false),
        CATPPUCCIN_MOCHA(false),
        DARK_PINK(false),
        DARK_BLUE(false),
        DARK_GREEN(false),
        DARK_YELLOW(false),
        DARK_ORANGE(false),
        DARK_RED(false);

        private final boolean materialYou;

        DarkThemeBackground(boolean materialYou) {
            this.materialYou = materialYou;
        }

        @Override
        public boolean isMaterialYou() {
            return materialYou;
        }
    }

    /**
     * @see DarkThemeBackground
     */
    public enum LightThemeBackground implements Background {
        APP_DEFAULT(false),
        WHITE(false),
        MATERIAL_YOU_NEUTRAL(true),
        MATERIAL_YOU_PRIMARY(true),
        MATERIAL_YOU_SECONDARY(true),
        MATERIAL_YOU_TERTIARY(true),
        CATPPUCCIN_LATTE(false),
        LIGHT_PINK(false),
        LIGHT_BLUE(false),
        LIGHT_GREEN(false),
        LIGHT_YELLOW(false),
        LIGHT_ORANGE(false),
        LIGHT_RED(false);

        private final boolean materialYou;

        LightThemeBackground(boolean materialYou) {
            this.materialYou = materialYou;
        }

        @Override
        public boolean isMaterialYou() {
            return materialYou;
        }
    }

    public static final String SETTINGS_KEY_DARK = "morphe_theme_background_dark";
    public static final String SETTINGS_KEY_LIGHT = "morphe_theme_background_light";

    public static final DarkThemeBackground DEFAULT_DARK = DarkThemeBackground.PURE_BLACK;
    public static final LightThemeBackground DEFAULT_LIGHT = LightThemeBackground.WHITE;

    /**
     * Config value of {@code APP_DEFAULT}. No resource variant uses it, so the app colors are used.
     */
    private static final int APP_DEFAULT_CONFIG_VALUE = 1;

    /**
     * Name of the shared preferences of Morphe.
     * <p>
     * The settings cannot be used here because the first context is wrapped before the extension
     * has a context of its own, and reading a setting without a context crashes the app.
     */
    private static final String PREFERENCES_NAME = "morphe_prefs";

    private static int darkConfigValue = -1;
    private static int lightConfigValue = -1;

    /**
     * Injection point.
     * <p>
     * Called with the base context of every context of the app that attaches one.
     */
    public static Context wrapContext(Context base) {
        try {
            if (base == null) {
                return null;
            }

            resolveConfigValues(base);

            Configuration configuration = base.getResources().getConfiguration();
            if (configuration.mcc == darkConfigValue && configuration.mnc == lightConfigValue) {
                // Context is created from a context that is already wrapped.
                return base;
            }

            Configuration override = new Configuration(configuration);
            override.mcc = darkConfigValue;
            override.mnc = lightConfigValue;

            return base.createConfigurationContext(override);
        } catch (Exception ex) {
            Logger.printException(() -> "wrapContext failure", ex);
            return base;
        }
    }

    /**
     * The selected backgrounds require an app restart to change,
     * so the preferences are read only once.
     */
    private static void resolveConfigValues(Context context) {
        if (darkConfigValue > 0) {
            return;
        }

        SharedPreferences preferences = null;
        try {
            preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        } catch (Exception ex) {
            // Preferences of the app are not available before the device is unlocked.
            Logger.printInfo(() -> "Could not load preferences", ex);
        }

        darkConfigValue = configValue(preferences, SETTINGS_KEY_DARK,
                DarkThemeBackground.values(), DEFAULT_DARK);
        lightConfigValue = configValue(preferences, SETTINGS_KEY_LIGHT,
                LightThemeBackground.values(), DEFAULT_LIGHT);

        Logger.printDebug(() -> "Theme background config values: "
                + darkConfigValue + " " + lightConfigValue);
    }

    private static int configValue(@Nullable SharedPreferences preferences, String key,
                                   Background[] values, @NonNull Background defaultValue) {
        Background selected = defaultValue;

        if (preferences != null) {
            String name = preferences.getString(key, null);
            if (name != null) {
                for (Background value : values) {
                    if (((Enum<?>) value).name().equals(name)) {
                        selected = value;
                        break;
                    }
                }
            }
        }

        if (selected.isMaterialYou() && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Material You colors do not exist and resolving them crashes the app.
            return APP_DEFAULT_CONFIG_VALUE;
        }

        return ((Enum<?>) selected).ordinal() + 1;
    }
}
