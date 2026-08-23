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
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.THEME_LAST_USED_DARK_MODE;

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
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Changes the app background color while the app runs.
 * <p>
 * The background of the app is not painted by app code, it is resolved by the resource system from
 * XML: the window background is {@code ?ytBaseBackground}, which resolves to a color resource.
 * Nothing in the app can be hooked to change that, but the resource system picks a resource
 * variant using the {@link Configuration} of the context it is resolved with.
 * <p>
 * The patch writes one {@code values-mccNNNN/colors.xml} for every dark background and one
 * {@code values-mncNNNN/colors.xml} for every light background, and this class selects one of them
 * by overriding {@link Configuration#mcc} and {@link Configuration#mnc} of every context the app
 * attaches. Both qualifiers are ignored by the app itself and affect no other resource.
 * <p>
 * The config value of a background is its ordinal plus one, and {@link #APP_DEFAULT_CONFIG_VALUE}
 * is used for the unpatched colors of the app. The patch relies on the same numbering.
 * <p>
 * Only a color that was compiled in can be selected this way, so the {@code CUSTOM} background
 * uses {@link ThemeColorOverlay} instead to give the same color resources a value of its
 * own. That needs Android 14 or later.
 * <p>
 * All of this is skipped for a background color that was set with a patch option, see
 * {@link #isPatchedBackground()}.
 */
@SuppressWarnings("unused")
public class ThemeColorPatch {

    public interface Background {
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
         * If the color of this background only exists on Android 12 and later.
         */
        default boolean isMaterialYou() {
            return kind() == Kind.MATERIAL_YOU;
        }

        /**
         * If what the app draws on the background with a color of its own, such as the new
         * content indicator, follows the Material You palette.
         */
        default boolean usesMaterialYouAccent() {
            return kind() == Kind.MATERIAL_YOU || kind() == Kind.MATERIAL_YOU_ACCENT;
        }

        default boolean isCustom() {
            return kind() == Kind.CUSTOM;
        }
    }

    public enum ThemeColorDark implements Background {
        APP_DEFAULT,
        PURE_BLACK,
        MATERIAL_YOU_PURE_BLACK(Kind.MATERIAL_YOU_ACCENT),
        MATERIAL_YOU_NEUTRAL(Kind.MATERIAL_YOU),
        MATERIAL_YOU_PRIMARY(Kind.MATERIAL_YOU),
        MATERIAL_YOU_SECONDARY(Kind.MATERIAL_YOU),
        MATERIAL_YOU_TERTIARY(Kind.MATERIAL_YOU),
        CLASSIC_YOUTUBE,
        CATPPUCCIN_MOCHA,
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

    public enum ThemeColorLight implements Background {
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
     * Availability of the color of a custom background.
     */
    public static class CustomBackgroundAvailability implements Setting.Availability {
        private final EnumSetting<? extends Background> setting;

        public CustomBackgroundAvailability(EnumSetting<? extends Background> setting) {
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
     * Index of the first color of the 9 bit palette, and the index ranges of the two themes.
     * The patch uses the same numbering.
     */
    private static final int PALETTE_INDEX_OFFSET = 100;

    /**
     * The value a color channel can have in the 9 bit palette, of the dark and of the light theme.
     * The patch generates the variants with the same values, and both must stay identical.
     * <p>
     * A background sits at one end of the range, so the eight values of a channel are placed where
     * the backgrounds of that theme are instead of being spread evenly. A dark background of
     * #0F0F0F would otherwise be shown as pure black, because the nearest even value is 36 away.
     */
    private static final int[] PALETTE_LEVELS_DARK = {0, 3, 15, 38, 74, 126, 187, 255};
    private static final int[] PALETTE_LEVELS_LIGHT = {0, 68, 129, 181, 217, 240, 252, 255};
    private static final int DARK_INDEX_OFFSET = 0;
    private static final int LIGHT_INDEX_OFFSET = 700;

    private static int darkConfigValue = -1;
    private static int lightConfigValue = -1;

    /**
     * If the background of a theme is one the user picked, and the overlay of that theme must be
     * loaded into every context that shows it.
     */
    private static boolean useDarkOverlay;
    private static boolean useLightOverlay;

    /**
     * The color of the background that is selected for each theme, or zero for a theme the app
     * does not have. Resolved once, with the resource variant of the theme it belongs to.
     */
    @ColorInt
    private static int darkBackgroundColor;
    @ColorInt
    private static int lightBackgroundColor;

    private static boolean backgroundColorsResolved;

    /**
     * Name of the theme that draws the splash screen of a background. The patch generates one for
     * every background it has a color of, and uses the same numbering.
     */
    private static final String SPLASH_THEME_NAME = "morphe_splash_theme_";

    private static boolean splashScreenThemeApplied;

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

            if (isPatchedBackground()) {
                return base;
            }

            resolveConfigValues(base);

            Configuration configuration = base.getResources().getConfiguration();

            // A variant belongs to one theme only, so the background of the theme the app shows
            // is the one to ask for. A theme change recreates the activity, and the index of the
            // other theme is used from then on.
            final boolean dark = isDarkTheme();
            if (THEME_LAST_USED_DARK_MODE.get() != dark) {
                // Contexts that attach before the app resolves its theme can then select the
                // variant of the theme the app is about to show.
                THEME_LAST_USED_DARK_MODE.save(dark);
            }

            final int darkIndex = darkConfigValue;
            final int lightIndex = lightConfigValue;

            Context context;
            if (configuration.mcc == mobileCountryCode(darkIndex)
                    && configuration.mnc == mobileNetworkCode(lightIndex)) {
                // Context is created from a context that is already wrapped.
                context = base;
            } else {
                Configuration override = new Configuration(configuration);
                setVariantOf(override, darkIndex, lightIndex);

                context = base.createConfigurationContext(override);
            }

            if (isCustomBackgroundSupported()) {
                ThemeColorOverlay.applyTo(context);
            }

            resolveBackgroundColors(context);

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

        darkConfigValue = configValue(dark, true);
        lightConfigValue = configValue(light, false);
        Logger.printDebug(() -> "Theme dark: " + darkConfigValue + " light: " + lightConfigValue);

        updateOverlay(context, dark, light);
    }

    /**
     * Resolves the color of both selected backgrounds, and hands them to Morphe, which uses the
     * background of the app for its own dialogs and settings.
     * <p>
     * A context carries the resource variant of one theme only, so each color is resolved with
     * a configuration of the theme it belongs to and read back with
     * {@link #backgroundColor(boolean)}.
     */
    private static void resolveBackgroundColors(Context context) {
        if (backgroundColorsResolved || !Utils.isContextSet()) {
            return;
        }
        backgroundColorsResolved = true;

        // An app without a light theme has no light colors to replace.
        if (!darkColorResourceNames().isEmpty()) {
            darkBackgroundColor = selectedBackgroundColor(context, true);
            ThemeUtils.setThemeDarkColor(darkBackgroundColor);
        }
        if (!lightColorResourceNames().isEmpty()) {
            lightBackgroundColor = selectedBackgroundColor(context, false);
            ThemeUtils.setThemeLightColor(lightBackgroundColor);
        }
    }

    /**
     * Injection point.
     *
     * @see SplashScreenTheme
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
            SplashScreenTheme.apply(activity, themeId);
        } catch (Exception ex) {
            Logger.printException(() -> "setSplashScreenTheme failure", ex);
        }
    }

    /**
     * The index of the theme that draws the splash screen of the selected background.
     * <p>
     * A color the user picked is not known while patching and has no theme of its own, so the
     * palette is used for it, which has one for every value it can be quantized to.
     */
    private static int splashScreenThemeIndex(boolean dark) {
        Background background = dark
                ? THEME_BACKGROUND_DARK.get()
                : THEME_BACKGROUND_LIGHT.get();

        if (!background.isCustom()) {
            return dark ? darkConfigValue : lightConfigValue;
        }

        StringSetting setting = dark
                ? THEME_BACKGROUND_DARK_CUSTOM_COLOR
                : THEME_BACKGROUND_LIGHT_CUSTOM_COLOR;

        return (dark ? DARK_INDEX_OFFSET : LIGHT_INDEX_OFFSET)
                + PALETTE_INDEX_OFFSET + get9BitColorIndex(setting, dark);
    }

    /**
     * The color of the background that is selected for a theme.
     *
     * @param dark If the background of the dark theme is wanted.
     * @return The color, or zero for a theme the app does not have.
     */
    @ColorInt
    static int backgroundColor(boolean dark) {
        if (isPatchedBackground()) {
            return dark ? ThemeUtils.getThemeDarkColor() : ThemeUtils.getThemeLightColor();
        }

        return dark ? darkBackgroundColor : lightBackgroundColor;
    }

    /**
     * If the theme the app shows uses the background of the app itself.
     * <p>
     * No color of the app is replaced then, and patch code that recolors app components to match
     * a selected background must leave them untouched, otherwise the app looks different from
     * the unpatched app.
     */
    public static boolean isAppDefaultBackground() {
        // A patched background color replaces the app colors, and nothing is left untouched.
        if (isPatchedBackground()) {
            return false;
        }

        final boolean dark = isDarkTheme();

        // The config value is used instead of the setting because a Material-You background
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
        if (isPatchedBackground()) {
            // An app without a light theme has no light color that was patched in.
            return patchedBackgroundColorLight().isEmpty() || Utils.isDarkModeEnabled();
        }

        // An app without a light theme shows the dark one, whatever the device or the app report.
        if (lightColorResourceNames().isEmpty()) {
            return true;
        }

        return Utils.isDarkModeStatusKnown()
                ? Utils.isDarkModeEnabled()
                : THEME_LAST_USED_DARK_MODE.get();
    }

    private static int selectedBackgroundColor(Context context, boolean dark) {
        Background background = dark
                ? THEME_BACKGROUND_DARK.get()
                : THEME_BACKGROUND_LIGHT.get();

        return getBackgroundColor(context, dark, ((Enum<?>) background).ordinal());
    }

    /**
     * Asks for the variant of a background, using a configuration a device never has.
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
            return offset + PALETTE_INDEX_OFFSET + get9BitColorIndex(setting, dark);
        }

        // A custom background has no resource variant of its own,
        // the color resources are replaced by the overlay instead.
        return offset + ((Enum<?>) background).ordinal() + 1;
    }

    private static int get9BitColorIndex(StringSetting colorSetting, boolean dark) {
        final int color = customColor(colorSetting);
        int[] levels = dark ? PALETTE_LEVELS_DARK : PALETTE_LEVELS_LIGHT;

        final int r3 = nearestPaletteLevel(levels, (color >> 16) & 0xFF);
        final int g3 = nearestPaletteLevel(levels, (color >> 8) & 0xFF);
        final int b3 = nearestPaletteLevel(levels, color & 0xFF);

        return (r3 << 6) | (g3 << 3) | b3;
    }

    /**
     * @return The index of the palette value that is closest to a color channel.
     */
    private static int nearestPaletteLevel(int[] levels, int channel) {
        int nearest = 0;
        int smallestDistance = Integer.MAX_VALUE;

        for (int i = 0, length = levels.length; i < length; i++) {
            final int distance = Math.abs(levels[i] - channel);
            if (distance < smallestDistance) {
                smallestDistance = distance;
                nearest = i;
            }
        }

        return nearest;
    }

    private static boolean useOverlay(boolean dark) {
        return dark ? useDarkOverlay : useLightOverlay;
    }

    /**
     * Registers, updates or removes the overlay of both themes.
     */
    private static void updateOverlay(Context context, Background dark, Background light) {
        if (!isCustomBackgroundSupported()) {
            return;
        }

        // A theme the app does not have declares no color resource, and has nothing to overlay.
        useDarkOverlay = dark.isCustom() && !darkColorResourceNames().isEmpty();
        useLightOverlay = light.isCustom() && !lightColorResourceNames().isEmpty();

        updateOverlay(context, true, darkColorResourceNames(), THEME_BACKGROUND_DARK_CUSTOM_COLOR);
        updateOverlay(context, false, lightColorResourceNames(), THEME_BACKGROUND_LIGHT_CUSTOM_COLOR);
    }

    /**
     * Registers, updates or removes the overlay that gives the color resources of one theme the
     * color the user picked.
     */
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static void updateOverlay(Context context, boolean dark, String resourceNames,
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
    private static Map<String, Integer> overlayColors(String resourceNames,
                                                      StringSetting customColorSetting) {
        final int color = customColor(customColorSetting);
        Map<String, Integer> colors = new LinkedHashMap<>();

        for (String resourceName : resourceNames.split(",")) {
            if (!resourceName.isEmpty()) {
                colors.put(resourceName, color);
            }
        }

        return colors;
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
            resolveConfigValues(context);

            Background background = (dark
                    ? ThemeColorDark.values()
                    : ThemeColorLight.values())[index];

            if (background.isCustom()) {
                return customColor(dark);
            }

            // The color of a background is the value its resource variant declares, and the
            // variant is selected the same way the app selects the background it uses.
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            final int configValue = configValue(background, dark);
            setVariantOf(configuration,
                    dark ? configValue : darkConfigValue,
                    dark ? lightConfigValue : configValue);

            String resourceName = backgroundColorResourceName(dark);
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

        // The app gives an indicator a background of its own after it is shown, which drops the
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
     * @see Background#usesMaterialYouAccent()
     */
    private static boolean usesMaterialYouAccent(boolean dark) {
        if (isPatchedBackground()) {
            // A patch option holds a color and nothing else, so only a Material You
            // background can be told apart.
            return (dark ? patchedBackgroundColorDark() : patchedBackgroundColorLight())
                    .startsWith(MATERIAL_YOU_COLOR_PREFIX);
        }

        return (dark ? THEME_BACKGROUND_DARK.get() : THEME_BACKGROUND_LIGHT.get())
                .usesMaterialYouAccent();
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

    /**
     * If the background color was set with a patch option, which replaces the color resources of
     * the app for good. Nothing of this class changes a color of the app then.
     */
    private static boolean isPatchedBackground() {
        return !patchedBackgroundColorDark().isEmpty();
    }

    /**
     * Injection point.
     * <p>
     * The dark background color of the patch options, or an empty string if the background is
     * selected in the app settings instead.
     */
    private static String patchedBackgroundColorDark() {
        return ""; // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Empty for an app without a light theme.
     *
     * @see #patchedBackgroundColorDark()
     */
    private static String patchedBackgroundColorLight() {
        return ""; // Modified during patching.
    }
}
