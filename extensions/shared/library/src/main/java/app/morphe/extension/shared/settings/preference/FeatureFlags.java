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

package app.morphe.extension.shared.settings.preference;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The feature flags of an app, as shown by {@link AbstractFeatureFlagsManagerPreference}.
 * Blocked and forced flags are saved in the shared feature flag settings.
 *
 * @param <K> Type of the flag IDs.
 */
public abstract class FeatureFlags<K extends Comparable<K>> {

    /**
     * @return All flags the app has read since it started, and if each flag is active.
     */
    public abstract Map<K, Boolean> getLoggedFlags();

    /**
     * @param flag Text of a single flag ID.
     * @throws IllegalArgumentException If the text is not a valid flag ID.
     */
    protected abstract K parseFlag(String flag);

    /**
     * @return If flag IDs are numbers, which shows a number keyboard for the search.
     */
    protected abstract boolean hasNumericFlags();

    /**
     * @return Flags to hide from the UI.
     */
    protected Set<K> getFlagsToIgnore() {
        return Collections.emptySet();
    }

    /**
     * @return Text shown below the flag in the UI, or null to show only the flag.
     */
    @Nullable
    protected String getFlagSummary(K flag) {
        return null;
    }

    /**
     * @param flags String containing flag IDs separated by commas or whitespace.
     * @return Set of parsed flag IDs.
     */
    public Set<K> parseFlags(String flags) {
        return new HashSet<>(parseFlagList(flags));
    }

    /**
     * @param flags String containing flag IDs separated by commas or whitespace.
     * @return Parsed flag IDs, in the order they appear.
     */
    public List<K> parseFlagList(String flags) {
        if (flags.isBlank()) {
            return Collections.emptyList();
        }

        String[] split = flags.split("[,\\s]+");
        List<K> parsedFlags = new ArrayList<>(split.length);
        for (String flag : split) {
            String trimmedFlag = flag.trim();
            if (trimmedFlag.isEmpty()) continue; // Skip empty entries.
            try {
                parsedFlags.add(parseFlag(trimmedFlag));
            } catch (IllegalArgumentException e) {
                Logger.printException(() -> "Invalid flag ID: " + flag);
            }
        }

        return parsedFlags;
    }

    /**
     * Serializes flags into the format used by the settings.
     *
     * @param flags Flag IDs to serialize.
     * @return String containing newline-separated flag IDs.
     */
    public String serializeFlags(Collection<K> flags) {
        return serializeFlags(flags, '\n');
    }

    /**
     * @param flags     Flag IDs to serialize.
     * @param separator Separator to put between the flag IDs.
     * @return String containing the separated flag IDs.
     */
    public String serializeFlags(Collection<K> flags, char separator) {
        StringBuilder builder = new StringBuilder(16 * flags.size());
        for (K flag : flags) {
            if (Utils.isNotEmpty(builder)) {
                builder.append(separator);
            }
            builder.append(flag);
        }

        return builder.toString();
    }
}
