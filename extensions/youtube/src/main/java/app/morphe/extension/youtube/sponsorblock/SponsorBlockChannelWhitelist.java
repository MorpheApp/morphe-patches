/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.sponsorblock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

public class SponsorBlockChannelWhitelist {

    public static boolean isCurrentChannelWhitelisted() {
        String channelId = VideoInformation.getChannelId();
        if (channelId.isEmpty()) return false;
        return isChannelWhitelisted(channelId);
    }

    public static boolean isChannelWhitelisted(String channelId) {
        try {
            JSONArray whitelist = getWhitelistJson();
            for (int i = 0, length =  whitelist.length(); i < length; i++) {
                JSONObject entry = whitelist.optJSONObject(i);
                if (entry != null && channelId.equals(entry.optString("id"))) {
                    return true;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "isChannelWhitelisted failure", ex);
        }
        return false;
    }

    public static void addChannel(String channelId, String channelName) {
        try {
            JSONArray whitelist = getWhitelistJson();
            for (int i = 0, length = whitelist.length(); i < length; i++) {
                JSONObject entry = whitelist.optJSONObject(i);
                if (entry != null && channelId.equals(entry.optString("id"))) return;
            }
            JSONObject entry = new JSONObject();
            entry.put("id", channelId);
            entry.put("name", channelName != null ? channelName : "");
            whitelist.put(entry);
            Settings.SB_CHANNEL_WHITELIST.save(whitelist.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "addChannel failure", ex);
        }
    }

    public static void removeChannel(String channelId) {
        try {
            JSONArray whitelist = getWhitelistJson();
            JSONArray newWhitelist = new JSONArray();
            for (int i = 0, length = whitelist.length(); i < length; i++) {
                JSONObject entry = whitelist.optJSONObject(i);
                if (entry != null && !channelId.equals(entry.optString("id"))) {
                    newWhitelist.put(entry);
                }
            }
            Settings.SB_CHANNEL_WHITELIST.save(newWhitelist.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "removeChannel failure", ex);
        }
    }

    /** Returns an ordered map of channelId → displayName (name if set, else empty string). */
    public static LinkedHashMap<String, String> getWhitelistedChannels() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try {
            JSONArray whitelist = getWhitelistJson();
            for (int i = 0, length = whitelist.length(); i < length; i++) {
                JSONObject entry = whitelist.optJSONObject(i);
                if (entry != null) {
                    String id = entry.optString("id");
                    if (!id.isEmpty()) {
                        result.put(id, entry.optString("name"));
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getWhitelistedChannels failure", ex);
        }
        return result;
    }

    private static JSONArray getWhitelistJson() {
        try {
            String json = Settings.SB_CHANNEL_WHITELIST.get();
            if (json.isEmpty()) return new JSONArray();
            return new JSONArray(json);
        } catch (JSONException ex) {
            Logger.printInfo(() -> "Whitelist JSON parse error, resetting", ex);
            return new JSONArray();
        }
    }
}
