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
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.ref.WeakReference;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.music.jam.QueueCommand;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

@SuppressWarnings("unused")
public final class EnableAudioVideoSwitchPatch {

    public interface QueueAccess {
        void patch_atvEnqueue(byte[] command);
        Executor patch_atvExecutor();
        int patch_atvCurrentIndex();
        Object[] patch_atvItems();
        String patch_atvVideoId(Object item);
        void patch_atvRemove(Object item);
        boolean patch_atvLocal();
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

    private interface Condition {
        boolean ok();
    }

    private static final String OEMBED_URL = "https://www.youtube.com/oembed?format=json&url="
        + "https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D";
    private static final String SEARCH_URL =
        "https://music.youtube.com/youtubei/v1/search?key=AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI";
    private static final String USER_AGENT =
        "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14)";

    private static final String AUDIO_TYPE = "MUSIC_VIDEO_TYPE_ATV";
    private static final String VIDEO_TYPE = "MUSIC_VIDEO_TYPE_OMV";

    private static volatile boolean busy;

    private static volatile WeakReference<QueueAccess> queue =
        new WeakReference<>(null);

    private EnableAudioVideoSwitchPatch() {
    }

    public static void capture(QueueAccess access) {
        queue = new WeakReference<>(access);
    }

    public static void installAudioVideoSwitchInterceptor(View pill) {
        try {
            if (pill == null || !Settings.ENABLE_AUDIO_VIDEO_SWITCH.get()) return;
            Context context = pill.getContext();
            pill.post(() -> attachInterceptor(pill, pill, context));
        } catch (Exception ex) {
            Logger.printException(() -> "installAudioVideoSwitchInterceptor failed", ex);
        }
    }

    private static void attachInterceptor(View pill, View view, Context context) {
        try {
            if (view == null) return;
            final boolean[] claimed = {false};
            view.setOnTouchListener((target, event) -> {
                if (!Settings.ENABLE_AUDIO_VIDEO_SWITCH.get()) {
                    claimed[0] = false;
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        claimed[0] = isLeftHalf(pill, event);
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        claimed[0] = false;
                        break;
                    default:
                        break;
                }
                if (!claimed[0]) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) openCounterpart(context);
                return true;
            });
            if (view instanceof ViewGroup group) {
                for (int i = 0, childCound = group.getChildCount(); i < childCound; i++) {
                    attachInterceptor(pill, group.getChildAt(i), context);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "attachInterceptor failed", ex);
        }
    }

    private static boolean isLeftHalf(View pill, MotionEvent event) {
        int[] location = new int[2];
        pill.getLocationOnScreen(location);
        return event.getRawX() - location[0] < pill.getWidth() / 2f;
    }

    private static void openCounterpart(Context context) {
        if (busy) return;
        busy = true;

        Utils.runOnBackgroundThread(() -> {
            try {
                QueueAccess access = queue.get();
                String currentId = currentQueueVideoId(access);
                if (!isVideoId(currentId)) {
                    Logger.printDebug(() -> "audio/video switch: no current video");
                    return;
                }
                String targetId = resolveCounterpart(currentId);
                if (targetId == null || !isVideoId(targetId) || targetId.equals(currentId)) {
                    Logger.printDebug(
                            () -> "audio/video switch: no counterpart for " + currentId);
                    return;
                }
                if (!switchInQueue(context, access, currentId, targetId)) {
                    openWatch(context, targetId);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "openCounterpart failure", ex);
            } finally {
                busy = false;
            }
        });
    }

    /**
     * The queue's own current item is authoritative for switching; the watch-page videoId may lag
     * behind after an in-queue track change. Falls back to the watch page when no queue exists.
     */
    private static String currentQueueVideoId(QueueAccess access) {
        if (access != null) {
            try {
                String id = videoAt(access, access.patch_atvCurrentIndex());
                if (id != null) return id;
            } catch (Exception ex) {
                Logger.printException(() -> "currentQueueVideoId failure", ex);
            }
        }
        return VideoInformation.getVideoId();
    }

