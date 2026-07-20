/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2100
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.LongSparseArray;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

@SuppressWarnings("unused")
public final class CustomFontPatch {

    private static final LongSparseArray<Typeface> CACHE = new LongSparseArray<>();
    private static String cachedFontPath;
    private static boolean loadFailed;
    private static boolean loadFailureToastShown;

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * Called on  main thread.
     */
    public static Typeface getCustomTypeface(Resources resources, String path, int style) {
        try {
            if (!Settings.CUSTOM_FONT.get() || Settings.FORCE_SYSTEM_FONT.get()) {
                return null;
            }

            String configuredPath = Settings.CUSTOM_FONT_FILE_PATH.get();
            if (TextUtils.isEmpty(configuredPath)) {
                return null;
            }

            String normalizedPath = configuredPath.trim();
            if (normalizedPath.isEmpty()) {
                return null;
            }

            // Reset all derived state when the selected font source changes.
            if (!normalizedPath.equals(cachedFontPath)) {
                CACHE.clear();
                cachedFontPath = normalizedPath;
                loadFailed = false;
                loadFailureToastShown = false;
            }

            // Avoid repeated expensive load attempts after a known-bad file/URI.
            if (loadFailed) {
                return null;
            }

            final int weight = weightFromPath(path);
            final boolean italic = (style & Typeface.ITALIC) != 0
                    || (path != null && path.toLowerCase().contains("italic"));

            final long key = ((long) weight << 1) | (italic ? 1L : 0L);
            Typeface cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }

            Typeface typeface = build(normalizedPath, weight, italic);
            if (typeface == null) {
                loadFailed = true;
                notifyLoadFailure();
                // Keep runtime state and UI toggle in sync after an unrecoverable font load failure.
                disableCustomFont();
                return null;
            }

            CACHE.put(key, typeface);
            return typeface;
        } catch (Exception ex) {
            Logger.printException(() -> "getCustomTypeface failure", ex);
            return null;
        }
    }

    private static Typeface build(String fontPath, int weight, boolean italic) {
        String variation = "'wght' " + weight + (italic ? ", 'ital' 1" : "");

        if (fontPath.startsWith("content://")) {
            Context context = Utils.getContext();
            if (context == null) {
                return null;
            }

            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(
                    Uri.parse(fontPath), "r")) {
                if (pfd == null) {
                    return null;
                }

                return new Typeface.Builder(pfd.getFileDescriptor())
                        .setFontVariationSettings(variation)
                        .setWeight(weight)
                        .setItalic(italic)
                        .build();
            } catch (FileNotFoundException ex) {
                Logger.printException(() -> "Font file not found", ex);
            } catch (IOException ex) {
                Logger.printException(() -> "Font IOException", ex);
            }

            return null;
        }

        return new Typeface.Builder(new File(fontPath))
                .setFontVariationSettings(variation)
                .setWeight(weight)
                .setItalic(italic)
                .build();
    }

    private static int weightFromPath(String path) {
        if (path == null) {
            return 400;
        }

        String lowerCasePath = path.toLowerCase();
        if (lowerCasePath.contains("black")) {
            return 900;
        }
        if (lowerCasePath.contains("extrabold") || lowerCasePath.contains("extra_bold")
                || lowerCasePath.contains("extra-bold")) {
            return 800;
        }
        if (lowerCasePath.contains("semibold") || lowerCasePath.contains("semi_bold")
                || lowerCasePath.contains("semi-bold")
                || lowerCasePath.contains("demibold") || lowerCasePath.contains("demi_bold")) {
            return 600;
        }
        if (lowerCasePath.contains("bold")) {
            return 700;
        }
        if (lowerCasePath.contains("medium")) {
            return 500;
        }
        if (lowerCasePath.contains("light")) {
            return 300;
        }
        if (lowerCasePath.contains("thin")) {
            return 100;
        }

        return 400;
    }

    private static void notifyLoadFailure() {
        if (loadFailureToastShown) {
            return;
        }

        loadFailureToastShown = true;
        if (ResourceUtils.getStringIdentifier("morphe_custom_font_failed_to_load") != 0) {
            Utils.showToastLong(str("morphe_custom_font_failed_to_load"));
        } else {
            Utils.showToastLong("Failed to load custom font. Pick a different file.");
        }
    }

    private static void disableCustomFont() {
        // Suppress shared preference listener side effects for this programmatic write.
        AbstractPreferenceFragment.settingImportInProgress = true;
        try {
            Settings.CUSTOM_FONT.save(false);
        } catch (Exception ignored) {
            // Ignore any preference write failures and keep fallback behavior.
        } finally {
            AbstractPreferenceFragment.settingImportInProgress = false;
        }
    }
}