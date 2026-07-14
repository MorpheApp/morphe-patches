/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2029
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Needle parsing and matching helpers called from the bytecode-injected filter.
 */
@SuppressWarnings("unused")
public abstract class BaseSettingsMenuFilter {

    private final BooleanSetting enabledSetting;
    private final StringSetting entriesSetting;

    protected BaseSettingsMenuFilter(BooleanSetting enabledSetting,
                                     StringSetting entriesSetting) {
        this.enabledSetting = enabledSetting;
        this.entriesSetting = entriesSetting;
    }

    /**
     * Null return short-circuits the injected filter. Any needle that resolves to the label of
     * the Morphe entry point (localised via {@code str()}) is silently dropped, so a user cannot
     * accidentally hide their own way back into Morphe settings.
     */
    @Nullable
    protected final String[] needles() {
        if (!enabledSetting.get()) return null;

        String raw = entriesSetting.get();
        if (raw.isBlank()) return null;

        Set<String> reserved = reservedNeedles();
        List<String> result = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) continue;
            if (reserved.contains(trimmed)) {
                Logger.printDebug(() -> "SettingsMenuFilter ignoring reserved needle: " + trimmed);
                continue;
            }
            result.add(trimmed);
        }
        return result.isEmpty() ? null : result.toArray(new String[0]);
    }

    private static volatile Set<String> reservedNeedles;

    private static Set<String> reservedNeedles() {
        Set<String> cached = reservedNeedles;
        if (cached == null) {
            cached = new HashSet<>(Arrays.asList(
                    str("morphe_settings_title").toLowerCase(Locale.ROOT),
                    str("morphe_settings_submenu_title").toLowerCase(Locale.ROOT)
            ));
            reservedNeedles = cached;
        }
        return cached;
    }

    /** Case-insensitive exact match; user needs to type each preference name in full. */
    public static boolean equalsAny(@Nullable CharSequence text, String[] needles) {
        if (text == null) return false;
        String haystack = text.toString().toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (haystack.equals(needle)) return true;
        }
        return false;
    }

    public static void logHidden(CharSequence title) {
        Logger.printDebug(() -> "SettingsMenuFilter hidden: " + title);
    }
}
