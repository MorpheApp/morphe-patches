/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.theme;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Changes the app theme color while the app runs.
 * <p>
 * The color of the app is not painted by app code, it is resolved by the resource system from
 * XML: the window color is {@code ?ytBaseBackground}, which resolves to a color resource.
 * Nothing in the app can be hooked to change that, but the resource system picks a resource
 * variant using the {@link Configuration} of the context it is resolved with.
 * <p>
 * The patch writes one {@code values-mccNNNN/colors.xml} for every dark color and one
 * {@code values-mncNNNN/colors.xml} for every light color, and this class selects one of them
 * by overriding {@link Configuration#mcc} and {@link Configuration#mnc} of every context the app
 * attaches. Both qualifiers are ignored by the app itself and affect no other resource.
 * <p>
 * The config value of a color is its ordinal plus one, and {@link #APP_DEFAULT_CONFIG_VALUE}
 * is used for the unpatched colors of the app. The patch relies on the same numbering.
 * <p>
 * Only a color that was compiled in can be selected this way, so the {@code CUSTOM} color
 * uses {@link ThemeColorOverlay} instead to give the same color resources a value of its
 * own. That needs Android 14 or later.
 * <p>
 * All of this is skipped for a theme color that was set with a patch option, see
 * {@link #isPatchedTheme()}.
 */
@SuppressWarnings("unused")
public class ThemeColorPatch {

    public interface ThemeColor {
        enum Kind {
            /** A color that every Android version has. */
            PLAIN,
            /** A Material You system color, which only Android 12 and later has. */
            MATERIAL_YOU,
            /**
             * Not a Material You color, but the accents follow the palette anyway,
             * which is what an AMOLED display needs.
             */
            MATERIAL_YOU_ACCENT,
            /** A color the user picks, which is applied with an overlay. */
            CUSTOM
        }

        Kind kind();

        /**
         * If the color only exists on Android 12 and later.
         */
        default boolean isMaterialYou() {
            return kind() == Kind.MATERIAL_YOU;
        }

        /**
         * If what the app draws with a color of its own, such as the new
         * content indicator, follows the Material You palette.
         */
        default boolean usesMaterialYouAccent() {
            return kind() == Kind.MATERIAL_YOU || kind() == Kind.MATERIAL_YOU_ACCENT;
        }

        default boolean isCustom() {
            return kind() == Kind.CUSTOM;
        }
    }

    public enum ThemeColorDark implements ThemeColor {
        APP_DEFAULT,
        PURE_BLACK,
        MATERIAL_YOU_PURE_BLACK(Kind.MATERIAL_YOU_ACCENT),
        MATERIAL_YOU_NEUTRAL(Kind.MATERIAL_YOU),
        MATERIAL_YOU_PRIMARY(Kind.MATERIAL_YOU),
        MATERIAL_YOU_SECONDARY(Kind.MATERIAL_YOU),
        MATERIAL_YOU_TERTIARY(Kind.MATERIAL_YOU),
        CATPPUCCIN_MOCHA,
        CLASSIC_YOUTUBE,
        DARK_PINK,
        DARK_BLUE,
        DARK_GREEN,
        DARK_YELLOW,
        DARK_ORANGE,
        DARK_RED,
        CUSTOM(Kind.CUSTOM);

        private final Kind kind;

        ThemeColorDark() {
            this(Kind.PLAIN);
        }

        ThemeColorDark(Kind kind) {
            this.kind = kind;
        }

        @Override
        public Kind kind() {
            return kind;
        }
    }

    public enum ThemeColorLight implements ThemeColor {
        APP_DEFAULT,
        WHITE,
        MATERIAL_YOU_WHITE(Kind.MATERIAL_YOU_ACCENT),
        MATERIAL_YOU_NEUTRAL(Kind.MATERIAL_YOU),
        MATERIAL_YOU_PRIMARY(Kind.MATERIAL_YOU),
        MATERIAL_YOU_SECONDARY(Kind.MATERIAL_YOU),
        MATERIAL_YOU_TERTIARY(Kind.MATERIAL_YOU),
        CATPPUCCIN_LATTE,
        LIGHT_PINK,
        LIGHT_BLUE,
        LIGHT_GREEN,
        LIGHT_YELLOW,
        LIGHT_ORANGE,
        LIGHT_RED,
        CUSTOM(Kind.CUSTOM);

        private final Kind kind;

        ThemeColorLight() {
            this(Kind.PLAIN);
        }

        ThemeColorLight(Kind kind) {
            this.kind = kind;
        }

        @Override
        public Kind kind() {
            return kind;
        }
    }

    /**
     * Availability of the color of a custom color.
     */
    public static class ThemeColorCustomAvailability implements Setting.Availability {
        private final EnumSetting<? extends ThemeColor> setting;

        public ThemeColorCustomAvailability(EnumSetting<? extends ThemeColor> setting) {
            this.setting = setting;
        }

        @Override
        public boolean isAvailable() {
            return setting.get().isCustom();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(setting);
        }
    }

    public static class ThemeColorChangeForegroundAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return !SharedYouTubeSettings.THEME_COLOR_DARK.isSetToDefault() ||
                    !SharedYouTubeSettings.THEME_COLOR_LIGHT.isSetToDefault();

        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(SharedYouTubeSettings.THEME_COLOR_DARK,
                    SharedYouTubeSettings.THEME_COLOR_LIGHT);
        }
    }


    /**
     * Config value of {@code APP_DEFAULT}. No resource variant uses it, so the app colors are used.
     */
    private static final int APP_DEFAULT_CONFIG_VALUE = 1;

    /**
     * Resource reference every Material You color of a patch option starts with.
     */
    private static final String MATERIAL_YOU_COLOR_PREFIX = "@android:color/system_";

    /**
     * A mobile country code and a mobile network code are three digits, so a device never reports
     * one above 999. Every variant of the patch uses a code above it, otherwise the system uses a
     * variant on its own while it draws the splash screen of the app, because that is resolved
     * with the configuration of the device.
     */
    private static final int UNREACHABLE_MOBILE_CODE = 1000;

    /**
     * Index of the first color of the 8 bit palette, and the index ranges of the two themes.
     * The patch uses the same numbering.
     */
    private static final int PALETTE_INDEX_OFFSET = 100;

    /**
     * The levels of Lightness, Chroma and Hue in the 8 bit OKLCH palette.
     * The patch generates the variants with the same values, and both must stay identical.
     */
    private static final float[] PALETTE_L_LEVELS_DARK = {0.0f, 0.02f, 0.05f, 0.1f, 0.2f, 0.35f, 0.6f, 1.0f};
    private static final float[] PALETTE_L_LEVELS_LIGHT = {0.0f, 0.4f, 0.65f, 0.8f, 0.9f, 0.95f, 0.98f, 1.0f};
    private static final float[] PALETTE_C_LEVELS = {0.0f, 0.03f, 0.07f, 0.15f};
    private static final float[] PALETTE_H_LEVELS = {0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f};

    private static final int DARK_INDEX_OFFSET = 0;
    private static final int LIGHT_INDEX_OFFSET = 400;

    private static int darkConfigValue = -1;
    private static int lightConfigValue = -1;

    /**
     * If the color of a theme is one the user picked, and the overlay of that theme must be
     * loaded into every context that shows it.
     */
    private static boolean useDarkOverlay;
    private static boolean useLightOverlay;

    /**
     * The color selected for each theme, or zero for a theme the app does not have.
     * Resolved once, with the resource variant of the theme it belongs to.
     */
    @ColorInt
    private static int darkThemeColor;
    @ColorInt
    private static int lightThemeColor;

    private static boolean themeColorsResolved;

    /**
     * Name of the theme that draws the splash screen of a theme. The patch generates one for
     * every theme it has a color of, and uses the same numbering.
     */
    private static final String SPLASH_THEME_NAME = "morphe_splash_theme_";

    private static boolean splashScreenThemeApplied;

    /**
     * If a theme color of the user can be applied. An overlay that an app registers for
     * itself exists since Android 14, and no color can be added to the app on older versions.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static boolean isCustomColorSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /**
     * Injection point.
     * <p>
     * Called with the base context of every context of the app that attaches one.
     */
    public static Context wrapContext(Context base) {
        try {
            if (base == null || !isPatchIncluded()) {
                return null;
            }

            if (!Utils.isContextSet()) {
                // Context might be used before context is set.
                Utils.setContext(base);
            }

            if (isPatchedTheme()) {
                return base;
            }

            resolveConfigValues(base);

            Configuration configuration = base.getResources().getConfiguration();

            // A variant belongs to one theme only, so the color of the theme the app shows
            // is the one to ask for. A theme change recreates the activity, and the index of the
            // other theme is used from then on.
            final boolean dark = isDarkTheme();
            if (SharedYouTubeSettings.THEME_LAST_USED_DARK_MODE.get() != dark) {
                // Contexts that attach before the app resolves its theme can then select the
                // variant of the theme the app is about to show.
                SharedYouTubeSettings.THEME_LAST_USED_DARK_MODE.save(dark);
            }

            final boolean changeForeground = SharedYouTubeSettings.THEME_COLOR_CHANGE_FOREGROUND.get();
            final int darkIndex = darkVariantIndex(dark, changeForeground);
            final int lightIndex = lightVariantIndex(dark, changeForeground);

            Context context;
            if (configuration.mcc == mobileCountryCode(darkIndex)
                    && configuration.mnc == mobileNetworkCode(lightIndex)) {
                // Context is created from a context that is already wrapped.
                context = base;
            } else {
                // Only the codes are overridden. A copy of the configuration pins every other
                // value to what it is now, so the framework relaunches the activity to correct
                // a value the app pinned, and the relaunch pins it again.
                Configuration override = new Configuration();
                setVariantOf(override, darkIndex, lightIndex);

                context = base.createConfigurationContext(override);
            }

            if (isCustomColorSupported()) {
                ThemeColorOverlay.applyTo(context, dark, changeForeground);
            }

            resolveThemeColors(context);

            return context;
        } catch (Exception ex) {
            Logger.printException(() -> "wrapContext failure", ex);
            return base;
        }
    }

    /**
     * Injection point.
     * <p>
     * The app hands a configuration of the device to {@code Resources.updateConfiguration}, which
     * replaces the configuration of a resources object the whole app shares. The variant of the
     * selected color goes with it, and every color the app resolves after that is the one the app
     * ships with. The app does this after it leaves picture in picture.
     */
    public static Configuration keepThemeVariant(Configuration configuration) {
        try {
            // A config value is only resolved for a context the patch wrapped,
            // so this also covers an app the patch was not included in.
            if (configuration == null || darkConfigValue < 0 || isPatchedTheme()) {
                return configuration;
            }

            final boolean dark = isDarkTheme();
            final boolean changeForeground = SharedYouTubeSettings.THEME_COLOR_CHANGE_FOREGROUND.get();

            Configuration variant = new Configuration(configuration);
            setVariantOf(variant,
                    darkVariantIndex(dark, changeForeground),
                    lightVariantIndex(dark, changeForeground));

            if (configuration.mcc != variant.mcc || configuration.mnc != variant.mnc) {
                Logger.printDebug(() -> "Theme variant the app replaced: "
                        + configuration.mcc + "/" + configuration.mnc
                        + " restored to: " + variant.mcc + "/" + variant.mnc);
            }

            return variant;
        } catch (Exception ex) {
            Logger.printException(() -> "keepThemeVariant failure", ex);
            return configuration;
        }
    }

    /**
     * The theme the app does not show keeps the colors of the app, unless the app draws its
     * foreground with them.
     */
    private static int darkVariantIndex(boolean dark, boolean changeForeground) {
        return dark || changeForeground
                ? darkConfigValue
                : DARK_INDEX_OFFSET + APP_DEFAULT_CONFIG_VALUE;
    }

    /**
     * @see #darkVariantIndex(boolean, boolean)
     */
    private static int lightVariantIndex(boolean dark, boolean changeForeground) {
        return !dark || changeForeground
                ? lightConfigValue
                : LIGHT_INDEX_OFFSET + APP_DEFAULT_CONFIG_VALUE;
    }

    /**
     * The selected colors require an app restart to change,
     * so the preferences are read only once.
     */
    private static void resolveConfigValues(Context context) {
        if (darkConfigValue > 0) {
            return;
        }

        ThemeColor dark = SharedYouTubeSettings.THEME_COLOR_DARK.get();
        ThemeColor light = SharedYouTubeSettings.THEME_COLOR_LIGHT.get();

        darkConfigValue = configValue(dark, true);
        lightConfigValue = configValue(light, false);
        Logger.printDebug(() -> "Theme dark: " + darkConfigValue + " light: " + lightConfigValue);

        updateOverlay(context, dark, light);
    }

    /**
     * Resolves the color of both selected colors, and hands them to Morphe, which uses the
     * color of the app for its own dialogs and settings.
     * <p>
     * A context carries the resource variant of one theme only, so each color is resolved with
     * a configuration of the theme it belongs to and read back with
     * {@link #themeColor(boolean)}.
     */
    private static void resolveThemeColors(Context context) {
        if (themeColorsResolved || !Utils.isContextSet()) {
            return;
        }
        themeColorsResolved = true;

        // Morphe draws its own text and icons, and follows what the app uses for its foreground.
        ThemeUtils.setChangeForegroundColor(SharedYouTubeSettings.THEME_COLOR_CHANGE_FOREGROUND.get());

        // An app without a light theme has no light colors to replace.
        if (colorResourceNames(true).length > 0) {
            darkThemeColor = selectedThemeColor(context, true);
            ThemeUtils.setThemeDarkColor(darkThemeColor);
        }
        if (colorResourceNames(false).length > 0) {
            lightThemeColor = selectedThemeColor(context, false);
            ThemeUtils.setThemeLightColor(lightThemeColor);
        }
    }

    /**
     * Injection point.
     * <p>
     * Hands the system the theme it draws the splash screen of the app with.
     * <p>
     * The system draws the splash screen before the app runs, and it resolves the resources of
     * the app with the configuration of the device, so the resource variant of the selected
     * background can never be used for it. This is the way the system takes a theme instead,
     * and it uses the one it was given for every launch that follows.
     */
    public static void setSplashScreenTheme(Activity activity) {
        try {
            if (splashScreenThemeApplied || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return;
            }
            splashScreenThemeApplied = true;

            final int index = splashScreenThemeIndex(isDarkTheme());
            final int themeId = ResourceUtils.getIdentifier(
                    ResourceType.STYLE, SPLASH_THEME_NAME + index);

            Logger.printDebug(() -> "Splash screen theme: " + SPLASH_THEME_NAME + index
                    + " id: " + themeId);
            activity.getSplashScreen().setSplashScreenTheme(themeId);
        } catch (Exception ex) {
            Logger.printException(() -> "setSplashScreenTheme failure", ex);
        }
    }

    /**
     * The index of the theme that draws the splash screen of the selected color.
     * <p>
     * A color the user picked is not known while patching and has no theme of its own, so the
     * palette is used for it, which has one for every value it can be quantized to.
     */
    private static int splashScreenThemeIndex(boolean dark) {
        ThemeColor color = dark
                ? SharedYouTubeSettings.THEME_COLOR_DARK.get()
                : SharedYouTubeSettings.THEME_COLOR_LIGHT.get();

        if (!color.isCustom()) {
            return dark ? darkConfigValue : lightConfigValue;
        }

        StringSetting setting = dark
                ? SharedYouTubeSettings.THEME_COLOR_DARK_CUSTOM
                : SharedYouTubeSettings.THEME_COLOR_LIGHT_CUSTOM;

        return (dark ? DARK_INDEX_OFFSET : LIGHT_INDEX_OFFSET)
                + PALETTE_INDEX_OFFSET + get8BitColorIndex(setting, dark);
    }

    /**
     * The color selected for a theme.
     *
     * @param dark If the color of the dark theme is wanted.
     * @return The color, or zero for a theme the app does not have.
     */
    @ColorInt
    static int themeColor(boolean dark) {
        if (isPatchedTheme()) {
            return dark ? ThemeUtils.getThemeDarkColor() : ThemeUtils.getThemeLightColor();
        }

        return dark ? darkThemeColor : lightThemeColor;
    }

    /**
     * If the theme the app shows uses the color of the app itself.
     * <p>
     * No color of the app is replaced then, and patch code that recolors app components to match
     * a selected color must leave them untouched, otherwise the app looks different from
     * the unpatched app.
     */
    public static boolean isAppDefaultColor() {
        // A patched color replaces the app colors, and nothing is left untouched.
        if (isPatchedTheme()) {
            return false;
        }

        final boolean dark = isDarkTheme();

        // The config value is used instead of the setting because a Material-You color
        // falls back to the app default on Android 11 and earlier.
        if (dark) {
            return darkConfigValue == (DARK_INDEX_OFFSET + APP_DEFAULT_CONFIG_VALUE);
        }
        return lightConfigValue == (LIGHT_INDEX_OFFSET + APP_DEFAULT_CONFIG_VALUE);
    }

    /**
     * If the app shows its dark theme.
     * <p>
     * The theme of the app is resolved after the first contexts of the app attach, and the theme
     * of the last run is used until then. The night mode of the device is not used for it, the app
     * has a theme setting of its own and can show the other theme than the device does.
     *
     * @see Utils#isDarkModeEnabled()
     */
    static boolean isDarkTheme() {
        if (isPatchedTheme()) {
            // An app without a light theme has no light color that was patched in.
            return patchedThemeColorLight().isEmpty() || Utils.isDarkModeEnabled();
        }

        // An app without a light theme shows the dark one, whatever the device or the app report.
        if (lightColorResourceNames().isEmpty()) {
            return true;
        }

        return Utils.isDarkModeStatusKnown()
                ? Utils.isDarkModeEnabled()
                : SharedYouTubeSettings.THEME_LAST_USED_DARK_MODE.get();
    }

    private static int selectedThemeColor(Context context, boolean dark) {
        ThemeColor color = dark
                ? SharedYouTubeSettings.THEME_COLOR_DARK.get()
                : SharedYouTubeSettings.THEME_COLOR_LIGHT.get();

        return getThemeColor(context, dark, ((Enum<?>) color).ordinal());
    }

    /**
     * Asks for the variant of a theme, using a configuration a device never has.
     */
    private static void setVariantOf(Configuration configuration, int darkIndex, int lightIndex) {
        configuration.mcc = mobileCountryCode(darkIndex);
        configuration.mnc = mobileNetworkCode(lightIndex);
    }

    private static int mobileCountryCode(int index) {
        return UNREACHABLE_MOBILE_CODE + (index - DARK_INDEX_OFFSET);
    }

    private static int mobileNetworkCode(int index) {
        return UNREACHABLE_MOBILE_CODE + (index - LIGHT_INDEX_OFFSET);
    }

    private static int configValue(ThemeColor color, boolean dark) {
        // The two themes use indices that never overlap, so that a variant of one of them
        // is never used by the other.
        final int offset = dark ? DARK_INDEX_OFFSET : LIGHT_INDEX_OFFSET;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && color.isMaterialYou()) {
            // Material-You colors do not exist and resolving them crashes the app.
            return offset + APP_DEFAULT_CONFIG_VALUE;
        }

        if (color.isCustom() && !isCustomColorSupported()) {
            StringSetting setting = dark
                    ? SharedYouTubeSettings.THEME_COLOR_DARK_CUSTOM
                    : SharedYouTubeSettings.THEME_COLOR_LIGHT_CUSTOM;
            return offset + PALETTE_INDEX_OFFSET + get8BitColorIndex(setting, dark);
        }

        // A custom color has no resource variant of its own,
        // the color resources are replaced by the overlay instead.
        return offset + ((Enum<?>) color).ordinal() + 1;
    }

    private static int get8BitColorIndex(StringSetting colorSetting, boolean dark) {
        final int color = customColor(colorSetting);
        float[] targetLab = rgbToOklab(color);

        float[] lLevels = dark ? PALETTE_L_LEVELS_DARK : PALETTE_L_LEVELS_LIGHT;

        int bestIndex = 0;
        float minDistance = Float.MAX_VALUE;

        for (int l = 0; l < 8; l++) {
            for (int c = 0; c < 4; c++) {
                for (int h = 0; h < 8; h++) {
                    float[] paletteLab = oklchToOklab(lLevels[l], PALETTE_C_LEVELS[c], PALETTE_H_LEVELS[h]);
                    final float dist = oklabDistance(targetLab, paletteLab);
                    if (dist < minDistance) {
                        minDistance = dist;
                        bestIndex = (l << 5) | (c << 3) | h;
                    }
                }
            }
        }

        return bestIndex;
    }

    private static float[] rgbToOklab(int color) {
        double r = linearizeSrgb(((color >> 16) & 0xFF) / 255.0);
        double g = linearizeSrgb(((color >> 8) & 0xFF) / 255.0);
        double b = linearizeSrgb((color & 0xFF) / 255.0);

        final double l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
        final double m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
        final double s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b;

        final double lCubeRoot = Math.cbrt(l);
        final double mCubeRoot = Math.cbrt(m);
        final double sCubeRoot = Math.cbrt(s);

        return new float[]{
                (float) (0.2104542553 * lCubeRoot + 0.7936177850 * mCubeRoot - 0.0040720468 * sCubeRoot),
                (float) (1.9779984951 * lCubeRoot - 2.4285922050 * mCubeRoot + 0.4505937099 * sCubeRoot),
                (float) (0.0259040371 * lCubeRoot + 0.7827717662 * mCubeRoot - 0.8086757660 * sCubeRoot)
        };
    }

    private static double linearizeSrgb(double value) {
        return (value > 0.04045)
                ? Math.pow((value + 0.055) / 1.055, 2.4)
                : value / 12.92;
    }

    private static float[] oklchToOklab(float l, float c, float h) {
        final double hRad = Math.toRadians(h);
        return new float[]{
                l,
                (float) (c * Math.cos(hRad)),
                (float) (c * Math.sin(hRad))
        };
    }

    private static float oklabDistance(float[] lab1, float[] lab2) {
        final float dl = lab1[0] - lab2[0];
        final float da = lab1[1] - lab2[1];
        final float db = lab1[2] - lab2[2];
        return (float) Math.sqrt(dl * dl + da * da + db * db);
    }

    private static boolean useOverlay(boolean dark) {
        return dark ? useDarkOverlay : useLightOverlay;
    }

    /**
     * Registers, updates or removes the overlay of both themes.
     */
    private static void updateOverlay(Context context, ThemeColor dark, ThemeColor light) {
        if (!isCustomColorSupported()) {
            return;
        }

        String[] darkNames = colorResourceNames(true);
        String[] lightNames = colorResourceNames(false);

        // A theme the app does not have declares no color resource, and has nothing to overlay.
        useDarkOverlay = dark.isCustom() && darkNames.length > 0;
        useLightOverlay = light.isCustom() && lightNames.length > 0;

        updateOverlay(context, true, darkNames, SharedYouTubeSettings.THEME_COLOR_DARK_CUSTOM);
        updateOverlay(context, false, lightNames, SharedYouTubeSettings.THEME_COLOR_LIGHT_CUSTOM);
    }

    /**
     * Registers, updates or removes the overlay that gives the color resources of one theme the
     * color the user picked.
     */
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static void updateOverlay(Context context, boolean dark, String[] resourceNames,
                                      StringSetting customColorSetting) {
        try {
            if (!useOverlay(dark)) {
                ThemeColorOverlay.unregisterIfRegistered(context, dark);
                return;
            }

            // The system deletes an overlay of the app when the app is installed again, so it is
            // registered on every start and not only after the user picks another color.
            ThemeColorOverlay.register(context, dark,
                    overlayColors(resourceNames, customColorSetting));
        } catch (Exception ex) {
            // Overlays are a part of the system and a manufacturer can change how they behave.
            Logger.printException(() -> "Could not update the overlay of the app", ex);
        }
    }

    /**
     * The color the user picked, mapped to every color resource of a theme.
     */
    private static Map<String, Integer> overlayColors(String[] resourceNames,
                                                      StringSetting customColorSetting) {
        final int color = customColor(customColorSetting);
        Map<String, Integer> colors = new LinkedHashMap<>();

        for (String resourceName : resourceNames) {
            if (resourceName.isEmpty()) {
                continue;
            }

            int finalColor = color;
            final int opacityIndex = resourceName.indexOf("_opacity_");
            if (opacityIndex >= 0) {
                String alphaHex = resourceName.substring(opacityIndex + 9);
                final int alpha = Integer.parseInt(alphaHex, 16);
                finalColor = (color & 0x00FFFFFF) | (alpha << 24);
            }

            colors.put(resourceName, finalColor);
        }

        return colors;
    }

    /**
     * The color of a theme, used to show it next to the name in the app settings.
     *
     * @param dark  If the color is of the dark theme.
     * @param index Index of the theme, which is the ordinal of its enum value.
     */
    @ColorInt
    public static int getThemeColor(Context context, boolean dark, int index) {
        try {
            resolveConfigValues(context);

            ThemeColor color = (dark
                    ? ThemeColorDark.values()
                    : ThemeColorLight.values())[index];

            if (color.isCustom()) {
                return customColor(dark);
            }

            // The color of a color is the value its resource variant declares, and the
            // variant is selected the same way the app selects the color it uses.
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            final int configValue = configValue(color, dark);
            final boolean changeForeground = SharedYouTubeSettings.THEME_COLOR_CHANGE_FOREGROUND.get();

            setVariantOf(configuration,
                    dark || changeForeground
                            ? (dark ? configValue : darkConfigValue)
                            : DARK_INDEX_OFFSET + APP_DEFAULT_CONFIG_VALUE,
                    !dark || changeForeground
                            ? (!dark ? configValue : lightConfigValue)
                            : LIGHT_INDEX_OFFSET + APP_DEFAULT_CONFIG_VALUE);

            String resourceName = themeColorResourceName(dark);
            if (resourceName.isEmpty()) {
                // The app has no theme of this kind, and no color of it to show.
                return ThemeUtils.getAppBackgroundColor();
            }

            final int identifier = ResourceUtils.getIdentifier(ResourceType.COLOR, resourceName);

            Context variant = context.createConfigurationContext(configuration);
            if (isCustomColorSupported()) {
                ThemeColorOverlay.removeFrom(variant);
            }

            return variant.getColor(identifier);
        } catch (Exception ex) {
            Logger.printException(() -> "getThemeColor failure", ex);
            return ThemeUtils.getAppBackgroundColor();
        }
    }

    /**
     * Injection point.
     * <p>
     * Called with the view stub of a new content indicator before it is shown.
     */
    public static void onNewContentIndicator(ViewStub stub) {
        try {
            stub.setOnInflateListener((inflatedStub, view) -> keepIndicatorColor(view));
        } catch (Exception ex) {
            Logger.printException(() -> "onNewContentIndicator failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called with a new content indicator that the layout declares as a view of its own
     * and not as a stub.
     */
    public static void onNewContentIndicator(View indicator) {
        try {
            keepIndicatorColor(indicator);
        } catch (Exception ex) {
            Logger.printException(() -> "onNewContentIndicator failure", ex);
        }
    }

    private static void keepIndicatorColor(View view) {
        Integer color = getIndicatorColor(view.getContext());
        if (color == null) {
            return;
        }

        setIndicatorColor(view, color);

        // The app gives an indicator a color of its own after it is shown, which drops the
        // color set above. Setting it again from a posted runnable is one frame too late, so the
        // color is applied before every draw and no frame can be drawn with the app color.
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            setIndicatorColor(view, color);
            return true;
        };

        view.getViewTreeObserver().addOnPreDrawListener(listener);

        // The observer belongs to the window and not to the view, so a listener left behind
        // keeps a discarded indicator alive for as long as the window lives.
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View attached) {
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View detached) {
                // Asked for again because the observer a view has before it is attached
                // is replaced by the one of the window.
                detached.getViewTreeObserver().removeOnPreDrawListener(listener);
            }
        });
    }

    private static void setIndicatorColor(View view, int color) {
        Drawable background = view.getBackground();

        // Both indicators are a shape with a stroke of the app background color, and only the
        // fill of the shape is replaced. Mutate is needed, otherwise every user of the
        // drawable is changed as well.
        if (background instanceof GradientDrawable shape) {
            // Setting the fill invalidates the drawable and asks for another draw, and this runs
            // before every draw, so an unchanged color must not be set again.
            ColorStateList fill = shape.getColor();
            if (fill == null || fill.getDefaultColor() != color) {
                ((GradientDrawable) shape.mutate()).setColor(color);
            }
        }

        // The count is a text view, and its text must stay readable on the new color.
        if (view instanceof TextView count) {
            final int textColor = getIndicatorTextColor(view.getContext());

            if (count.getCurrentTextColor() != textColor) {
                count.setTextColor(textColor);
            }
        }
    }

    /**
     * The color of the new content indicator, or null to keep the color of the app.
     * <p>
     * A Material You color does not go with the red of the app, which is why the indicator
     * follows the same palette. Every other color keeps the app color.
     */
    @Nullable
    public static Integer getIndicatorColor(Context context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return null;
            }

            final boolean dark = Utils.isDarkModeEnabled();

            if (!usesMaterialYouAccent(dark)) {
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
     * @see ThemeColor#usesMaterialYouAccent()
     */
    private static boolean usesMaterialYouAccent(boolean dark) {
        if (isPatchedTheme()) {
            // A patch option holds a color and nothing else, so only a Material You
            // color can be told apart.
            return (dark ? patchedThemeColorDark() : patchedThemeColorLight())
                    .startsWith(MATERIAL_YOU_COLOR_PREFIX);
        }

        return (dark
                ? SharedYouTubeSettings.THEME_COLOR_DARK.get()
                : SharedYouTubeSettings.THEME_COLOR_LIGHT.get()
        ).usesMaterialYouAccent();
    }

    /**
     * The color of the text of the new content count, which must be readable
     * on {@link #getIndicatorColor(Context)}.
     */
    public static int getIndicatorTextColor(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getColor(android.R.color.system_neutral1_900);
        }

        // Never reached, an indicator color exists only with a Material You color.
        return Color.BLACK;
    }

    private static int customColor(boolean dark) {
        return customColor(dark
                ? SharedYouTubeSettings.THEME_COLOR_DARK_CUSTOM
                : SharedYouTubeSettings.THEME_COLOR_LIGHT_CUSTOM);
    }

    /**
     * The color a custom theme setting holds, or the color of its default value if the
     * user saved something that is not a color.
     * <p>
     * A theme must be opaque, otherwise the app draws over itself.
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
     * The first name is the color the app uses for the color itself.
     */
    private static String themeColorResourceName(boolean dark) {
        final String[] resourceNames = colorResourceNames(dark);
        return resourceNames.length > 0 ? resourceNames[0] : "";
    }

    private static String[] colorResourceNames(boolean dark) {
        final String resourceNames = dark ? darkColorResourceNames() : lightColorResourceNames();
        return resourceNames.isEmpty() ? new String[0] : resourceNames.split(",");
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Names of the dark theme color resources, separated by a comma.
     * The first name is the color the app uses for the theme itself.
     */
    private static String darkColorResourceNames() {
        return ""; // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Names of the light theme color resources, separated by a comma.
     * The first name is the color the app uses for the theme itself.
     */
    private static String lightColorResourceNames() {
        return ""; // Modified during patching.
    }

    /**
     * If the theme color was set with a patch option, which replaces the color resources of
     * the app for good. Nothing of this class changes a color of the app then.
     */
    private static boolean isPatchedTheme() {
        return !patchedThemeColorDark().isEmpty();
    }

    /**
     * Injection point.
     * <p>
     * The dark theme color of the patch options, or an empty string if the theme is
     * selected in the app settings instead.
     */
    private static String patchedThemeColorDark() {
        return ""; // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Empty for an app without a light theme.
     *
     * @see #patchedThemeColorDark()
     */
    private static String patchedThemeColorLight() {
        return ""; // Modified during patching.
    }
}
