/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.subscriptiongroups;

import java.util.Objects;

/**
 * One channel in a subscription group (or in the known-channels list),
 * identified by the strongest identifier available:
 *
 * channel id (UC...) &gt; @handle &gt; display name.
 *
 * Entries captured on a channel page carry the handle (and the display
 * name); entries typed by hand in the management dialog carry only a
 * display name until they can be matched with a captured entry.
 * Equality is by {@link #key()}, so a name-only entry and a handle
 * entry for the same channel are NOT automatically the same entry;
 * merging happens explicitly when names/handles are resolved against
 * the known-channels list (see SubscriptionGroupFilter).
 */
public final class ChannelEntry {

    public final String id;
    public final String handle;
    public final String name;

    public ChannelEntry(String id, String handle, String name) {
        this.id = id == null ? "" : id.trim();
        this.handle = handle == null ? "" : handle.trim();
        this.name = name == null ? "" : name.trim();
    }

    public static ChannelEntry nameOnly(String name) {
        return new ChannelEntry("", "", name);
    }

    /** Stable identity: id first, then handle, then normalized name. */
    public String key() {
        if (!id.isEmpty()) {
            return "id:" + id;
        }
        if (!handle.isEmpty()) {
            return "handle:" + SubscriptionGroupFilter.normalizeChannelName(handle);
        }
        return "name:" + SubscriptionGroupFilter.normalizeChannelName(name);
    }

    /** What to show in lists: the display name, falling back to the handle/id. */
    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        }
        if (!handle.isEmpty()) {
            return handle;
        }
        return id;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChannelEntry entry && key().equals(entry.key());
    }

    @Override
    public int hashCode() {
        return Objects.hash(key());
    }

    @Override
    public String toString() {
        return displayName();
    }
}
