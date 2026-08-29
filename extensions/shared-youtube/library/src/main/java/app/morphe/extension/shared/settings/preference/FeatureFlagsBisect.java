/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2638
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.EnableDebuggingPatch;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/**
 * Finds which feature flag causes an app behavior by blocking half of the remaining
 * candidates, then asking after each app restart if the behavior is still present.
 * <p>
 * The candidates are frozen when the search starts, because a blocked flag is never
 * logged again and the set of logged flags would otherwise shrink on every restart.
 */
public final class FeatureFlagsBisect {

    enum Result {
        /** More steps are needed. */
        CONTINUE,
        /** A single flag was identified. */
        FOUND,
        /** All candidates were ruled out. */
        EXHAUSTED
    }

    private static final String FIELD_SEPARATOR = ";";
    private static final char FLAG_SEPARATOR = ',';

    /**
     * Flags that may still cause the behavior.
     */
    private final List<Long> candidates;

    /**
     * Candidates blocked for the current step. Always the first half of the candidates.
     */
    private final List<Long> testing = new ArrayList<>();

    /**
     * Flags the user had blocked before the search started. Restored when it ends.
     */
    private final List<Long> userBlocked;

    private int step;

    private long foundFlag;

    public static void handleAppStartup() {
        // Remind the user they are searching for flags
        if (isActive()) {
            Utils.showToastShort(str("morphe_debug_feature_flags_manager_bisect_in_progress"));
        }
    }

    private FeatureFlagsBisect(List<Long> candidates, List<Long> userBlocked, int step) {
        this.candidates = candidates;
        this.userBlocked = userBlocked;
        this.step = step;
    }

    static boolean isActive() {
        return !SharedYouTubeSettings.FEATURE_FLAGS_BISECT.get().isBlank();
    }

    /**
     * @return The search in progress, or null if there is none or the saved state is invalid.
     */
    @Nullable
    static FeatureFlagsBisect load() {
        String state = SharedYouTubeSettings.FEATURE_FLAGS_BISECT.get();
        if (state.isBlank()) return null;

        try {
            String[] fields = state.split(FIELD_SEPARATOR, -1);
            if (fields.length != 4) throw new IllegalArgumentException("Wrong number of fields");

            FeatureFlagsBisect bisect = new FeatureFlagsBisect(
                    EnableDebuggingPatch.parseFlagList(fields[1]),
                    EnableDebuggingPatch.parseFlagList(fields[3]),
                    Integer.parseInt(fields[0]));
            bisect.testing.addAll(EnableDebuggingPatch.parseFlagList(fields[2]));

            return bisect;
        } catch (Exception ex) {
            Logger.printException(() -> "Invalid binary search state: " + state, ex);
            SharedYouTubeSettings.FEATURE_FLAGS_BISECT.resetToDefault();

            return null;
        }
    }

    /**
     * Starts a search over the given flags, blocking the first half of them.
     */
    static void start(Collection<Long> flags) {
        TreeSet<Long> userBlocked = new TreeSet<>(EnableDebuggingPatch.parseFlags(
                SharedYouTubeSettings.DISABLED_FEATURE_FLAGS.get()));

        TreeSet<Long> candidates = new TreeSet<>(flags);
        // A flag the user already blocked cannot be the cause of a behavior that is present.
        candidates.removeAll(userBlocked);

        new FeatureFlagsBisect(new ArrayList<>(candidates), new ArrayList<>(userBlocked), 1)
                .splitAndApply();
    }

    /**
     * Applies the answer for the current step and moves the search forward.
     *
     * @param behaviorPresent If the behavior is still present with the current half blocked.
     */
    Result answer(boolean behaviorPresent) {
        if (behaviorPresent) {
            // None of the blocked flags cause the behavior.
            candidates.removeAll(testing);
        } else {
            // Blocking these flags removed the behavior, so the cause is one of them.
            candidates.retainAll(testing);

            if (candidates.size() == 1) {
                foundFlag = candidates.get(0);
                finish(foundFlag);

                return Result.FOUND;
            }
        }

        if (candidates.isEmpty()) {
            finish(null);

            return Result.EXHAUSTED;
        }

        step++;
        splitAndApply();

        return Result.CONTINUE;
    }

    /**
     * Ends the search and restores the flags the user had blocked.
     */
    void cancel() {
        finish(null);
    }

    int getStep() {
        return step;
    }

    int getRemainingCount() {
        return candidates.size();
    }

    int getTestingCount() {
        return testing.size();
    }

    long getFoundFlag() {
        return foundFlag;
    }

    /**
     * Blocks the first half of the remaining candidates.
     */
    private void splitAndApply() {
        testing.clear();
        // Round up so a single remaining candidate is still tested on its own.
        testing.addAll(candidates.subList(0, (candidates.size() + 1) / 2));

        TreeSet<Long> blocked = new TreeSet<>(userBlocked);
        blocked.addAll(testing);
        SharedYouTubeSettings.DISABLED_FEATURE_FLAGS.save(EnableDebuggingPatch.serializeFlags(blocked));

        SharedYouTubeSettings.FEATURE_FLAGS_BISECT.save(step
                + FIELD_SEPARATOR + EnableDebuggingPatch.serializeFlags(candidates, FLAG_SEPARATOR)
                + FIELD_SEPARATOR + EnableDebuggingPatch.serializeFlags(testing, FLAG_SEPARATOR)
                + FIELD_SEPARATOR + EnableDebuggingPatch.serializeFlags(userBlocked, FLAG_SEPARATOR));

        Logger.printDebug(() -> "Binary search step " + step + ", " + candidates.size()
                + " candidates, blocking " + testing.size());
    }

    /**
     * Restores the flags the user had blocked, keeping the found flag blocked if there is one.
     */
    private void finish(@Nullable Long found) {
        TreeSet<Long> blocked = new TreeSet<>(userBlocked);
        if (found != null) {
            blocked.add(found);
        }

        SharedYouTubeSettings.DISABLED_FEATURE_FLAGS.save(EnableDebuggingPatch.serializeFlags(blocked));
        SharedYouTubeSettings.FEATURE_FLAGS_BISECT.resetToDefault();
    }
}
