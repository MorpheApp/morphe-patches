/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5201
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedSettings;
import app.morphe.extension.shared.settings.preference.FeatureFlags;
import app.morphe.extension.shared.settings.preference.FeatureFlagsBisect;

/**
 * Logs the experiments the app reads, and blocks or forces them.
 * Experiments are identified by name.
 */
@SuppressWarnings("unused")
public final class EnableDebuggingPatch {

    /**
     * Variants that start with this are the default app behavior.
     */
    private static final String CONTROL_VARIANT_PREFIX = "control";

    public static final FeatureFlags<String> FEATURE_FLAGS = new FeatureFlags<>() {
        @Override
        public Map<String, Boolean> getLoggedFlags() {
            if (Utils.isContextSet() && State.LOG_FEATURE_FLAGS) {
                return Collections.unmodifiableMap(State.experiments);
            }

            return Collections.emptyMap();
        }

        @Override
        protected String parseFlag(String flag) {
            return flag;
        }

        @Override
        protected boolean hasNumericFlags() {
            return false;
        }

        @Nullable
        @Override
        protected String getFlagSummary(String flag) {
            return State.LOG_FEATURE_FLAGS ? State.variants.get(flag) : null;
        }
    };

    /**
     * Experiments can be read before the app context is set, and the settings cannot be read
     * until then. This class is loaded only after the context is set.
     */
    private static final class State {
        /**
         * Only log if debugging is enabled on startup.
         * This prevents enabling debugging while the app is running
         * then failing to restart resulting in an incomplete log.
         */
        private static final boolean LOG_FEATURE_FLAGS = Logger.isDebugEnabled();

        private static final Map<String, Boolean> OVERRIDDEN_EXPERIMENTS = loadOverriddenExperiments();

        /**
         * Experiment name to if the experiment is active.
         */
        private static final ConcurrentMap<String, Boolean> experiments = LOG_FEATURE_FLAGS
                ? new ConcurrentHashMap<>(1000, 0.5f, 1)
                : null;

        /**
         * Experiment name to the variant the app read, for experiments read as a variant.
         */
        private static final ConcurrentMap<String, String> variants = LOG_FEATURE_FLAGS
                ? new ConcurrentHashMap<>(500, 0.5f, 1)
                : null;

        static {
            FeatureFlagsBisect.handleAppStartup();
        }

        private static Map<String, Boolean> loadOverriddenExperiments() {
            if (!LOG_FEATURE_FLAGS) return Collections.emptyMap();

            List<String> disabled = FEATURE_FLAGS.parseFlagList(SharedSettings.DISABLED_FEATURE_FLAGS.get());
            List<String> forced = FEATURE_FLAGS.parseFlagList(SharedSettings.FORCED_FEATURE_FLAGS.get());

            if (!disabled.isEmpty()) {
                Logger.printDebug(() -> "Blocked experiments: " + disabled);
            }
            if (!forced.isEmpty()) {
                Logger.printDebug(() -> "Forced experiments: " + forced);
            }

            Map<String, Boolean> overrides = new HashMap<>(2 * (disabled.size() + forced.size()));
            for (String experiment : disabled) overrides.put(experiment, FALSE);
            for (String experiment : forced) overrides.put(experiment, TRUE);

            return Collections.unmodifiableMap(overrides);
        }
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @param experimentName Name of an experiment that is read as on or off.
     * @param enabled        If the app has the experiment on.
     */
    public static boolean isBooleanExperimentEnabled(String experimentName, boolean enabled) {
        try {
            if (!Utils.isContextSet() || !State.LOG_FEATURE_FLAGS) {
                return enabled;
            }

            Boolean override = State.OVERRIDDEN_EXPERIMENTS.get(experimentName);
            if (override != null) {
                return override;
            }

            // Always add the experiment but only log if it is enabled.
            if (State.experiments.putIfAbsent(experimentName, enabled) == null && enabled) {
                Logger.printVerbose(() -> "experiment is enabled: " + experimentName);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "isBooleanExperimentEnabled failure", ex);
        }

        return enabled;
    }

    /**
     * Injection point.
     *
     * @param experimentName Name of an experiment that is read as a variant.
     * @param variant        Variant of the experiment, or null if the user is not in the experiment.
     * @return The variant to use. Forcing has no effect, as there is no single variant
     *         that turns an experiment on.
     */
    @Nullable
    public static String getExperimentVariant(String experimentName, @Nullable String variant) {
        try {
            if (!Utils.isContextSet() || !State.LOG_FEATURE_FLAGS) {
                return variant;
            }

            if (FALSE.equals(State.OVERRIDDEN_EXPERIMENTS.get(experimentName))) {
                return null;
            }

            final boolean enabled = variant != null && !variant.toLowerCase(Locale.ROOT)
                    .startsWith(CONTROL_VARIANT_PREFIX);
            if (State.experiments.putIfAbsent(experimentName, enabled) == null) {
                if (variant != null) {
                    State.variants.put(experimentName, variant);
                }
                if (enabled) {
                    Logger.printVerbose(() -> "experiment is enabled: " + experimentName
                            + " variant: " + variant);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getExperimentVariant failure", ex);
        }

        return variant;
    }
}
