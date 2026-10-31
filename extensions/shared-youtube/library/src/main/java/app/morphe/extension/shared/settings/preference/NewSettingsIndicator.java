/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4162
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import androidx.annotation.ColorInt;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/**
 * Shows a dot next to settings and list entries that are new to the user,
 * for the number of days set with {@link SharedYouTubeSettings#SETTINGS_NEW_INDICATOR_DAYS}.
 * <p>
 * A setting is new from the first time its key is in the settings menu.
 * The first time this runs, all current keys are saved as already known,
 * so nothing is new after a fresh install or after updating to the first version with this feature.
 */
@SuppressWarnings("deprecation")
public final class NewSettingsIndicator {

    /**
     * Morphe teal, the light end of the logo gradient.
     */
    @ColorInt
    private static final int DOT_COLOR = 0xFF00AFAE;

    private static final SharedPreferences preferences =
            new SharedPrefCategory("morphe_new_settings").preferences;

    /**
     * All preference keys and list entries that were seen in the settings menu.
     */
    private static final String KNOWN_IDS_KEY = "known_ids";

    /**
     * Prefix of the keys that store the time an id was first seen.
     * Only ids first seen within the max days of the seek bar are stored.
     */
    private static final String FIRST_SEEN_KEY_PREFIX = "first_seen:";

    /**
     * Separates the preference key and the entry value in the id of a list entry.
     */
    private static final String LIST_ENTRY_SEPARATOR = "|";

    private static String listEntryId(String key, CharSequence entryValue) {
        return key + LIST_ENTRY_SEPARATOR + entryValue;
    }

    /**
     * Saves the keys that are seen for the first time, and adds a dot to new preferences,
     * list entries and the preference screens that contain them.
     * Must be called after all preferences are added to and removed from the screen.
     */
    public static void markNewPreferences(PreferenceScreen screen) {
        try {
            Set<String> currentIds = new HashSet<>();
            collectIds(screen, currentIds);

            Map<String, Long> firstSeenTimes = updateFirstSeenTimes(currentIds);

            final int days = SeekBarPreference.clampToRange(SharedYouTubeSettings.SETTINGS_NEW_INDICATOR_DAYS);
            if (days == 0 || firstSeenTimes.isEmpty()) {
                return;
            }

            final long newAfter = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
            Set<String> newIds = new HashSet<>();
            for (Map.Entry<String, Long> entry : firstSeenTimes.entrySet()) {
                if (entry.getValue() > newAfter) {
                    newIds.add(entry.getKey());
                }
            }
            if (newIds.isEmpty()) {
                return;
            }

            Logger.printDebug(() -> "New settings: " + newIds);
            markGroup(screen, newIds);
        } catch (Exception ex) {
            Logger.printException(() -> "markNewPreferences failure", ex);
        }
    }

    private static void collectIds(PreferenceGroup group, Set<String> ids) {
        for (int i = 0, count = group.getPreferenceCount(); i < count; i++) {
            Preference pref = group.getPreference(i);
            if (pref instanceof PreferenceGroup subGroup) {
                // Group keys include the sorting and are not tracked.
                // A group is new if anything inside it is new.
                collectIds(subGroup, ids);
                continue;
            }

            String key = pref.getKey();
            if (key == null) continue;
            ids.add(key);

            if (pref instanceof CustomDialogListPreference listPref && listPref.tracksNewEntries()) {
                CharSequence[] entryValues = listPref.getEntryValues();
                if (entryValues != null) {
                    for (CharSequence entryValue : entryValues) {
                        ids.add(listEntryId(key, entryValue));
                    }
                }
            }
        }
    }

    /**
     * Saves ids seen for the first time, and removes first seen times older than the max days.
     *
     * @return The first seen times of the ids added within the max days.
     */
    private static Map<String, Long> updateFirstSeenTimes(Set<String> currentIds) {
        if (!preferences.contains(KNOWN_IDS_KEY)) {
            Logger.printDebug(() -> "Saving " + currentIds.size() + " known settings");
            preferences.edit().putStringSet(KNOWN_IDS_KEY, currentIds).apply();
            return Collections.emptyMap();
        }

        Set<String> knownIds = new HashSet<>(preferences.getStringSet(KNOWN_IDS_KEY, Collections.emptySet()));
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        final long now = System.currentTimeMillis();
        Map<String, Long> firstSeenTimes = new HashMap<>();
        for (String id : currentIds) {
            if (knownIds.add(id)) {
                editor.putLong(FIRST_SEEN_KEY_PREFIX + id, now);
                firstSeenTimes.put(id, now);
                changed = true;
            }
        }
        if (changed) {
            editor.putStringSet(KNOWN_IDS_KEY, knownIds);
        }

        SeekBarPreference.SeekBarConfig config =
                SeekBarPreference.configFor(SharedYouTubeSettings.SETTINGS_NEW_INDICATOR_DAYS);
        final long expiredBefore = now - TimeUnit.DAYS.toMillis(config.max());
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(FIRST_SEEN_KEY_PREFIX)) continue;

            final long firstSeen = (Long) entry.getValue();
            if (firstSeen < expiredBefore) {
                editor.remove(key);
                changed = true;
            } else {
                firstSeenTimes.put(key.substring(FIRST_SEEN_KEY_PREFIX.length()), firstSeen);
            }
        }

        if (changed) {
            editor.apply();
        }
        return firstSeenTimes;
    }

    /**
     * @return If the group contains a new preference or list entry.
     */
    private static boolean markGroup(PreferenceGroup group, Set<String> newIds) {
        boolean groupHasNew = false;

        for (int i = 0, count = group.getPreferenceCount(); i < count; i++) {
            Preference pref = group.getPreference(i);
            final boolean isNew;

            if (pref instanceof PreferenceGroup subGroup) {
                isNew = markGroup(subGroup, newIds);
            } else {
                String key = pref.getKey();
                if (key == null) continue;

                boolean hasNewEntry = false;
                if (pref instanceof CustomDialogListPreference listPref && listPref.tracksNewEntries()) {
                    CharSequence[] entryValues = listPref.getEntryValues();
                    if (entryValues != null) {
                        Set<String> newEntryValues = new HashSet<>();
                        for (CharSequence entryValue : entryValues) {
                            if (newIds.contains(listEntryId(key, entryValue))) {
                                newEntryValues.add(entryValue.toString());
                            }
                        }
                        if (!newEntryValues.isEmpty()) {
                            listPref.setNewEntryValues(newEntryValues, DOT_COLOR);
                            hasNewEntry = true;
                        }
                    }
                }

                isNew = hasNewEntry || newIds.contains(key);
            }

            // Categories are not marked, since the new preference is shown right below them.
            if (isNew && (pref instanceof PreferenceScreen || !(pref instanceof PreferenceGroup))) {
                pref.setTitle(NewIndicatorSpan.addTo(pref.getTitle(), DOT_COLOR));
            }
            groupHasNew |= isNew;
        }

        return groupHasNew;
    }
}
