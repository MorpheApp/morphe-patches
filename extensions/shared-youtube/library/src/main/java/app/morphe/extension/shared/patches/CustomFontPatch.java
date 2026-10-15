/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3673
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

@SuppressWarnings({"unused", "JavaReflectionMemberAccess"})
public final class CustomFontPatch {

    public enum FontType {
        DEFAULT,
        SYSTEM,
        CUSTOM
    }

    private static final String FONT_FILE_NAME = "morphe_custom_font";
    private static final String TEMP_FONT_FILE_NAME = "morphe_custom_font.tmp";

    private static final int MIN_WEIGHT = 1;
    private static final int MAX_WEIGHT = 1000;
    private static final int THIN_WEIGHT = 100;
    private static final int LIGHT_WEIGHT = 300;
    private static final int DEFAULT_WEIGHT = 400;
    private static final int MEDIUM_WEIGHT = 500;
    private static final int SEMI_BOLD_WEIGHT = 600;
    private static final int BOLD_WEIGHT = 700;
    private static final int EXTRA_BOLD_WEIGHT = 800;
    private static final int BLACK_WEIGHT = 900;

    /**
     * Sans serif system font families and their weight.
     * Serif, monospace, emoji and other families keep their look.
     */
    private static final String[] SYSTEM_FAMILY_NAMES = {
            "sans-serif",
            "sans-serif-thin",
            "sans-serif-light",
            "sans-serif-medium",
            "sans-serif-black",
            "sans-serif-condensed",
            "sans-serif-condensed-light",
            "sans-serif-condensed-medium",
            "roboto",
    };
    private static final int[] SYSTEM_FAMILY_WEIGHTS = {
            DEFAULT_WEIGHT,
            THIN_WEIGHT,
            LIGHT_WEIGHT,
            MEDIUM_WEIGHT,
            BLACK_WEIGHT,
            DEFAULT_WEIGHT,
            LIGHT_WEIGHT,
            MEDIUM_WEIGHT,
            DEFAULT_WEIGHT,
    };

    /**
     * Font loaded from the font file during app startup,
     * or null if the setting is off or the font could not be loaded.
     */
    @Nullable
    private static volatile Typeface baseTypeface;

    private static final Map<Integer, Typeface> typefaceCache = new ConcurrentHashMap<>();

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private static File getFontFile(Context context) {
        return new File(context.getFilesDir(), FONT_FILE_NAME);
    }

    /**
     * @return If a font file was picked and is still present.
     */
    public static boolean isFontFileSet(Context context) {
        return !SharedYouTubeSettings.CUSTOM_FONT_FILE.get().isEmpty() && getFontFile(context).isFile();
    }

    @Nullable
    private static Typeface loadTypeface(File file) {
        try {
            if (!file.isFile()) {
                return null;
            }
            // Returns null if the file is not a font.
            return new Typeface.Builder(file).build();
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not load font file: " + file, ex);
            return null;
        }
    }

