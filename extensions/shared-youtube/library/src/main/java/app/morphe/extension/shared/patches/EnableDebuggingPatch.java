/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2638
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.FeatureFlagsBisect;

import static app.morphe.extension.shared.StringRef.str;

@SuppressWarnings("unused")
public final class EnableDebuggingPatch {

    /**
     * Only log if debugging is enabled on startup.
     * This prevents enabling debugging
     * while the app is running then failing to restart
     * resulting in an incomplete log.
     */
    private static final boolean LOG_FEATURE_FLAGS = BaseSettings.DEBUG.get();

    private static final Map<Long, Boolean> OVERRIDDEN_FEATURE_FLAGS = loadOverriddenFlags();

    private static final ConcurrentMap<Long, Boolean> featureFlags = LOG_FEATURE_FLAGS
            ? new ConcurrentHashMap<>(3000, 0.5f, 1)
            : null;

    static {
        FeatureFlagsBisect.handleAppStartup();
    }

    private static Map<Long, Boolean> loadOverriddenFlags() {
        if (!LOG_FEATURE_FLAGS) return Collections.emptyMap();

        List<Long> disabled = parseFlagList(SharedYouTubeSettings.DISABLED_FEATURE_FLAGS.get());
        List<Long> forced = parseFlagList(SharedYouTubeSettings.FORCED_FEATURE_FLAGS.get());

        logFlags("Disabled feature flags:", disabled);
        logFlags("Forced feature flags:", forced);

        Map<Long, Boolean> overrides = new HashMap<>(2 * disabled.size() + forced.size());
        for (Long flag : disabled) overrides.put(flag, FALSE);
        for (Long flag : forced) overrides.put(flag, TRUE);

        return Collections.unmodifiableMap(overrides);
    }

    private static void logFlags(String header, Collection<Long> flags) {
        if (flags.isEmpty()) return;

        StringBuilder sb = new StringBuilder(header.length() + 12 * flags.size());
        sb.append('\n');
        for (Long flag : flags) {
            sb.append("  ").append(flag).append('\n');
        }
        Logger.printDebug(sb::toString);
    }

    /**
     * Injection point.
     */
    public static boolean isBooleanFeatureFlagEnabled(boolean value, long flag) {
        if (LOG_FEATURE_FLAGS) {
            Long flagObj = flag;
            Boolean override = OVERRIDDEN_FEATURE_FLAGS.get(flagObj);
            if (override != null) {
                return override;
            }
            // Always add flag but only log if flag is enabled.
            if (featureFlags.putIfAbsent(flagObj, value) == null && value) {
                Logger.printDebug(() -> "boolean feature is enabled: " + flag);
            }
        }

        return value;
    }

    /**
     * Injection point.
     */
    public static double isDoubleFeatureFlagEnabled(double value, long flag, double defaultValue) {
        if (LOG_FEATURE_FLAGS && defaultValue != value) {
            Long flagObj = flag;
            if (FALSE.equals(OVERRIDDEN_FEATURE_FLAGS.get(flagObj))) {
                return defaultValue;
            }
            if (featureFlags.putIfAbsent(flagObj, TRUE) == null) {
                // Align the log outputs to make post-processing easier.
                Logger.printDebug(() -> " double feature is enabled: " + flag
                        + " value: " + value + (defaultValue == 0 ? "" : " default: " + defaultValue));
            }
        }

        return value;
    }

    /**
     * Injection point.
     */
    public static long isLongFeatureFlagEnabled(long value, long flag, long defaultValue) {
        if (LOG_FEATURE_FLAGS && defaultValue != value) {
            Long flagObj = flag;
            if (FALSE.equals(OVERRIDDEN_FEATURE_FLAGS.get(flagObj))) {
                return defaultValue;
            }
            if (featureFlags.putIfAbsent(flagObj, TRUE) == null) {
                Logger.printDebug(() -> "   long feature is enabled: " + flag
                        + " value: " + value + (defaultValue == 0 ? "" : " default: " + defaultValue));
            }
        }

        return value;
    }

    /**
     * Injection point.
     */
    public static String isStringFeatureFlagEnabled(String value, long flag, String defaultValue) {
        if (LOG_FEATURE_FLAGS && !defaultValue.equals(value)) {
            Long flagObj = flag;
            if (FALSE.equals(OVERRIDDEN_FEATURE_FLAGS.get(flagObj))) {
                return defaultValue;
            }
            if (featureFlags.putIfAbsent(flagObj, TRUE) == null) {
                Logger.printDebug(() -> " string feature is enabled: " + flag
                        + " value: " + value + (defaultValue.isEmpty() ? "" : " default: " + defaultValue));
            }
        }

        return value;
    }

    /**
     * Get all logged feature flags.
     * @return Map of all known flags and their current state
     */
    public static Map<Long, Boolean> getAllLoggedFlags() {
        if (LOG_FEATURE_FLAGS) {
            return Collections.unmodifiableMap(featureFlags);
        }

        // In practice this is never reached because at least one flag is always encountered.
        return Collections.emptyMap();
    }

    /**
     * Serializes flags into the format used by the settings.
     * @param flags Flag IDs to serialize
     * @return String containing newline-separated flag IDs
     */
    public static String serializeFlags(Collection<Long> flags) {
        return serializeFlags(flags, '\n');
    }

    /**
     * @param flags     Flag IDs to serialize
     * @param separator Separator to put between the flag IDs
     * @return String containing the separated flag IDs
     */
    public static String serializeFlags(Collection<Long> flags, char separator) {
        StringBuilder builder = new StringBuilder(10 * flags.size());
        for (Long flag : flags) {
            //noinspection SizeReplaceableByIsEmpty
            if (builder.length() != 0) {
                builder.append(separator);
            }
            builder.append(flag);
        }

        return builder.toString();
    }

    /**
     * Public method for parsing flags.
     * @param flags String containing flag IDs separated by commas or whitespace
     * @return Set of parsed flag IDs
     */
    public static Set<Long> parseFlags(String flags) {
        return new HashSet<>(parseFlagList(flags));
    }

    /**
     * @param flags String containing flag IDs separated by commas or whitespace
     * @return Parsed flag IDs, in the order they appear
     */
    public static List<Long> parseFlagList(String flags) {
        if (flags.isBlank()) {
            return Collections.emptyList();
        }

        String[] split = flags.split("[,\\s]+");
        List<Long> parsedFlags = new ArrayList<>(split.length);
        for (String flag : split) {
            String trimmedFlag = flag.trim();
            if (trimmedFlag.isEmpty()) continue; // Skip empty entries.
            try {
                parsedFlags.add(Long.parseLong(trimmedFlag));
            } catch (NumberFormatException e) {
                Logger.printException(() -> "Invalid flag ID: " + flag);
            }
        }

        return parsedFlags;
    }
}
