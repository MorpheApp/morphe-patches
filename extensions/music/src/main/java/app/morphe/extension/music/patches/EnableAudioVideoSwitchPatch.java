/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class EnableAudioVideoSwitchPatch {

    private static final String OEMBED_URL = "https://www.youtube.com/oembed?format=json&url="
        + "https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D";
    private static final String SEARCH_URL =
        "https://music.youtube.com/youtubei/v1/search?key=AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI";
    private static final String USER_AGENT =
        "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14)";

    private static final String AUDIO_TYPE = "MUSIC_VIDEO_TYPE_ATV";
    private static final String VIDEO_TYPE = "MUSIC_VIDEO_TYPE_OMV";

    private static volatile boolean busy;

    private EnableAudioVideoSwitchPatch() {
    }

    public static void installAudioVideoSwitchInterceptor(View pill) {
        try {
            if (pill == null || !Settings.ENABLE_AUDIO_VIDEO_SWITCH.get()) return;
            final Context context = pill.getContext();
            pill.post(() -> attachInterceptor(pill, context));
        } catch (Exception ex) {
            Logger.printException(() -> "installAudioVideoSwitchInterceptor failed", ex);
        }
    }

    private static void attachInterceptor(View view, Context context) {
        try {
            if (view == null) return;
            view.setOnTouchListener((target, event) -> {
                if (!Settings.ENABLE_AUDIO_VIDEO_SWITCH.get()) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) openCounterpart(context);
                return true;
            });
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    attachInterceptor(group.getChildAt(i), context);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "attachInterceptor failed", ex);
        }
    }

    private static void openCounterpart(final Context context) {
        final String currentId = VideoInformation.getVideoId();
        if (!isVideoId(currentId) || busy) return;

        busy = true;
        new Thread(() -> {
            try {
                String targetId = resolveCounterpart(currentId);
                if (isVideoId(targetId) && !targetId.equals(currentId)) {
                    openWatch(context, targetId);
                } else {
                    Logger.printDebug(() -> "audio/video switch: no counterpart for " + currentId);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "audio/video switch failed", ex);
            } finally {
                busy = false;
            }
        }, "morphe-av-switch").start();
    }

    private static void openWatch(final Context context, final String videoId) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://music.youtube.com/watch?v=" + videoId)
                );
                intent.setPackage(context.getPackageName());
                if (!(context instanceof Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(intent);
                Logger.printDebug(() -> "audio/video switch: opened " + videoId);
            } catch (Exception ex) {
                Logger.printException(() -> "audio/video switch: could not open counterpart", ex);
            }
        });
    }

    private static String resolveCounterpart(String currentId) throws Exception {
        JSONObject metadata = requestJson(OEMBED_URL + currentId, null);
        if (metadata == null) return null;

        String title = metadata.optString("title", "");
        String author = metadata.optString("author_name", "");
        if (title.trim().isEmpty()) return null;

        JSONObject response = requestJson(SEARCH_URL, buildQuery(title, author));
        if (response == null) return null;

        Map<String, Item> items = new LinkedHashMap<>();
        collectItems(response, "", items);

        String currentType = null;
        for (Item item : items.values()) {
            if (!currentId.equals(item.videoId) || item.type.isEmpty()) continue;
            currentType = item.type;
            break;
        }
        if (!AUDIO_TYPE.equals(currentType) && !VIDEO_TYPE.equals(currentType)) {
            if (author.trim().endsWith("- Topic")) currentType = AUDIO_TYPE;
        }
        if (!AUDIO_TYPE.equals(currentType) && !VIDEO_TYPE.equals(currentType)) return null;

        String wantedType = AUDIO_TYPE.equals(currentType) ? VIDEO_TYPE : AUDIO_TYPE;
        String resolvedType = currentType;
        Item best = null;
        double bestScore = -1.0d;
        for (Item item : items.values()) {
            if (!wantedType.equals(item.type) || currentId.equals(item.videoId)) continue;
            double itemScore = score(title, item.title);
            if (itemScore > bestScore) {
                bestScore = itemScore;
                best = item;
            }
        }
        final String resolved = best == null ? null : best.videoId;
        final double resolvedScore = bestScore;
        Logger.printDebug(() -> "audio/video switch: " + currentId + " (" + resolvedType
            + ") -> " + resolved + " score=" + resolvedScore);
        return resolved;
    }

    private static String buildQuery(String title, String author) {
        StringBuilder query = new StringBuilder(stripDecorations(title).trim());
        String channel = stripDecorations(author)
            .replaceFirst("(?i)\\s*-\\s*Topic\\s*$", "")
            .replaceFirst("(?i)\\s*VEVO\\s*$", "")
            .trim();
        if (!channel.isEmpty() && !channel.equalsIgnoreCase(query.toString())) {
            if (query.length() > 0) query.append(' ');
            query.append(channel);
        }
        return query.toString().replaceAll("\\s+", " ").trim();
    }

    private static String stripDecorations(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", " ");
    }

    private static double score(String expected, String actual) {
        String left = normalize(expected);
        String right = normalize(actual);
        if (left.isEmpty() || right.isEmpty()) return 0.0d;
        return normalizeScore(left, right);
    }

    private static double normalizeScore(String left, String right) {
        if (left.contains(right) || right.contains(left)) return 1.0d;
        Set<String> a = new LinkedHashSet<>(Arrays.asList(left.split(" ")));
        Set<String> b = new LinkedHashSet<>(Arrays.asList(right.split(" ")));
        if (a.isEmpty() || b.isEmpty()) return 0.0d;
        int common = 0;
        for (String token : a) {
            if (b.contains(token)) common++;
        }
        return (double) common / Math.max(1, Math.min(a.size(), b.size()));
    }

    private static String normalize(String value) {
        String text = stripDecorations(value).toLowerCase(Locale.ROOT);
        text = text.replaceAll("[^a-z0-9 ]+", " ");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static void collectItems(Object node, String nearestTitle, Map<String, Item> out) {
        if (node instanceof JSONObject object) {
            String ownTitle = directTitle(object);
            if (!ownTitle.isEmpty()) nearestTitle = ownTitle;

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if ("watchEndpoint".equals(key) && value instanceof JSONObject endpoint) {
                    addEndpoint(endpoint, nearestTitle, out);
                } else {
                    collectItems(value, nearestTitle, out);
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                collectItems(array.opt(i), nearestTitle, out);
            }
        }
    }

    private static void addEndpoint(JSONObject endpoint, String nearestTitle, Map<String, Item> out) {
        String videoId = endpoint.optString("videoId", "");
        if (!isVideoId(videoId)) return;
        String type = musicVideoType(endpoint);

        Item existing = out.get(videoId);
        if (existing == null) {
            out.put(videoId, new Item(videoId, type, nearestTitle));
            return;
        }
        if (existing.type.isEmpty()) existing.type = type;
        if (existing.title.isEmpty()) existing.title = nearestTitle;
    }

    private static String musicVideoType(JSONObject endpoint) {
        JSONObject configs = endpoint.optJSONObject("watchEndpointMusicSupportedConfigs");
        if (configs == null) return "";
        JSONObject musicConfig = configs.optJSONObject("watchEndpointMusicConfig");
        return musicConfig == null ? "" : musicConfig.optString("musicVideoType", "");
    }

    private static String directTitle(JSONObject object) {
        String[] keys = {"title", "flexColumns", "primaryText", "secondaryText"};
        for (String key : keys) {
            if (!object.has(key)) continue;
            String text = flatten(object.opt(key), 0);
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static String flatten(Object value, int depth) {
        if (depth > 8) return "";
        if (value instanceof String string) return string;
        if (value instanceof JSONObject object) {
            if (object.has("simpleText")) return object.optString("simpleText", "");
            for (String key : new String[]{"runs", "flexColumns", "text"}) {
                if (!object.has(key)) continue;
                String text = flatten(object.opt(key), depth + 1);
                if (!text.isEmpty()) return text;
            }
            return "";
        }
        if (value instanceof JSONArray array) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String text = flatten(array.opt(i), depth + 1);
                if (text.isEmpty()) continue;
                if (builder.length() > 0) builder.append(' ');
                builder.append(text);
            }
            return builder.toString();
        }
        return "";
    }

    private static JSONObject requestJson(String url, String searchQuery) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);

        if (searchQuery == null) {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        } else {
            connection.setRequestProperty("User-Agent", USER_AGENT);
            byte[] body = new JSONObject()
                .put("query", searchQuery)
                .put("context", new JSONObject()
                    .put("client", new JSONObject()
                        .put("clientName", "ANDROID_MUSIC")
                        .put("clientVersion", "7.27.52")
                        .put("androidSdkVersion", 34)
                        .put("hl", "en")
                        .put("gl", "US")))
                .toString()
                .getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }

        int status = connection.getResponseCode();
        try (InputStream input = status < 400
            ? connection.getInputStream()
            : connection.getErrorStream()) {
            if (input == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) != -1) buffer.write(chunk, 0, read);
            if (status >= 400) return null;
            return new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isVideoId(String value) {
        if (value == null || value.length() != 11) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '-';
            if (!valid) return false;
        }
        return true;
    }

    private static final class Item {
        final String videoId;
        String type;
        String title;

        Item(String videoId, String type, String title) {
            this.videoId = videoId;
            this.type = type;
            this.title = title;
        }
    }
}
