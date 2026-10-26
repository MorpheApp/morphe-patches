/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.subscriptiongroups;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Persistence for subscription channel groups.
 *
 * Follows the {@code ChannelWhitelist} storage pattern: the whole
 * collection lives in a single {@link app.morphe.extension.shared.settings.StringSetting}
 * ({@link Settings#SUBSCRIPTION_GROUPS_DATA}), reads are cached and
 * only reparsed when the raw setting value changes (which also picks
 * up setting imports), and writes serialize the whole collection back
 * into that one setting. The serialization format itself is defined
 * by {@link SubscriptionGroupFilter}.
 */
public final class SubscriptionGroups {

    private static String cachedRaw;
    private static LinkedHashMap<String, LinkedHashSet<String>> cachedGroups;

    private SubscriptionGroups() {
    }

    /** All groups, in creation order. Reparses only when the raw value changed. */
    public static synchronized LinkedHashMap<String, LinkedHashSet<String>> loadGroups() {
        String raw = Settings.SUBSCRIPTION_GROUPS_DATA.get();
        if (cachedGroups == null || !raw.equals(cachedRaw)) {
            cachedRaw = raw;
            cachedGroups = SubscriptionGroupFilter.parse(raw);
        }
        // Return a copy so callers can edit and save without touching the cache.
        LinkedHashMap<String, LinkedHashSet<String>> copy = new LinkedHashMap<>();
        for (String group : cachedGroups.keySet()) {
            copy.put(group, new LinkedHashSet<>(cachedGroups.get(group)));
        }
        return copy;
    }

    public static synchronized void saveGroups(
            LinkedHashMap<String, LinkedHashSet<String>> groups) {
        try {
            String raw = SubscriptionGroupFilter.serialize(groups);
            Settings.SUBSCRIPTION_GROUPS_DATA.save(raw);
            cachedRaw = raw;
            cachedGroups = SubscriptionGroupFilter.parse(raw);
        } catch (Exception ex) {
            Logger.printException(() -> "Subscription groups save failure", ex);
        }
    }

    /** @return false if a group with this (normalized) name already exists. */
    public static boolean createGroup(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        LinkedHashMap<String, LinkedHashSet<String>> groups = loadGroups();
        if (SubscriptionGroupFilter.findGroup(groups, trimmed) != null) {
            return false;
        }
        groups.put(trimmed, new LinkedHashSet<>());
        saveGroups(groups);
        return true;
    }

    public static void deleteGroup(String name) {
        LinkedHashMap<String, LinkedHashSet<String>> groups = loadGroups();
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

    public static void setGroupMembers(String groupName, Collection<String> channelNames) {
        LinkedHashMap<String, LinkedHashSet<String>> groups = loadGroups();
        for (String group : groups.keySet()) {
            if (SubscriptionGroupFilter.normalizeChannelName(group)
                    .equals(SubscriptionGroupFilter.normalizeChannelName(groupName))) {
                groups.put(group, new LinkedHashSet<>(channelNames));
                saveGroups(groups);
                return;
            }
        }
    }
}
