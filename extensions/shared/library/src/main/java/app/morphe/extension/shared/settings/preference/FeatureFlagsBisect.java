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
import app.morphe.extension.shared.settings.SharedSettings;

/**
 * Finds which feature flag causes an app behavior by blocking half of the remaining
 * candidates, then asking after each app restart if the behavior is still present.
 * <p>
 * The candidates are frozen when the search starts, because a blocked flag is never
 * logged again and the set of logged flags would otherwise shrink on every restart.
 *
 * @param <K> Type of the flag IDs.
 */
@SuppressWarnings("unused")
public final class FeatureFlagsBisect<K extends Comparable<K>> {

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

    private final FeatureFlags<K> flags;

    /**
     * Flags that may still cause the behavior.
     */
    private final List<K> candidates;

    /**
     * Candidates blocked for the current step. Always the first half of the candidates.
     */
    private final List<K> testing = new ArrayList<>();

    /**
     * Flags the user had blocked before the search started. Restored when it ends.
     */
    private final List<K> userBlocked;

    private int step;

    @Nullable
    private K foundFlag;

    /**
     * If the behavior was ever reported as gone. A search that ends with no candidates
     * after this means more than one flag is involved, or an answer was wrong.
     */
    private boolean behaviorEverAbsent;

    public static void handleAppStartup() {
        // Remind the user they are searching for flags
        if (isActive()) {
            Utils.showToastShort(str("morphe_debug_feature_flags_manager_bisect_in_progress"));
        }
    }

    private FeatureFlagsBisect(FeatureFlags<K> flags, List<K> candidates, List<K> userBlocked, int step) {
        this.flags = flags;
        this.candidates = candidates;
        this.userBlocked = userBlocked;
        this.step = step;
    }

    static boolean isActive() {
        return !SharedSettings.FEATURE_FLAGS_BISECT.get().isBlank();
    }

    /**
     * @return The search in progress, or null if there is none or the saved state is invalid.
     */
    @Nullable
    static <K extends Comparable<K>> FeatureFlagsBisect<K> load(FeatureFlags<K> flags) {
        String state = SharedSettings.FEATURE_FLAGS_BISECT.get();
        if (state.isBlank()) return null;

        try {
            String[] fields = state.split(FIELD_SEPARATOR, -1);
            if (fields.length < 4) throw new IllegalArgumentException("Wrong number of fields");

            FeatureFlagsBisect<K> bisect = new FeatureFlagsBisect<>(flags,
                    new ArrayList<>(flags.parseFlagList(fields[1])),
                    new ArrayList<>(flags.parseFlagList(fields[3])),
                    Integer.parseInt(fields[0]));
            bisect.testing.addAll(flags.parseFlagList(fields[2]));
            // A search started before this field existed simply has no absent answer.
            bisect.behaviorEverAbsent = fields.length > 4 && "1".equals(fields[4]);

            return bisect;
        } catch (Exception ex) {
            Logger.printException(() -> "Invalid binary search state: " + state, ex);
            SharedSettings.FEATURE_FLAGS_BISECT.resetToDefault();

            return null;
        }
    }

    /**
     * Starts a search over the given flags, blocking the first half of them.
     */
    static <K extends Comparable<K>> void start(FeatureFlags<K> flags, Collection<K> searchFlags) {
        TreeSet<K> userBlocked = new TreeSet<>(flags.parseFlags(
                SharedSettings.DISABLED_FEATURE_FLAGS.get()));

        TreeSet<K> candidates = new TreeSet<>(searchFlags);
        // A flag the user already blocked cannot be the cause of a behavior that is present.
        candidates.removeAll(userBlocked);

        new FeatureFlagsBisect<>(flags, new ArrayList<>(candidates), new ArrayList<>(userBlocked), 1)
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
            behaviorEverAbsent = true;
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

    @Nullable
    K getFoundFlag() {
        return foundFlag;
    }

    boolean behaviorEverAbsent() {
        return behaviorEverAbsent;
    }

    /**
     * Blocks the first half of the remaining candidates.
     */
    private void splitAndApply() {
        testing.clear();
        // Round up so a single remaining candidate is still tested on its own.
        testing.addAll(candidates.subList(0, (candidates.size() + 1) / 2));

        TreeSet<K> blocked = new TreeSet<>(userBlocked);
        blocked.addAll(testing);
        SharedSettings.DISABLED_FEATURE_FLAGS.save(flags.serializeFlags(blocked));

        SharedSettings.FEATURE_FLAGS_BISECT.save(step
                + FIELD_SEPARATOR + flags.serializeFlags(candidates, FLAG_SEPARATOR)
                + FIELD_SEPARATOR + flags.serializeFlags(testing, FLAG_SEPARATOR)
                + FIELD_SEPARATOR + flags.serializeFlags(userBlocked, FLAG_SEPARATOR)
                + FIELD_SEPARATOR + (behaviorEverAbsent ? "1" : "0"));

        Logger.printDebug(() -> "Binary search step: " + step + " candidates: " + candidates.size()
                + " blocking: " + testing.size());
    }

    /**
     * Restores the flags the user had blocked, keeping the found flag blocked if there is one.
     */
    private void finish(@Nullable K found) {
        TreeSet<K> blocked = new TreeSet<>(userBlocked);
        if (found != null) {
            blocked.add(found);
        }

        SharedSettings.DISABLED_FEATURE_FLAGS.save(flags.serializeFlags(blocked));
        SharedSettings.FEATURE_FLAGS_BISECT.resetToDefault();
    }
}
