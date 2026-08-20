/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.theme;

import static app.morphe.extension.shared.settings.SharedYouTubeSettings.THEME_BACKGROUND_DARK;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.THEME_BACKGROUND_DARK_CUSTOM_COLOR;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.THEME_BACKGROUND_LIGHT;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.THEME_BACKGROUND_LIGHT_CUSTOM_COLOR;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;

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
 * <p>
 * Only a color that was compiled in can be selected this way, so the {@code CUSTOM} background
 * uses {@link ThemeBackgroundOverlay} instead to give the same color resources a value of its
 * own. That needs Android 14 or later.
 */
@SuppressWarnings("unused")
public class ThemeBackgroundPatch {

    public interface Background {
        /**
         * If the color of this background is a Material You system color,
         * which only exists on Android 12 and later.
         */
        boolean isMaterialYou();

        /**
         * If the color of this background is picked by the user and applied with an overlay.
         */
        boolean isCustom();
    }

    /**
     * Important: Existing values cannot be renamed or removed, and new values can only be appended.
     * The ordinal of a value selects its resource variant, so changing the order of this enum
     * changes the background of everyone who already selected one.
     */
    public enum DarkThemeBackground implements Background {
        APP_DEFAULT,
        PURE_BLACK,
        MATERIAL_YOU_NEUTRAL(true, false),
        MATERIAL_YOU_PRIMARY(true, false),
        MATERIAL_YOU_SECONDARY(true, false),
        MATERIAL_YOU_TERTIARY(true, false),
        MODERN_YOUTUBE,
        CLASSIC_YOUTUBE,
        CATPPUCCIN_MOCHA,
        DARK_PINK,
        DARK_BLUE,
        DARK_GREEN,
        DARK_YELLOW,
        DARK_ORANGE,
        DARK_RED,
        CUSTOM(false, true);

        private final boolean materialYou;
        private final boolean custom;

        DarkThemeBackground() {
            this(false, false);
        }

        DarkThemeBackground(boolean materialYou, boolean custom) {
            this.materialYou = materialYou;
            this.custom = custom;
        }

        @Override
        public boolean isMaterialYou() {
            return materialYou;
        }

        @Override
        public boolean isCustom() {
            return custom;
        }
    }

    /**
     * @see DarkThemeBackground
     */
    public enum LightThemeBackground implements Background {
        APP_DEFAULT,
        WHITE,
        MATERIAL_YOU_NEUTRAL(true, false),
        MATERIAL_YOU_PRIMARY(true, false),
        MATERIAL_YOU_SECONDARY(true, false),
        MATERIAL_YOU_TERTIARY(true, false),
        CATPPUCCIN_LATTE,
        LIGHT_PINK,
        LIGHT_BLUE,
        LIGHT_GREEN,
        LIGHT_YELLOW,
        LIGHT_ORANGE,
        LIGHT_RED,
        CUSTOM(false, true);

        private final boolean materialYou;
        private final boolean custom;

        LightThemeBackground() {
            this(false, false);
        }

        LightThemeBackground(boolean materialYou, boolean custom) {
            this.materialYou = materialYou;
            this.custom = custom;
        }

        @Override
        public boolean isMaterialYou() {
            return materialYou;
        }

        @Override
        public boolean isCustom() {
            return custom;
        }
    }

