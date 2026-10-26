/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.subscriptiongroups;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Persistence for subscription channel groups, following the same
 * single-StringSetting pattern as the channel whitelist.
 *
 * Storage format v2 (JSON):
 * <pre>
 * {"version":2,"groups":[
 *   {"name":"Music","channels":[{"id":"","handle":"@someone","name":"Some One"}]}
 * ]}
 * </pre>
 * Each channel is a {@link ChannelEntry}: channel id if known, else
 * @handle, else display name. Data written by v1 (the line-based
 * format parsed by {@link SubscriptionGroupFilter}) is migrated on
 * first load: its channels become name-only entries.
 *
 * The known-channels list (channels seen on channel pages, with
 * handle + name) is kept in a second setting as a JSON array of
 * entries. It powers the "choose from known channels" member picker
 * and lets hand-typed names be upgraded to handle entries.
 */
public final class SubscriptionGroups {

    private static String cachedRaw;
    private static LinkedHashMap<String, LinkedHashSet<ChannelEntry>> cachedGroups;
    private static String cachedKnownRaw;
    private static List<ChannelEntry> cachedKnown;

    private SubscriptionGroups() {
    }

    /** All groups, in creation order. Reparses only when the raw value changed. */
    public static synchronized LinkedHashMap<String, LinkedHashSet<ChannelEntry>> loadGroups() {
        String raw = Settings.SUBSCRIPTION_GROUPS_DATA.get();
        if (cachedGroups == null || !raw.equals(cachedRaw)) {
            cachedRaw = raw;
            cachedGroups = parseStored(raw);
        }
        // Return a copy so callers can edit and save without touching the cache.
        LinkedHashMap<String, LinkedHashSet<ChannelEntry>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<ChannelEntry>> group : cachedGroups.entrySet()) {
            copy.put(group.getKey(), new LinkedHashSet<>(group.getValue()));
        }
        return copy;
    }

    public static synchronized void saveGroups(
            LinkedHashMap<String, LinkedHashSet<ChannelEntry>> groups) {
        try {
            String raw = toJson(groups);
            Settings.SUBSCRIPTION_GROUPS_DATA.save(raw);
            cachedRaw = raw;
            cachedGroups = parseStored(raw);
        } catch (Exception ex) {
            Logger.printException(() -> "Subscription groups save failure", ex);
        }
    }

    private static LinkedHashMap<String, LinkedHashSet<ChannelEntry>> parseStored(String raw) {
        LinkedHashMap<String, LinkedHashSet<ChannelEntry>> groups = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return groups;
        }
        if (!raw.startsWith("{")) {
            // v1 line format: migrate name-only entries.
            for (Map.Entry<String, LinkedHashSet<String>> group
                    : SubscriptionGroupFilter.parse(raw).entrySet()) {
                LinkedHashSet<ChannelEntry> entries = new LinkedHashSet<>();
                for (String name : group.getValue()) {
                    entries.add(ChannelEntry.nameOnly(name));
                }
                groups.put(group.getKey(), entries);
            }
            return groups;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray groupArray = root.optJSONArray("groups");
            if (groupArray == null) {
                return groups;
            }
            for (int i = 0; i < groupArray.length(); i++) {
                JSONObject groupObject = groupArray.optJSONObject(i);
                if (groupObject == null) {
                    continue;
                }
                String name = groupObject.optString("name", "").trim();
                if (name.isEmpty() || groups.containsKey(name)) {
                    continue;
                }
                LinkedHashSet<ChannelEntry> entries = new LinkedHashSet<>();
                JSONArray channels = groupObject.optJSONArray("channels");
                if (channels != null) {
                    for (int j = 0; j < channels.length(); j++) {
                        JSONObject channel = channels.optJSONObject(j);
                        if (channel != null) {
                            entries.add(entryFromJson(channel));
                        }
                    }
                }
                groups.put(name, entries);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Subscription groups parse failure", ex);
        }
        return groups;
    }

    private static String toJson(LinkedHashMap<String, LinkedHashSet<ChannelEntry>> groups) {
        try {
            JSONArray groupArray = new JSONArray();
            for (Map.Entry<String, LinkedHashSet<ChannelEntry>> group : groups.entrySet()) {
                String name = group.getKey() == null ? "" : group.getKey().trim();
                if (name.isEmpty()) {
                    continue;
                }
                JSONArray channels = new JSONArray();
                for (ChannelEntry entry : group.getValue()) {
                    channels.put(entryToJson(entry));
                }
                JSONObject groupObject = new JSONObject();
                groupObject.put("name", name);
                groupObject.put("channels", channels);
                groupArray.put(groupObject);
            }
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("groups", groupArray);
            return root.toString();
        } catch (Exception ex) {
            Logger.printException(() -> "Subscription groups serialize failure", ex);
            return "";
        }
    }

    private static JSONObject entryToJson(ChannelEntry entry) throws Exception {
        JSONObject object = new JSONObject();
        object.put("id", entry.id);
        object.put("handle", entry.handle);
        object.put("name", entry.name);
        return object;
    }

    private static ChannelEntry entryFromJson(JSONObject object) {
        return new ChannelEntry(
                object.optString("id", ""),
                object.optString("handle", ""),
                object.optString("name", ""));
    }

    /** @return false if a group with this (normalized) name already exists. */
    public static boolean createGroup(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        LinkedHashMap<String, LinkedHashSet<ChannelEntry>> groups = loadGroups();
        if (SubscriptionGroupFilter.findGroup(groups, trimmed) != null) {
            return false;
        }
        groups.put(trimmed, new LinkedHashSet<>());
        saveGroups(groups);
        return true;
    }

    public static void deleteGroup(String name) {
        LinkedHashMap<String, LinkedHashSet<ChannelEntry>> groups = loadGroups();
        String keyToRemove = null;
        for (String group : groups.keySet()) {
            if (SubscriptionGroupFilter.normalizeChannelName(group)
                    .equals(SubscriptionGroupFilter.normalizeChannelName(name))) {
                keyToRemove = group;
                break;
            }
        }
        if (keyToRemove != null) {
            groups.remove(keyToRemove);
            saveGroups(groups);
        }
    }

    public static void setGroupMembers(String groupName, Collection<ChannelEntry> channelEntries) {
        LinkedHashMap<String, LinkedHashSet<ChannelEntry>> groups = loadGroups();
        for (String group : groups.keySet()) {
            if (SubscriptionGroupFilter.normalizeChannelName(group)
                    .equals(SubscriptionGroupFilter.normalizeChannelName(groupName))) {
                groups.put(group, new LinkedHashSet<>(channelEntries));
                saveGroups(groups);
                return;
            }
        }
    }

    // region Known channels

    /** Channels recorded from channel pages, in first-seen order. */
    public static synchronized List<ChannelEntry> loadKnownChannels() {
        String raw = Settings.SUBSCRIPTION_GROUPS_KNOWN.get();
        if (cachedKnown == null || !raw.equals(cachedKnownRaw)) {
            cachedKnownRaw = raw;
            cachedKnown = new ArrayList<>();
            try {
                if (raw != null && raw.startsWith("[")) {
                    JSONArray array = new JSONArray(raw);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject object = array.optJSONObject(i);
                        if (object != null) {
                            cachedKnown.add(entryFromJson(object));
                        }
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Known channels parse failure", ex);
            }
        }
        return new ArrayList<>(cachedKnown);
    }

    /**
     * Records a channel seen on its channel page. An existing entry
     * with the same id/handle/key is refreshed with any stronger data
     * the new sighting has; nothing is downgraded.
     */
    public static synchronized void recordKnownChannel(ChannelEntry entry) {
        if (entry == null || entry.displayName().isEmpty()) {
            return;
        }
        try {
            List<ChannelEntry> known = loadKnownChannels();
            int existingIndex = -1;
            for (int i = 0; i < known.size(); i++) {
                ChannelEntry existing = known.get(i);
                if (existing.key().equals(entry.key())
                        || (!entry.handle.isEmpty() && existing.handle.equals(entry.handle))
                        || (!entry.id.isEmpty() && existing.id.equals(entry.id))) {
                    existingIndex = i;
                    break;
                }
            }
            if (existingIndex >= 0) {
                ChannelEntry existing = known.get(existingIndex);
                ChannelEntry merged = new ChannelEntry(
                        !entry.id.isEmpty() ? entry.id : existing.id,
                        !entry.handle.isEmpty() ? entry.handle : existing.handle,
                        !entry.name.isEmpty() ? entry.name : existing.name);
                if (merged.key().equals(existing.key())
                        && merged.name.equals(existing.name)) {
                    return; // Nothing new learned.
                }
                known.set(existingIndex, merged);
            } else {
                known.add(entry);
            }
            JSONArray array = new JSONArray();
            for (ChannelEntry knownEntry : known) {
                array.put(entryToJson(knownEntry));
            }
            String raw = array.toString();
            Settings.SUBSCRIPTION_GROUPS_KNOWN.save(raw);
            cachedKnownRaw = raw;
            cachedKnown = new ArrayList<>(known);
        } catch (Exception ex) {
            Logger.printException(() -> "Known channels save failure", ex);
        }
    }

    // endregion
}
