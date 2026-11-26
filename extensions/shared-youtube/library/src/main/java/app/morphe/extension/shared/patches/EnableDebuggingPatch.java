/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2638
 * https://github.com/MorpheApp/morphe-patches/pull/4416
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedSettings;
import app.morphe.extension.shared.settings.preference.FeatureFlags;
import app.morphe.extension.shared.settings.preference.FeatureFlagsBisect;

@SuppressWarnings("unused")
public final class EnableDebuggingPatch {

    /**
     * Only log if debugging is enabled on startup.
     * This prevents enabling debugging
     * while the app is running then failing to restart
     * resulting in an incomplete log.
     */
    private static final boolean LOG_FEATURE_FLAGS = Logger.isDebugEnabled();

    /**
     * Flags to hide from the UI.
     */
    private static final Set<Long> FLAGS_TO_IGNORE = Set.of(
            45386834L, // 'You' tab settings icon.
            45532100L  // Cairo flag. Turning this off with all other flags causes the settings menu to be a mix of old/new.
    );

    public static final FeatureFlags<Long> FEATURE_FLAGS = new FeatureFlags<>() {
        @Override
        public Map<Long, Boolean> getLoggedFlags() {
            if (LOG_FEATURE_FLAGS) {
                return Collections.unmodifiableMap(featureFlags);
            }

            return Collections.emptyMap();
        }

        @Override
        protected Long parseFlag(String flag) {
            return Long.parseLong(flag);
        }

        @Override
        protected boolean hasNumericFlags() {
            return true;
        }

        @Override
        protected Set<Long> getFlagsToIgnore() {
            return FLAGS_TO_IGNORE;
        }
    };

    private static final Map<Long, Boolean> OVERRIDDEN_FEATURE_FLAGS = loadOverriddenFlags();

    private static final ConcurrentMap<Long, Boolean> featureFlags = LOG_FEATURE_FLAGS
            ? new ConcurrentHashMap<>(3000, 0.5f, 1)
            : null;

    static {
        FeatureFlagsBisect.handleAppStartup();

        if (LOG_FEATURE_FLAGS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Utils.runOnBackgroundThread(EnableDebuggingPatch::logProcessExits);
        }
    }

    /**
     * Logs why the previous processes ended, such as a crash, an ANR or a kill in the background,
     * which the app cannot log itself.
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    private static void logProcessExits() {
        try {
            Context context = Utils.getContext();
            String packageName = context.getPackageName();
            StringBuilder builder = new StringBuilder("Last process exits:");
            int count = 0;
            for (ApplicationExitInfo exit : context.getSystemService(ActivityManager.class)
                    .getHistoricalProcessExitReasons(packageName, 0, 20)) {
                // The WebView processes of the app are listed too.
                if (!exit.getProcessName().startsWith(packageName)) continue;
                if (count++ >= 5) break;

                builder.append('\n').append(exit);
                // The system keeps the thread dump of an ANR. The main thread shows what blocked the app.
                if (exit.getReason() == ApplicationExitInfo.REASON_ANR) {
                    appendAnrMainThread(builder, exit);
                }
            }
            Logger.printDebug(builder::toString);
        } catch (Exception ex) {
            Logger.printException(() -> "logProcessExits failure", ex);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private static void appendAnrMainThread(StringBuilder builder, ApplicationExitInfo exit) {
        try (InputStream trace = exit.getTraceInputStream()) {
            if (trace == null) return;

            BufferedReader reader = new BufferedReader(new InputStreamReader(trace, StandardCharsets.UTF_8));
            boolean mainThread = false;
            int lines = 0;
            for (String line; (line = reader.readLine()) != null && lines < 60; ) {
                if (line.startsWith("\"main\"")) mainThread = true;
                else if (mainThread && line.isEmpty()) break;
                if (mainThread) {
                    builder.append("\n  ").append(line);
                    lines++;
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the ANR trace", ex);
        }
    }

    private static Map<Long, Boolean> loadOverriddenFlags() {
        if (!LOG_FEATURE_FLAGS) return Collections.emptyMap();

        List<Long> disabled = FEATURE_FLAGS.parseFlagList(SharedSettings.DISABLED_FEATURE_FLAGS.get());
        List<Long> forced = FEATURE_FLAGS.parseFlagList(SharedSettings.FORCED_FEATURE_FLAGS.get());

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
        sb.append(header).append('\n');
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
                Logger.printVerbose(() -> "boolean feature is enabled: " + flag);
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
                Logger.printVerbose(() -> " double feature is enabled: " + flag
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
                Logger.printVerbose(() -> "   long feature is enabled: " + flag
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
                Logger.printVerbose(() -> " string feature is enabled: " + flag
                        + " value: " + value + (defaultValue.isEmpty() ? "" : " default: " + defaultValue));
            }
        }

        return value;
    }

    /**
     * Injection point.
     */
    public static long isBootstrapFeatureFlagEnabled(long value, int flag) {
        if (LOG_FEATURE_FLAGS) {
            Long flagObj = (long) flag;
            Boolean override = OVERRIDDEN_FEATURE_FLAGS.get(flagObj);
            if (FALSE.equals(override)) {
                return 0;
            }

            // Bootstrap store flag ids are packed bit field locations, and never overlap server flag ids.
            // Bits 16-29 of the id are the size of the flag value in bits.
            if (((flag >>> 16) & 0x3FFF) == 1) {
                if (override != null) {
                    return 1;
                }
                if (featureFlags.putIfAbsent(flagObj, value != 0) == null && value != 0) {
                    Logger.printVerbose(() -> "boolean feature is enabled: " + flag);
                }
            } else if (value != 0 && featureFlags.putIfAbsent(flagObj, TRUE) == null) {
                Logger.printVerbose(() -> "   long feature is enabled: " + flag + " value: " + value);
            }
        }

        return value;
    }
}