    /**
     * Returns false before playback advanced, so the caller can fall back to a watch deep link.
     */
    private static boolean switchInQueue(
            Context context,
            @Nullable QueueAccess access,
            String currentId,
            String targetId
    ) {
        if (access == null) return false;
        try {
            if (!access.patch_atvLocal()) {
                Logger.printDebug(() -> "audio/video switch: queue is not local");
                return false;
            }
            Executor executor = access.patch_atvExecutor();
            if (executor == null) return false;

            byte[] command = QueueCommand.encode(targetId, true);
            int[] queuedIndex = new int[] {-1};
            runOnQueueExecutor(executor, () -> {
                Object[] items = access.patch_atvItems();
                int index = access.patch_atvCurrentIndex();
                if (items == null || index < 0 || index >= items.length) return;
                if (!currentId.equals(access.patch_atvVideoId(items[index]))) {
                    Logger.printDebug(
                            () -> "audio/video switch: current queue item is not " + currentId);
                    return;
                }
                access.patch_atvEnqueue(command);
                queuedIndex[0] = index;
            });
            final int index = queuedIndex[0];
            if (index < 0) return false;

            if (!await(() -> targetId.equals(videoAt(access, index + 1)), 2_000)) {
                Logger.printDebug(() -> "audio/video switch: counterpart was not queued next");
                return false;
            }

            dispatchMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_NEXT);
            if (!await(
                    () -> access.patch_atvCurrentIndex() == index + 1
                            && targetId.equals(videoAt(access, index + 1)),
                    3_000
            )) {
                Logger.printDebug(
                        () -> "audio/video switch: playback did not advance to " + targetId);
                return false;
            }

            removePrevious(access, currentId);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "switchInQueue failure", ex);
            return false;
        }
    }

    private static void removePrevious(QueueAccess access, String currentId) {
        try {
            Executor executor = access.patch_atvExecutor();
            if (executor == null) return;
            runOnQueueExecutor(executor, () -> {
                Object[] items = access.patch_atvItems();
                int index = access.patch_atvCurrentIndex();
                Object previous = null;
                if (items != null) {
                    if (index - 1 >= 0 && index - 1 < items.length
                            && currentId.equals(access.patch_atvVideoId(items[index - 1]))) {
                        previous = items[index - 1];
                    } else {
                        for (Object item : items) {
                            if (currentId.equals(access.patch_atvVideoId(item))) {
                                previous = item;
                                break;
                            }
                        }
                    }
                }
                if (previous != null) {
                    access.patch_atvRemove(previous);
                } else {
                    Logger.printDebug(
                            () -> "audio/video switch: previous queue item not found");
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "removePrevious failure", ex);
        }
    }

    private static String videoAt(QueueAccess access, int index) {
        Object[] items = access.patch_atvItems();
        if (items == null || index < 0 || index >= items.length) return null;
        return access.patch_atvVideoId(items[index]);
    }

    /** Native queue mutations only ever run on YouTube Music's own queue executor. */
    private static void runOnQueueExecutor(Executor executor, Runnable task)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception ex) {
                Logger.printException(() -> "audio/video switch: queue task failed", ex);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            Logger.printDebug(() -> "audio/video switch: queue task timed out");
        }
    }

    private static boolean await(Condition condition, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            try {
                if (condition.ok()) return true;
            } catch (Exception ex) {
                Logger.printException(() -> "audio/video switch: queue poll failed", ex);
                return false;
            }
            if (SystemClock.uptimeMillis() >= deadline) return false;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Dispatches a media key event via AudioManager. This is the same mechanism used by Bluetooth
     * headsets and does not require any special permissions. Both ACTION_DOWN and ACTION_UP are
     * sent, as some players ignore events without a matching up event.
     */
    private static void dispatchMediaKeyEvent(Context context, int keyCode) {
        if (context.getSystemService(Context.AUDIO_SERVICE) instanceof AudioManager audioManager) {
            try {
                long now = SystemClock.uptimeMillis();
                audioManager.dispatchMediaKeyEvent(
                        new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                audioManager.dispatchMediaKeyEvent(
                        new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            } catch (Exception ex) {
                Logger.printException(() -> "dispatchMediaKeyEvent failure", ex);
            }
        }
    }

    private static void openWatch(Context context, String videoId) {
        Utils.runOnMainThread(() -> {
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
                Logger.printException(() -> "openWatch failure", ex);
            }
        });
    }

    @Nullable
    private static String resolveCounterpart(String currentId) throws Exception {
        JSONObject metadata = requestJson(OEMBED_URL + currentId, null);

        String title = metadata.optString("title", "");
        String author = metadata.optString("author_name", "");
        if (title.trim().isEmpty()) return null;

        JSONObject response = requestJson(SEARCH_URL, buildQuery(title, author));

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
        String resolved = best == null ? null : best.videoId;
        final double resolvedScore = bestScore;
        Logger.printDebug(() -> "audio/video switch: " + currentId + " (" + resolvedType
            + ") -> " + resolved + " score: " + resolvedScore);
        return resolved;
    }

    private static String buildQuery(String title, String author) {
        StringBuilder query = new StringBuilder(stripDecorations(title).trim());
        String channel = stripDecorations(author)
            .replaceFirst("(?i)\\s*-\\s*Topic\\s*$", "")
            .replaceFirst("(?i)\\s*VEVO\\s*$", "")
            .trim();
        if (!channel.isEmpty() && !channel.equalsIgnoreCase(query.toString())) {
            //noinspection SizeReplaceableByIsEmpty
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
            for (int i = 0, length = array.length(); i < length; i++) {
                String text = flatten(array.opt(i), depth + 1);
                if (text.isEmpty()) continue;
                //noinspection SizeReplaceableByIsEmpty
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

        final int status = connection.getResponseCode();
        if (status < 400) {
            return Requester.parseJSONObject(connection);
        }
        return new JSONObject(Requester.parseErrorString(connection));
    }

    private static boolean isVideoId(String value) {
        final int length = value.length();
        if (length != 11) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '-';
            if (!valid) return false;
        }
        return true;
    }
}
