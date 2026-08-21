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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewStub;
import android.widget.TextView;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
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
 * uses {@link ThemeColorOverlay} instead to give the same color resources a value of its
 * own. That needs Android 14 or later.
 */
@SuppressWarnings("unused")
public class ThemeColorPatch {

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

    public enum ThemeColorDark implements Background {
        APP_DEFAULT,
        PURE_BLACK,
        MATERIAL_YOU_NEUTRAL(true, false),
        MATERIAL_YOU_PRIMARY(true, false),
        MATERIAL_YOU_SECONDARY(true, false),
        MATERIAL_YOU_TERTIARY(true, false),
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

        ThemeColorDark() {
            this(false, false);
        }

        ThemeColorDark(boolean materialYou, boolean custom) {
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

    public enum ThemeColorLight implements Background {
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

        ThemeColorLight() {
            this(false, false);
        }

        ThemeColorLight(boolean materialYou, boolean custom) {
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

    /**
     * Mobile country codes of 100 to 199 are not assigned to any country, so a device never
     * reports one. Every variant of the patch uses a code of that range, otherwise the system
     * uses a variant on its own while it draws the splash screen of the app, because that is
     * resolved with the configuration of the device.
     */
    private static final int UNUSED_MOBILE_COUNTRY_CODE = 100;

    /**
     * Index of the first color of the 9 bit palette, and the index ranges of the two themes.
     * The patch uses the same numbering.
     */
    private static final int PALETTE_INDEX_OFFSET = 100;
    private static final int DARK_INDEX_OFFSET = 0;
    private static final int LIGHT_INDEX_OFFSET = 700;

    private static int darkConfigValue = -1;
    private static int lightConfigValue = -1;

    /**
     * If a background of the user is in use and its overlay must be loaded into every context.
     */
    private static boolean useOverlay;

    /**
     * If the colors Morphe uses for itself were resolved with the theme they belong to.
     */
    private static boolean morpheColorsUpdated;

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

            // A variant belongs to one theme only, so the background of the theme the app shows
            // is the one to ask for. The night mode of the device says nothing about it, the app
            // has a theme setting of its own. A theme change recreates the activity, and the
            // index of the other theme is used from then on.
            final int index = Utils.isDarkModeEnabled() ? darkConfigValue : lightConfigValue;

            Context context;
            if (configuration.mcc == mobileCountryCode(index)
                    && configuration.mnc == mobileNetworkCode(index)) {
                // Context is created from a context that is already wrapped.
                context = base;
            } else {
                Configuration override = new Configuration(configuration);
                setVariantOf(override, index);

                context = base.createConfigurationContext(override);
            }

            if (isCustomBackgroundSupported() && useOverlay) {
                ThemeColorOverlay.applyTo(context);
            }

            updateMorpheColors(context);

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

        Background dark = THEME_BACKGROUND_DARK.get();
        Background light = THEME_BACKGROUND_LIGHT.get();
        Logger.printDebug(() -> "Theme dark: " + darkConfigValue + " light:" + lightConfigValue);

        darkConfigValue = configValue(dark, true);
        lightConfigValue = configValue(light, false);

        updateOverlay(context, dark, light);
    }

    /**
     * Morphe uses the background of the app for its own dialogs and settings, and it resolves
     * the color with the context of the app. That context can be of the other theme than the one
     * the app shows, because the app can use a theme of its own while the device uses the other,
     * and a background belongs to one theme only. Both colors are resolved again here, with the
     * theme each of them belongs to.
     */
    private static void updateMorpheColors(Context context) {
        if (morpheColorsUpdated || !Utils.isContextSet()) {
            return;
        }
        morpheColorsUpdated = true;

        // An app without a light theme has no light colors to replace.
        if (!darkColorResourceNames().isEmpty()) {
            ThemeUtils.setThemeDarkColor(selectedBackgroundColor(context, true));
        }
        if (!lightColorResourceNames().isEmpty()) {
            ThemeUtils.setThemeLightColor(selectedBackgroundColor(context, false));
        }
    }

    /**
     * If the theme the app shows uses the background of the app itself.
     * <p>
     * No color of the app is replaced then, and patch code that recolors app components to match
     * a selected background must leave them untouched, otherwise the app looks different from
     * the unpatched app.
     */
    public static boolean isAppDefaultBackground() {
        final boolean dark = Utils.isDarkModeEnabled();

        // The config value is used instead of the setting because a Material-You background
        // falls back to the app default on Android 11 and earlier.
        if (dark) {
            return darkConfigValue == (DARK_INDEX_OFFSET + APP_DEFAULT_CONFIG_VALUE);
        }
        return lightConfigValue == (LIGHT_INDEX_OFFSET + APP_DEFAULT_CONFIG_VALUE);
    }

    private static int selectedBackgroundColor(Context context, boolean dark) {
        Background background = dark
                ? THEME_BACKGROUND_DARK.get()
                : THEME_BACKGROUND_LIGHT.get();

        return getBackgroundColor(context, dark, ((Enum<?>) background).ordinal());
    }

    /**
     * Asks for the variant of a background, using a configuration a device never has.
     *
     * @param index Index of the background, which the patch uses with the same encoding.
     */
    private static void setVariantOf(Configuration configuration, int index) {
        configuration.mcc = mobileCountryCode(index);
        configuration.mnc = mobileNetworkCode(index);
    }

    private static int mobileCountryCode(int index) {
        return UNUSED_MOBILE_COUNTRY_CODE + (index >> 5);
    }

    private static int mobileNetworkCode(int index) {
        return 1 + (index & 31);
    }

    private static int configValue(Background background, boolean dark) {
        // The two themes use indices that never overlap, so that a variant of one of them
        // is never used by the other.
        final int offset = dark ? DARK_INDEX_OFFSET : LIGHT_INDEX_OFFSET;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && background.isMaterialYou()) {
            // Material-You colors do not exist and resolving them crashes the app.
            return offset + APP_DEFAULT_CONFIG_VALUE;
        }

        if (background.isCustom() && !isCustomBackgroundSupported()) {
            StringSetting setting = dark
                    ? THEME_BACKGROUND_DARK_CUSTOM_COLOR
                    : THEME_BACKGROUND_LIGHT_CUSTOM_COLOR;
            return offset + PALETTE_INDEX_OFFSET + get9BitColorIndex(setting);
        }

        // A custom background has no resource variant of its own,
        // the color resources are replaced by the overlay instead.
        return offset + ((Enum<?>) background).ordinal() + 1;
    }

    private static int get9BitColorIndex(StringSetting colorSetting) {
        final int color = customColor(colorSetting);

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
                ThemeColorOverlay.unregisterIfRegistered(context);
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
            ThemeColorOverlay.register(context, colors);
        } catch (Exception ex) {
            // Overlays are a part of the system and a manufacturer can change how they behave.
            Logger.printException(() -> "Could not update the overlay of the app", ex);
        }
    }

