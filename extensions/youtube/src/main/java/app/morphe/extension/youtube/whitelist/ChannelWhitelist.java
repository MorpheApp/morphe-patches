/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2334
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.whitelist;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.drawable.Drawable;
import android.util.Pair;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.requests.ChannelIdRequest;
import app.morphe.extension.youtube.settings.Settings;

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

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    public static int addFlyoutButtons(Object flyoutPanel, int nextButtonIndex, boolean isMyTabHistoryFlyout) {
        if (isMyTabHistoryFlyout || !isPatchIncluded()) {
            return nextButtonIndex;
        }

        if (Settings.ADS_CHANNEL_WHITELIST_FLYOUT_MENU.get()) {
            Drawable icon = ResourceUtils.getDrawable(Utils.appIsUsingBoldIcons()
                    ? "morphe_settings_screen_01_ads_bold" : "morphe_settings_screen_01_ads");
            nextButtonIndex = addWhitelistButton(flyoutPanel, WhitelistType.ADS, icon, nextButtonIndex);
        }

        if (Settings.PLAYBACK_SPEED_CHANNEL_WHITELIST_FLYOUT_MENU.get()) {
            Drawable icon = ResourceUtils.getDrawable(Utils.appIsUsingBoldIcons()
                    ? "morphe_settings_screen_12_video_bold" : "morphe_settings_screen_12_video");
            nextButtonIndex = addWhitelistButton(flyoutPanel, WhitelistType.PLAYBACK_SPEED, icon, nextButtonIndex);
        }

        return nextButtonIndex;
    }

    private static int addWhitelistButton(
            Object flyoutPanel,
            WhitelistType type,
            Drawable icon,
            int index
    ) {
        ChannelIdRequest request = FlyoutUtils.getFlyoutChannelIdRequest();
        if (request == null) {
            // Menus without a video of their own, such as the player settings, belong to the playing video.
            String currentChannelId = VideoInformation.getChannelId();
            String currentChannelName = VideoInformation.getChannelName();
            if (currentChannelId.isEmpty()) {
                return index;
            }

            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    icon,
                    type.getFlyoutTitle(isChannelWhitelisted(type, currentChannelId)),
                    v -> {
                        toggleChannel(type, currentChannelId, currentChannelName);
                        FlyoutUtils.dismissFlyout();
                    },
                    index
            );
        }

        // The button is disabled until the channel is loaded, and is enabled right away if it already is.
        return FlyoutUtils.addFlyoutButton(
                flyoutPanel,
                icon,
                type.getFlyoutTitle(false),
                v -> {
                    // The button is only enabled once the request is done, so this does not wait.
                    Pair<String, String> channelInfo = request.getChannelInfo();
                    if (channelInfo != null) {
                        toggleChannel(type, channelInfo.second, channelInfo.first);
                    }

                    FlyoutUtils.dismissFlyout();
                },
                index,
                new FlyoutUtils.FlyoutButtonResource(request.getFuture(), text -> {
                    Pair<String, String> channelInfo = request.getChannelInfo();
                    String channelId = channelInfo == null ? "" : channelInfo.second;
                    if (channelId.isEmpty()) {
                        return false;
                    }

                    if (text != null) {
                        text.setText(type.getFlyoutTitle(isChannelWhitelisted(type, channelId)));
                    }
                    return true;
                })
        );
    }

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
            Utils.showSnackbarShort(str("morphe_channel_whitelist_channel_removed"));
        } else {
            Map<String, String> channels = new LinkedHashMap<>(getWhitelistedChannels(type));
            if (!channelId.isEmpty() && channels.put(channelId, channelName == null ? "" : channelName) == null) {
                save(type, channels);
            }
            Utils.showSnackbarShort(str("morphe_channel_whitelist_channel_added"));
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