    /**
     * Availability of the custom dark background color.
     */
    public static final class CustomDarkBackgroundAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return THEME_BACKGROUND_DARK.get().isCustom();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(THEME_BACKGROUND_DARK);
        }
    }

    /**
     * Availability of the custom light background color.
     */
    public static final class CustomLightBackgroundAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return THEME_BACKGROUND_LIGHT.get().isCustom();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(THEME_BACKGROUND_LIGHT);
        }
    }

    /**
     * Config value of {@code APP_DEFAULT}. No resource variant uses it, so the app colors are used.
     */
    private static final int APP_DEFAULT_CONFIG_VALUE = 1;

    private static int darkConfigValue = -1;
    private static int lightConfigValue = -1;

    /**
     * If a background of the user is in use and its overlay must be loaded into every context.
     */
    private static boolean useOverlay;

    /**
     * If a background color of the user can be applied. An overlay that an app registers for
     * itself exists since Android 14, and no color can be added to the app on older versions.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static boolean isCustomBackgroundSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

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

            if (!Utils.isContextSet()) {
                // Context might be used before context is set.
                Utils.setContext(base);
            }

            resolveConfigValues(base);

            Configuration configuration = base.getResources().getConfiguration();
            Context context;
            if (configuration.mcc == darkConfigValue && configuration.mnc == lightConfigValue) {
                // Context is created from a context that is already wrapped.
                context = base;
            } else {
                Configuration override = new Configuration(configuration);
                override.mcc = darkConfigValue;
                override.mnc = lightConfigValue;

                context = base.createConfigurationContext(override);
            }

            if (isCustomBackgroundSupported() && useOverlay) {
                ThemeBackgroundOverlay.applyTo(context);
            }

            return context;
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

        Background dark = selectedBackground(THEME_BACKGROUND_DARK, DarkThemeBackground.values());
        Background light = selectedBackground(THEME_BACKGROUND_LIGHT, LightThemeBackground.values());

        darkConfigValue = configValue(dark, true);
        lightConfigValue = configValue(light, false);

        Logger.printDebug(() -> "Theme background config values: " + darkConfigValue + " " + lightConfigValue);

        updateOverlay(context, dark, light);
    }

    private static Background selectedBackground(EnumSetting<? extends Background> setting,
                                                 Background[] values) {
        Enum<?> name = setting.get();
        for (Background value : values) {
            if (value.equals(name)) {
                return value;
            }
        }

        return setting.defaultValue;
    }

    private static int configValue(Background background, boolean dark) {
        if (background.isMaterialYou() && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Material-You colors do not exist and resolving them crashes the app.
            return APP_DEFAULT_CONFIG_VALUE;
        }

        if (background.isCustom() && !isCustomBackgroundSupported()) {
            StringSetting setting = dark
                    ? THEME_BACKGROUND_DARK_CUSTOM_COLOR
                    : THEME_BACKGROUND_LIGHT_CUSTOM_COLOR;
            String colorString = setting.get();

            return 100 + get9BitColorIndex(colorString, setting.defaultValue);
        }

        // A custom background has no resource variant of its own,
        // the color resources are replaced by the overlay instead.
        return ((Enum<?>) background).ordinal() + 1;
    }

    private static int get9BitColorIndex(String colorString, String defaultColor) {
        int color;
        try {
            color = Color.parseColor(colorString);
        } catch (IllegalArgumentException ex) {
            Logger.printException(() -> "Invalid color: " + colorString);
            color = Color.parseColor(defaultColor);
        }

        final int r = (color >> 16) & 0xFF;
        final int g = (color >> 8) & 0xFF;
        final int b = color & 0xFF;

        final int r3 = Math.round(r * 7f / 255f);
        final int g3 = Math.round(g * 7f / 255f);
        final int b3 = Math.round(b * 7f / 255f);

        return (r3 << 6) | (g3 << 3) | b3;
    }

    /**
     * Registers, updates or removes the overlay that gives the color resources of the app the
     * color the user picked.
     */
    private static void updateOverlay(Context context, Background dark, Background light) {
        if (!isCustomBackgroundSupported()) {
            return;
        }

        useOverlay = dark.isCustom() || light.isCustom();

        try {
            if (!useOverlay) {
                ThemeBackgroundOverlay.unregisterIfRegistered(context);
                return;
            }

            Map<String, Integer> colors = new LinkedHashMap<>();

            if (dark.isCustom()) {
                addOverlayColors(colors, darkColorResourceNames(), THEME_BACKGROUND_DARK_CUSTOM_COLOR);
            }

            if (light.isCustom()) {
                addOverlayColors(colors, lightColorResourceNames(), THEME_BACKGROUND_LIGHT_CUSTOM_COLOR);
            }

            // The system deletes an overlay of the app when the app is installed again, so it is
            // registered on every start and not only after the user picks another color.
            ThemeBackgroundOverlay.register(context, colors);
        } catch (Exception ex) {
            // Overlays are a part of the system and a manufacturer can change how they behave.
            Logger.printException(() -> "Could not update the overlay of the app", ex);
        }
    }

    private static void addOverlayColors(Map<String, Integer> colors, String resourceNames,
                                         StringSetting colorSetting) {
        String colorString = colorSetting.get();
        int color;
        try {
            color = Color.parseColor(colorString);
        } catch (IllegalArgumentException ex) {
            Logger.printException(() -> "Invalid custom color: " + colorString);
            color = Color.parseColor(colorSetting.resetToDefault());
        }

        // A background must be opaque, otherwise the app draws over itself.
        color |= 0xFF000000;

        for (String resourceName : resourceNames.split(",")) {
            if (!resourceName.isEmpty()) {
                colors.put(resourceName, color);
            }
        }
    }

    /**
     * The color of a background, used to show it next to the name in the app settings.
     *
     * @param dark  If the background is of the dark theme.
     * @param index Index of the background, which is the ordinal of its enum value.
     */
    public static int getBackgroundColor(Context context, boolean dark, int index) {
        try {
            Background background = dark
                    ? DarkThemeBackground.values()[index]
                    : LightThemeBackground.values()[index];

            if (background.isCustom()) {
                return customColor(dark);
            }

            // The color of a background is the value its resource variant declares, and the
            // variant is selected the same way the app selects the background it uses.
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            if (dark) {
                configuration.mcc = configValue(background, true);
            } else {
                configuration.mnc = configValue(background, false);
            }

            final int identifier = ResourceUtils.getIdentifier(ResourceType.COLOR,
                    backgroundColorResourceName(dark));

            Context variant = context.createConfigurationContext(configuration);
            if (isCustomBackgroundSupported()) {
                ThemeBackgroundOverlay.removeFrom(variant);
            }

            return variant.getColor(identifier);
        } catch (Exception ex) {
            Logger.printException(() -> "getBackgroundColor failure", ex);
            return Utils.getAppBackgroundColor();
        }
    }

    /**
     * The color of the new content indicator, or null to keep the color of the app.
     * <p>
     * A Material You background does not go with the red of the app, which is why the indicator
     * follows the same palette. Every other background keeps the app color.
     */
    @Nullable
    public static Integer getIndicatorColor(Context context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return null;
            }

            final boolean dark = Utils.isDarkModeEnabled();
            Background background = dark
                    ? THEME_BACKGROUND_DARK.get()
                    : THEME_BACKGROUND_LIGHT.get();

            if (!background.isMaterialYou()) {
                return null;
            }

            return context.getColor(dark
                    ? android.R.color.system_accent1_100
                    : android.R.color.system_accent1_200);
        } catch (Exception ex) {
            Logger.printException(() -> "getIndicatorColor failure", ex);
            return null;
        }
    }

    /**
     * The color of the text of the new content count, which must be readable
     * on {@link #getIndicatorColor(Context)}.
     */
    public static int getIndicatorTextColor(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getColor(android.R.color.system_neutral1_900);
        }

        // Never reached, an indicator color exists only with a Material You background.
        return Color.BLACK;
    }

    private static int customColor(boolean dark) {
        StringSetting setting = dark
                ? THEME_BACKGROUND_DARK_CUSTOM_COLOR
                : THEME_BACKGROUND_LIGHT_CUSTOM_COLOR;
        String colorString = setting.get();

        try {
            return Color.parseColor(colorString) | 0xFF000000;
        } catch (IllegalArgumentException ex) {
            Logger.printException(() -> "Invalid custom color: " + colorString);
            return Color.parseColor(setting.resetToDefault());
        }
    }

    /**
     * The first name is the color the app uses for the background itself.
     */
    private static String backgroundColorResourceName(boolean dark) {
        String resourceNames = dark ? darkColorResourceNames() : lightColorResourceNames();
        return resourceNames.split(",")[0];
    }

    /**
     * Injection point.
     * <p>
     * Names of the dark background color resources, separated by a comma.
     * The first name is the color the app uses for the background itself.
     */
    private static String darkColorResourceNames() {
        return ""; // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Names of the light background color resources, separated by a comma.
     * The first name is the color the app uses for the background itself.
     */
    private static String lightColorResourceNames() {
        return ""; // Modified during patching.
    }
}