    /**
     * Copies a picked font file to the app files, so it can be used after the
     * document permission is gone.
     *
     * @return If the file is a font that can be used.
     */
    public static boolean importFontFile(Context context, Uri uri) {
        File tempFile = new File(context.getFilesDir(), TEMP_FONT_FILE_NAME);
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tempFile)) {
                if (in == null) {
                    return false;
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            if (loadTypeface(tempFile) == null) {
                Logger.printDebug(() -> "Picked file is not a font: " + uri);
                return false;
            }

            File fontFile = getFontFile(context);
            if (!tempFile.renameTo(fontFile)) {
                Logger.printException(() -> "Could not save font file: " + fontFile);
                return false;
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "importFontFile failure", ex);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
    }

    /**
     * Injection point.
     * Called during app startup, before any view is created.
     */
    public static void applyCustomFont(Context context) {
        try {
            if (!Utils.isContextSet()) {
                // Settings are read using the context.
                Utils.setContext(context);
            }

            if (!SharedYouTubeSettings.CUSTOM_FONT_FILE.get().isEmpty() && !getFontFile(context).isFile()) {
                Logger.printInfo(() -> "Custom font file not found, clearing the file setting");
                SharedYouTubeSettings.CUSTOM_FONT_FILE.resetToDefault();
            }

            if (SharedYouTubeSettings.CUSTOM_FONT_TYPE.get() != FontType.CUSTOM) {
                return;
            }

            Typeface typeface = loadTypeface(getFontFile(context));
            if (typeface == null) {
                Logger.printInfo(() -> "Custom font could not be loaded, turning it off");
                disableCustomFont();
                return;
            }

            baseTypeface = typeface;
            replaceSystemTypefaces();
        } catch (Exception ex) {
            Logger.printException(() -> "applyCustomFont failure", ex);
        }
    }

    private static void disableCustomFont() {
        // Do not show the restart dialog for this change.
        AbstractPreferenceFragment.settingImportInProgress = true;
        try {
            SharedYouTubeSettings.CUSTOM_FONT_TYPE.save(FontType.DEFAULT);
        } catch (Exception ex) {
            Logger.printException(() -> "disableCustomFont failure", ex);
        } finally {
            AbstractPreferenceFragment.settingImportInProgress = false;
        }

        Utils.showToastLong(str("morphe_custom_font_failed_to_load"));
    }

    /**
     * Most text uses the platform default typefaces, and not the fonts of the app.
     * The default typefaces are not public API, so each one is replaced on its own,
     * and any that cannot be replaced keeps the system font.
     */
    private static void replaceSystemTypefaces() {
        Typeface regular = getTypeface(DEFAULT_WEIGHT, false);
        Typeface bold = getTypeface(BOLD_WEIGHT, false);

        replaceSystemFontMap();
        replaceDefaultStyles();
        replaceStaticField("DEFAULT", regular);
        replaceStaticField("DEFAULT_BOLD", bold);
        replaceStaticField("SANS_SERIF", regular);
    }

    @SuppressWarnings("unchecked")
    private static void replaceSystemFontMap() {
        try {
            Field field = Typeface.class.getDeclaredField("sSystemFontMap");
            field.setAccessible(true);
            Map<String, Typeface> map = (Map<String, Typeface>) field.get(null);
            if (map == null) {
                Logger.printInfo(() -> "System font map is null");
                return;
            }

            Map<String, Typeface> replacements = new HashMap<>();
            for (int i = 0; i < SYSTEM_FAMILY_NAMES.length; i++) {
                replacements.put(SYSTEM_FAMILY_NAMES[i], getTypeface(SYSTEM_FAMILY_WEIGHTS[i], false));
            }

            try {
                map.putAll(replacements);
            } catch (UnsupportedOperationException ex) {
                // Some Android versions use an unmodifiable map.
                Map<String, Typeface> copy = new HashMap<>(map);
                copy.putAll(replacements);
                field.set(null, copy);
            }
            Logger.printDebug(() -> "Replaced system font map families");
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not replace system font map", ex);
        }
    }

    private static void replaceDefaultStyles() {
        try {
            Field field = Typeface.class.getDeclaredField("sDefaults");
            field.setAccessible(true);
            Typeface[] defaults = (Typeface[]) field.get(null);
            if (defaults == null) {
                Logger.printInfo(() -> "Default typefaces are null");
                return;
            }

            // Index is the typeface style: normal, bold, italic, bold italic.
            for (int style = 0; style < defaults.length; style++) {
                final boolean isBold = (style & Typeface.BOLD) != 0;
                final boolean isItalic = (style & Typeface.ITALIC) != 0;
                defaults[style] = getTypeface(isBold ? BOLD_WEIGHT : DEFAULT_WEIGHT, isItalic);
            }
            Logger.printDebug(() -> "Replaced default typefaces");
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not replace default typefaces", ex);
        }
    }

    private static void replaceStaticField(String name, Typeface typeface) {
        try {
            Field field = Typeface.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, typeface);
            Logger.printDebug(() -> "Replaced typeface field: " + name);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not replace typeface field: " + name, ex);
        }
    }

    private static boolean isSystemFont() {
        return SharedYouTubeSettings.CUSTOM_FONT_TYPE.get() == FontType.SYSTEM;
    }

    /**
     * @return If the app fonts are replaced with the custom font or the system font.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isFontReplaced() {
        return baseTypeface != null || isSystemFont();
    }

    /**
     * @return The custom font or the system font with the weight and italic,
     *         or null if the app fonts are not replaced.
     */
    @Nullable
    private static Typeface getTypeface(int weight, boolean italic) {
        if (weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
            weight = DEFAULT_WEIGHT;
        }

        Typeface base = baseTypeface;
        if (base != null) {
            return createCustomTypeface(base, weight, italic);
        }
        if (isSystemFont()) {
            return createSystemTypeface(weight, italic);
        }
        return null;
    }

    private static Typeface createCustomTypeface(Typeface base, int weight, boolean italic) {
        final int key = (weight << 1) | (italic ? 1 : 0);
        Typeface cached = typefaceCache.get(key);
        if (cached != null) {
            return cached;
        }

        Typeface typeface;
        if (Utils.isSDKAbove(28)) {
            typeface = Typeface.create(base, weight, italic);
        } else {
            // Before Android 9 only bold and italic can be selected.
            int style = weight >= SEMI_BOLD_WEIGHT ? Typeface.BOLD : Typeface.NORMAL;
            if (italic) {
                style |= Typeface.ITALIC;
            }
            typeface = Typeface.create(base, style);
        }

        typefaceCache.put(key, typeface);
        return typeface;
    }

    private static Typeface createSystemTypeface(int weight, boolean italic) {
        if (Utils.isSDKAbove(28)) {
            return Typeface.create(Typeface.DEFAULT, weight, italic);
        }

        // Before Android 9, a weight can only be selected using the font family name.
        final String family;
        int style = italic ? Typeface.ITALIC : Typeface.NORMAL;
        if (weight >= BLACK_WEIGHT) {
            family = "sans-serif-black";
        } else if (weight >= SEMI_BOLD_WEIGHT) {
            family = "sans-serif";
            style |= Typeface.BOLD;
        } else if (weight >= MEDIUM_WEIGHT) {
            family = "sans-serif-medium";
        } else if (weight >= DEFAULT_WEIGHT) {
            family = "sans-serif";
        } else if (weight >= LIGHT_WEIGHT) {
            family = "sans-serif-light";
        } else {
            family = "sans-serif-thin";
        }
        return Typeface.create(family, style);
    }

    @Nullable
    private static Typeface getTypeface(int weight, int style, boolean italic) {
        // A negative style means the caller did not ask for one, which must not read as bold italic.
        if (style < 0) {
            style = Typeface.NORMAL;
        }
        if ((style & Typeface.BOLD) != 0) {
            weight = Math.max(weight, BOLD_WEIGHT);
        }
        italic |= (style & Typeface.ITALIC) != 0;
        return getTypeface(weight, italic);
    }

    /**
     * Brand fonts bundled in the app, and Roboto. Monospace, icon and emoji fonts keep their look.
     */
    private static boolean isReplacedFontName(String name) {
        if (name.contains("mono") || name.contains("icon")
                || name.contains("emoji") || name.contains("symbol")) {
            return false;
        }
        return name.startsWith("youtube_sans")
                || name.startsWith("ytsans")
                || name.startsWith("youtubemarquee")
                || name.startsWith("google_sans")
                || name.startsWith("gm3_ref_typeface")
                || name.startsWith("yt_ref_typography")
                || name.startsWith("roboto");
    }

    private static int weightFromName(String name) {
        if (name.contains("extrabold")) return EXTRA_BOLD_WEIGHT;
        if (name.contains("semibold")) return SEMI_BOLD_WEIGHT;
        if (name.contains("black") || name.contains("heavy")) return BLACK_WEIGHT;
        if (name.contains("bold")) return BOLD_WEIGHT;
        if (name.contains("medium")) return MEDIUM_WEIGHT;
        if (name.contains("light")) return LIGHT_WEIGHT;
        if (name.contains("thin")) return THIN_WEIGHT;
        return DEFAULT_WEIGHT;
    }

    @Nullable
    private static Typeface getTypefaceFromName(String name, int style) {
        name = name.toLowerCase(Locale.ENGLISH);
        if (!isReplacedFontName(name)) {
            return null;
        }
        return getTypeface(weightFromName(name), style, name.contains("italic"));
    }

    /**
     * Injection point.
     *
     * @param original Typeface from the font provider.
     * @return The custom font or the system font, or the original typeface if it should not be replaced.
     */
    @Nullable
    public static Typeface getCustomTypeface(@Nullable Typeface original, int weight, int style,
                                             @Nullable String fontSettings) {
        try {
            if (!isFontReplaced()) {
                return original;
            }

            // Other fonts are chosen by the user, such as the Shorts text styles.
            if (fontSettings != null && !fontSettings.isEmpty() && !fontSettings.contains("YouTube Sans")) {
                return original;
            }

            Typeface typeface = getTypeface(weight, style, false);
            return typeface != null ? typeface : original;
        } catch (Exception ex) {
            Logger.printException(() -> "getCustomTypeface failure", ex);
            return original;
        }
    }

    /**
     * Injection point.
     */
    @Nullable
    public static Typeface getCustomTypeface(Context context, int fontResourceId, int style) {
        try {
            if (!isFontReplaced()) {
                return null;
            }

            String name = context.getResources().getResourceEntryName(fontResourceId);
            return getTypefaceFromName(name, style);
        } catch (Resources.NotFoundException ex) {
            Logger.printDebug(() -> "Font resource not found: 0x" + Integer.toHexString(fontResourceId));
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> "getCustomTypeface failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     */
    @Nullable
    public static Typeface getCustomTypeface(Enum<?> font, int style) {
        try {
            if (!isFontReplaced()) {
                return null;
            }

            return getTypefaceFromName(font.name(), style);
        } catch (Exception ex) {
            Logger.printException(() -> "getCustomTypeface failure", ex);
            return null;
        }
    }
}
