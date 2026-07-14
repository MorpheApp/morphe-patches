/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2029
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Needle parsing and matching helpers called from the bytecode-injected filter.
 */
@SuppressWarnings("unused")
public abstract class BaseSettingsMenuFilter {

    private final BooleanSetting enabledSetting;
    private final StringSetting entriesSetting;

    private volatile String cachedRaw;
    @Nullable private volatile String[] cachedNeedles;

    protected BaseSettingsMenuFilter(BooleanSetting enabledSetting,
                                     StringSetting entriesSetting) {
        this.enabledSetting = enabledSetting;
        this.entriesSetting = entriesSetting;
    }

    /**
     * Null short-circuits the filter; reserved labels are dropped so the Morphe entry point stays reachable.
     */
    @Nullable
    protected final String[] needles() {
        if (!enabledSetting.get()) return null;

        String raw = entriesSetting.get();
        if (raw.isBlank()) return null;

        if (raw.equals(cachedRaw)) return cachedNeedles;

        Set<String> reserved = reservedNeedles();
        List<String> result = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim().toLowerCase();
            if (trimmed.isEmpty()) continue;
            if (reserved.contains(trimmed)) {
                Logger.printDebug(() -> "SettingsMenuFilter ignoring reserved needle: " + trimmed);
                continue;
            }
            result.add(trimmed);
        }
        String[] parsed = result.isEmpty() ? null : result.toArray(new String[0]);
        cachedNeedles = parsed;
        cachedRaw = raw;
        return parsed;
    }

    /**
     * Resolved per call so the host app locale wins over the Morphe language override.
     */
    private static Set<String> reservedNeedles() {
        Set<String> result = new HashSet<>();
        addLoweredIfPresent(result, ResourceUtils.getString("morphe_settings_title"));
        addLoweredIfPresent(result, ResourceUtils.getString("morphe_settings_submenu_title"));
        return result;
    }

    private static void addLoweredIfPresent(Set<String> target, @Nullable String value) {
        if (value != null) target.add(value.toLowerCase());
    }

    /**
     * Exact match keeps a needle from over-hiding unrelated titles that share substrings with it.
     */
    public static boolean equalsAny(@Nullable CharSequence text, String[] needles) {
        if (text == null) return false;
        String haystack = text.toString().toLowerCase();
        for (String needle : needles) {
            if (haystack.equals(needle)) return true;
        }
        return false;
    }

    public static void logHidden(CharSequence title) {
        Logger.printDebug(() -> "SettingsMenuFilter hidden: " + title);
    }
}
