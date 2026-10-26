/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.subscriptiongroups;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure group-membership logic for subscription channel groups.
 * No Android imports, so this class can be reasoned about (and unit
 * tested) without a device.
 *
 * Model: an ordered map of group name -> ordered set of channel names.
 * Channel names are the display names exactly as shown in the
 * subscriptions feed.
 *
 * Serialization format (line based, one entry per line):
 * <pre>
 * #morphe_subscription_groups_v1
 * group:Music
 * channel:Channel A
 * channel:Channel B
 * group:Games
 * channel:Channel C
 * </pre>
 * A "group:" line starts a new group, the following "channel:" lines
 * are its members until the next "group:" line. Blank lines are
 * ignored and parsing is best-effort: unknown lines are skipped, so a
 * hand-edited or partially corrupted value degrades to fewer groups
 * instead of failing. Group names and channel names are trimmed;
 * names containing a newline cannot be represented.
 */
public final class SubscriptionGroupFilter {

    /** Selection that shows every channel. */
    public static final String ALL = "all";

    private static final String HEADER = "#morphe_subscription_groups_v1";
    private static final String GROUP_PREFIX = "group:";
    private static final String CHANNEL_PREFIX = "channel:";

    private SubscriptionGroupFilter() {
    }

    /**
     * Normalizes a channel or group name for comparison: trims,
     * collapses runs of whitespace into a single space and lower-cases
     * (case only matters for latin names).
     */
    public static String normalizeChannelName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static String serialize(Map<String, LinkedHashSet<String>> groups) {
        StringBuilder sb = new StringBuilder(HEADER);
        for (Map.Entry<String, LinkedHashSet<String>> group : groups.entrySet()) {
            String groupName = group.getKey() == null ? "" : group.getKey().trim();
            if (groupName.isEmpty()) {
                continue;
            }
            sb.append('\n').append(GROUP_PREFIX).append(groupName);
            for (String channel : group.getValue()) {
                String channelName = channel == null ? "" : channel.trim();
                if (!channelName.isEmpty()) {
                    sb.append('\n').append(CHANNEL_PREFIX).append(channelName);
                }
            }
        }
        return sb.toString();
    }

    public static LinkedHashMap<String, LinkedHashSet<String>> parse(String serialized) {
        LinkedHashMap<String, LinkedHashSet<String>> groups = new LinkedHashMap<>();
        if (serialized == null) {
            return groups;
        }
        String currentGroup = null;
        for (String rawLine : serialized.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.equals(HEADER)) {
                continue;
            }
            if (line.startsWith(GROUP_PREFIX)) {
                String name = line.substring(GROUP_PREFIX.length()).trim();
                if (!name.isEmpty() && !groups.containsKey(name)) {
                    groups.put(name, new LinkedHashSet<>());
                    currentGroup = name;
                } else {
                    currentGroup = name.isEmpty() ? null : name;
                }
            } else if (line.startsWith(CHANNEL_PREFIX) && currentGroup != null) {
                String channel = line.substring(CHANNEL_PREFIX.length()).trim();
                if (!channel.isEmpty()) {
                    groups.get(currentGroup).add(channel);
                }
            }
        }
        return groups;
    }

    /** Every channel name mentioned by any group, in first-seen order. */
    public static LinkedHashSet<String> knownChannelNames(
            Map<String, LinkedHashSet<String>> groups) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (LinkedHashSet<String> members : groups.values()) {
            names.addAll(members);
        }
        return names;
    }

    /**
     * Conservative visibility rule for a feed item.
     *
     * @param selectedGroup the group the user picked, or null/empty/"all"
     * @param channelName   the channel identified in the item, or null
     *                      when no channel could be identified
     * @param groups        all groups
     * @return true if the item stays visible. Items whose channel could
     * not be identified (channelName == null) are NEVER hidden.
     */
    public static boolean shouldShow(String selectedGroup, String channelName,
                                     Map<String, LinkedHashSet<String>> groups) {
        if (selectedGroup == null || selectedGroup.trim().isEmpty()
                || ALL.equals(normalizeChannelName(selectedGroup))) {
            return true;
        }
        if (channelName == null) {
            // Unidentified channel: never hide.
            return true;
        }
        LinkedHashSet<String> members = findGroup(groups, selectedGroup);
        if (members == null) {
            return true;
        }
        String normalizedChannel = normalizeChannelName(channelName);
        for (String member : members) {
            if (normalizeChannelName(member).equals(normalizedChannel)) {
                return true;
            }
        }
        return false;
    }

    /** Finds a group's member set by (normalized) name, or null. */
    public static LinkedHashSet<String> findGroup(
            Map<String, LinkedHashSet<String>> groups, String groupName) {
        if (groupName == null) {
            return null;
        }
        for (Map.Entry<String, LinkedHashSet<String>> group : groups.entrySet()) {
            if (normalizeChannelName(group.getKey()).equals(normalizeChannelName(groupName))) {
                return group.getValue();
            }
        }
        return null;
    }

    /**
     * Identifies the channel of a feed item from its visible text lines.
     * A line counts as the channel only if, after normalization, it is
     * exactly equal to a known channel name - substring matching would
     * misidentify videos whose title merely mentions a channel.
     *
     * @return the known channel name in its original spelling, or null
     * when the item's channel cannot be identified.
     */
    public static String findChannelName(List<String> visibleTextLines,
                                         Collection<String> knownNames) {
        if (visibleTextLines == null || knownNames == null) {
            return null;
        }
        for (String line : visibleTextLines) {
            String normalizedLine = normalizeChannelName(line);
            if (normalizedLine.isEmpty()) {
                continue;
            }
            for (String known : knownNames) {
                if (normalizeChannelName(known).equals(normalizedLine)) {
                    return known;
                }
            }
        }
        return null;
    }

    /** Parses a multiline member editor field: one channel name per line. */
    public static LinkedHashSet<String> parseMemberLines(String text) {
        LinkedHashSet<String> members = new LinkedHashSet<>();
        if (text == null) {
            return members;
        }
        for (String line : text.split("\n")) {
            String name = line.trim();
            if (!name.isEmpty()) {
                members.add(name);
            }
        }
        return members;
    }

    /** Joins member names for the multiline editor field. */
    public static String joinMemberLines(Collection<String> members) {
        List<String> names = new ArrayList<>(members);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    public static Set<String> groupNames(Map<String, LinkedHashSet<String>> groups) {
        return groups.keySet();
    }
}
