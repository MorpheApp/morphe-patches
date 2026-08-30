/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2334
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.whitelist;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;

/**
 * Channels a {@link WhitelistType} feature is turned off for.
 * The channel name is stored next to the id, so the list still reads
 * as names when the video that added a channel is long gone.
 */
public final class ChannelWhitelist {

    private static final Map<WhitelistType, String> cachedJson =
            new EnumMap<>(WhitelistType.class);
    private static final Map<WhitelistType, Map<String, String>> cachedChannels =
            new EnumMap<>(WhitelistType.class);

    /**
     * @return An ordered map of channel id to channel name, which is empty if the name is unknown.
     */
    public static synchronized Map<String, String> getWhitelistedChannels(WhitelistType type) {
        String json = type.setting.get();
        // Reparsing only when the raw value changed also picks up setting imports.
        if (!json.equals(cachedJson.get(type))) {
            cachedJson.put(type, json);
            cachedChannels.put(type, parse(json));
        }
        return cachedChannels.get(type);
    }

    public static boolean isCurrentChannelWhitelisted(WhitelistType type) {
        return isChannelWhitelisted(type, VideoInformation.getChannelId());
    }

    public static boolean isChannelWhitelisted(WhitelistType type, @Nullable String channelId) {
        return channelId != null && !channelId.isEmpty()
                && getWhitelistedChannels(type).containsKey(channelId);
    }

    public static boolean isEmpty(WhitelistType type) {
        return getWhitelistedChannels(type).isEmpty();
    }

    /**
     * Adds the channel if it is not whitelisted yet, removes it if it is, and reports which happened.
     */
    public static void toggleChannel(WhitelistType type, String channelId, @Nullable String channelName) {
        if (isChannelWhitelisted(type, channelId)) {
            removeChannel(type, channelId);
            Utils.showToastShort(str("morphe_channel_whitelist_channel_removed"));
        } else {
            addChannel(type, channelId, channelName);
            Utils.showToastShort(str("morphe_channel_whitelist_channel_added"));
        }
    }

    public static void addChannel(WhitelistType type, String channelId, @Nullable String channelName) {
        if (channelId.isEmpty()) {
            return;
        }
        Map<String, String> channels = new LinkedHashMap<>(getWhitelistedChannels(type));
        if (channels.put(channelId, channelName == null ? "" : channelName) == null) {
            save(type, channels);
        }
    }

    public static void removeChannel(WhitelistType type, String channelId) {
        Map<String, String> channels = new LinkedHashMap<>(getWhitelistedChannels(type));
        if (channels.remove(channelId) != null) {
            save(type, channels);
        }
    }

    private static Map<String, String> parse(String json) {
        Map<String, String> channels = new LinkedHashMap<>();
        try {
            if (!json.isEmpty()) {
                JSONArray whitelist = new JSONArray(json);
                for (int i = 0, length = whitelist.length(); i < length; i++) {
                    JSONObject entry = whitelist.optJSONObject(i);
                    if (entry != null) {
                        String id = entry.optString("id");
                        if (!id.isEmpty()) {
                            channels.put(id, entry.optString("name"));
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Whitelist JSON parse error, resetting", ex);
        }
        return Collections.unmodifiableMap(channels);
    }

    private static void save(WhitelistType type, Map<String, String> channels) {
        try {
            JSONArray whitelist = new JSONArray();
            for (Map.Entry<String, String> channel : channels.entrySet()) {
                JSONObject entry = new JSONObject();
                entry.put("id", channel.getKey());
                entry.put("name", channel.getValue());
                whitelist.put(entry);
            }
            type.setting.save(whitelist.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "save failure", ex);
        }
    }
}