    private static void addOverlayColors(Map<String, Integer> colors, String resourceNames,
                                         StringSetting customColorSetting) {
        final int color = customColor(customColorSetting);

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
    @ColorInt
    public static int getBackgroundColor(Context context, boolean dark, int index) {
        try {
            Background background = (dark
                    ? ThemeColorDark.values()
                    : ThemeColorLight.values())[index];

            if (background.isCustom()) {
                return customColor(dark);
            }

            // The color of a background is the value its resource variant declares, and the
            // variant is selected the same way the app selects the background it uses.
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            setVariantOf(configuration, configValue(background, dark));

            final String resourceName = backgroundColorResourceName(dark);
            if (resourceName.isEmpty()) {
                // The app has no theme of this kind, and no color of it to show.
                return ThemeUtils.getAppBackgroundColor();
            }

            final int identifier = ResourceUtils.getIdentifier(ResourceType.COLOR, resourceName);

            Context variant = context.createConfigurationContext(configuration);
            if (isCustomBackgroundSupported()) {
                ThemeColorOverlay.removeFrom(variant);
            }

            return variant.getColor(identifier);
        } catch (Exception ex) {
            Logger.printException(() -> "getBackgroundColor failure", ex);
            return ThemeUtils.getAppBackgroundColor();
        }
    }

    /**
     * Injection point.
     * <p>
     * Called with the view stub of a new content indicator of the pivot bar, which is the dot
     * of a tab and the count next to it, before either is shown.
     */
    public static void onNewContentIndicator(ViewStub stub) {
        try {
            stub.setOnInflateListener((inflatedStub, view) -> {
                Integer color = getIndicatorColor(view.getContext());
                if (color == null) {
                    return;
                }

                setIndicatorColor(view, color);

                // The pivot bar can set the background of an indicator after it is inflated,
                // and the color is applied again after the app is done with the view.
                view.post(() -> setIndicatorColor(view, color));
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onNewContentIndicator failure", ex);
        }
    }

    private static void setIndicatorColor(View view, int color) {
        Drawable background = view.getBackground();

        // Both indicators are a shape with a stroke of the app background color, and only the
        // fill of the shape is replaced. Mutate is needed, otherwise every user of the
        // drawable is changed as well.
        if (background instanceof GradientDrawable) {
            ((GradientDrawable) background.mutate()).setColor(color);
        }

        // The count is a text view, and its text must stay readable on the new color.
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(
                    getIndicatorTextColor(view.getContext()));
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
        return customColor(dark
                ? THEME_BACKGROUND_DARK_CUSTOM_COLOR
                : THEME_BACKGROUND_LIGHT_CUSTOM_COLOR);
    }

    /**
     * The color a custom background setting holds, or the color of its default value if the
     * user saved something that is not a color.
     * <p>
     * A background must be opaque, otherwise the app draws over itself.
     */
    @ColorInt
    private static int customColor(StringSetting setting) {
        String colorString = setting.get();

        try {
            return Color.parseColor(colorString) | 0xFF000000;
        } catch (IllegalArgumentException ex) {
            Logger.printException(() -> "Invalid custom color: " + colorString);
            return Color.parseColor(setting.resetToDefault()) | 0xFF000000;
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
