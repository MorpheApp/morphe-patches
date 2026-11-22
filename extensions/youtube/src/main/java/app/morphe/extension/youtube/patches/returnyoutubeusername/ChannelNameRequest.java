/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5173
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.returnyoutubeusername;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRoutes;

/**
 * Fetches the channel name of a handle without an account. The handle is resolved to its
 * channel id, and the name is read from the metadata of the channel.
 * <p>
 * Fetched names are saved, so a handle that was shown before shows its name right away,
 * also after the app is restarted. Saved names are fetched again after a while, in case the
 * channel was renamed.
 * <p>
 * When a name is fetched, the texts that show the handle meanwhile are laid out again.
 */
final class ChannelNameRequest {

    private static final int MAX_CACHED_NAMES = 500;

    private static final int MAX_SAVED_NAMES = 2000;

    private static final long SAVED_NAME_MAX_AGE_MILLISECONDS = TimeUnit.DAYS.toMillis(7);

    private static final String PREFERENCES_NAME = "morphe_return_youtube_username";

    /**
     * Separates the time a name was fetched from the name in the saved value.
     */
    private static final char SAVED_VALUE_SEPARATOR = '|';

    private static final String CHANNEL_URL = "https://www.youtube.com/";

    private static final String[] CHANNEL_ID_PATH = {"endpoint", "browseEndpoint"};

    private static final String[] CHANNEL_NAME_PATH = {"metadata", "channelMetadataRenderer"};

    /**
     * Handle -> channel name. A null name means the handle or the channel was not found,
     * or the request failed.
     */
    private static final Map<String, CompletableFuture<String>> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(MAX_CACHED_NAMES, true));

    /**
     * Handle -> saved name that is not yet too old. Loaded once, off the main thread.
     */
    @Nullable
    private static CompletableFuture<Map<String, String>> savedNames;

    /**
     * A saved name, with the time it was fetched.
     */
    private record SavedName(String handle, long fetchTime) {
    }

    private ChannelNameRequest() {
    }

    /**
     * Loads the saved names off the main thread, if not yet loaded.
     * Called before they are needed, so they are usually loaded once a handle is shown.
     *
     * @return The saved names, once loaded.
     */
    static synchronized CompletableFuture<Map<String, String>> loadSavedNames() {
        if (savedNames == null) {
            savedNames = CompletableFuture.supplyAsync(ChannelNameRequest::readSavedNames,
                    Utils::runOnBackgroundThread);
        }
        return savedNames;
    }

    /**
     * Starts fetching the name if needed, and does not wait for it.
     */
    static CompletableFuture<String> fetch(String handle) {
        return cache.computeIfAbsent(handle, key -> {
            CompletableFuture<Map<String, String>> saved = loadSavedNames();

            Map<String, String> loaded = saved.getNow(null);
            if (loaded != null) {
                String name = loaded.get(key);
                if (name != null) {
                    return CompletableFuture.completedFuture(name);
                }
            }

            CompletableFuture<String> future = saved.thenApplyAsync(names -> {
                String name = names.get(key);
                return name != null ? name : fetchName(key);
            }, Utils::runOnBackgroundThread);
            future.thenRun(LithoRelayoutPatch::relayoutOutdatedTexts);
            return future;
        });
    }

    /**
     * @return If the name of the handle is being fetched.
     */
    static boolean isPending(String handle) {
        CompletableFuture<String> future = cache.get(handle);
        return future != null && !future.isDone();
    }

    private static SharedPreferences getPreferences() {
        return Utils.getContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /**
     * @return The saved names that are not too old. The other names are removed, and the oldest names
     *         are removed if there are too many.
     */
    private static Map<String, String> readSavedNames() {
        Map<String, String> names = new HashMap<>();
        try {
            SharedPreferences preferences = getPreferences();
            final long now = System.currentTimeMillis();
            List<String> removed = new ArrayList<>();
            List<SavedName> kept = new ArrayList<>();

            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                String handle = entry.getKey();
                String value = entry.getValue() instanceof String text ? text : "";
                final int separator = value.indexOf(SAVED_VALUE_SEPARATOR);
                final long fetchTime = separator > 0 ? parseLong(value.substring(0, separator)) : 0;
                if (now - fetchTime > SAVED_NAME_MAX_AGE_MILLISECONDS || fetchTime > now) {
                    removed.add(handle);
                    continue;
                }
                names.put(handle, value.substring(separator + 1));
                kept.add(new SavedName(handle, fetchTime));
            }

            if (kept.size() > MAX_SAVED_NAMES) {
                kept.sort(Comparator.comparingLong(SavedName::fetchTime));
                for (SavedName oldest : kept.subList(0, kept.size() - MAX_SAVED_NAMES)) {
                    names.remove(oldest.handle());
                    removed.add(oldest.handle());
                }
            }

            if (!removed.isEmpty()) {
                SharedPreferences.Editor editor = preferences.edit();
                for (String handle : removed) {
                    editor.remove(handle);
                }
                editor.apply();
            }

            Logger.printDebug(() -> "Loaded " + names.size() + " saved channel names, removed " + removed.size());
        } catch (Exception ex) {
            Logger.printException(() -> "readSavedNames failure", ex);
        }
        return names;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static void saveName(String handle, String name) {
        getPreferences().edit()
                .putString(handle, String.valueOf(System.currentTimeMillis()) + SAVED_VALUE_SEPARATOR + name)
                .apply();
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static String fetchName(String handle) {
        final long startTime = System.currentTimeMillis();

        // The endpoint rejects some countries, such as CN, so the default country is used.
        Locale locale = new Locale(Locale.getDefault().getLanguage());

        String channelId = optString(ChannelSearchRoutes.fetch(ChannelSearchRoutes.RESOLVE_CHANNEL_URL,
                ChannelSearchRoutes.createUrlBody(CHANNEL_URL + handle, locale)), CHANNEL_ID_PATH, "browseId");
        String name = channelId == null ? null : optString(ChannelSearchRoutes.fetch(ChannelSearchRoutes.GET_CHANNEL_NAME,
                ChannelSearchRoutes.createChannelBody(channelId, locale)), CHANNEL_NAME_PATH, "title");
        if (name != null) {
            saveName(handle, name);
        }

        Logger.printDebug(() -> "Fetched channel name of " + handle + ": " + name
                + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        return name;
    }

    /**
     * @return The trimmed text at the path of the response, or null if it is missing or empty.
     */
    @Nullable
    private static String optString(@Nullable JSONObject json, String[] path, String key) {
        for (String name : path) {
            if (json == null) {
                return null;
            }
            json = json.optJSONObject(name);
        }
        if (json == null) {
            return null;
        }
        String text = json.optString(key).trim();
        return text.isEmpty() ? null : text;
    }
}
