/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Unauthenticated YouTube InnerTube helper to search channels by keyword, resolve channel URLs
 * or @handles, fetch recent video titles, and asynchronously load circular channel avatars.
 */
public final class GhostChannelSearchApi {

    private static final String YT_API_BASE = "https://youtubei.googleapis.com/youtubei/v1/";
    private static final String WEB_CLIENT_NAME = "WEB";
    private static final String WEB_CLIENT_VERSION = "2.20260401.00.00";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    // YouTube search filter parameter restricting results strictly to channels.
    private static final String CHANNEL_FILTER_PARAMS = "EgIQAg%3D%3D";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RECENT_VIDEOS = 3;

    private static final Pattern CHANNEL_ID_PATTERN = Pattern.compile("(UC[a-zA-Z0-9_-]{22})");
    private static final Pattern HANDLE_URL_PATTERN = Pattern.compile("youtube\\.com/(@[a-zA-Z0-9._-]+)");

    private static final Map<String, Bitmap> avatarCache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 64;
                }
            }
    );

    public interface SearchCallback {
        void onResults(@NonNull List<GhostChannelStore.ChannelEntry> results, boolean error);
    }

    public interface DetailCallback {
        void onDetails(@Nullable GhostChannelStore.ChannelEntry updated);
    }

    private GhostChannelSearchApi() {
    }

    /**
     * Searches YouTube channels by name, @handle, channel ID (UC...), or full YouTube channel URL.
     */
    public static void searchAsync(@NonNull String rawQuery, @NonNull SearchCallback callback) {
        String query = rawQuery.trim();
        if (query.isEmpty()) {
            Utils.runOnMainThread(() -> callback.onResults(Collections.emptyList(), false));
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            try {
                List<GhostChannelStore.ChannelEntry> results = new ArrayList<>();

                // 1. Direct URL, UC channel ID, or @handle resolution.
                String directId = extractChannelId(query);
                if (directId != null) {
                    GhostChannelStore.ChannelEntry entry = fetchChannelDetails(directId);
                    if (entry != null) {
                        results.add(entry);
                    }
                } else if (looksLikeUrlOrHandle(query)) {
                    String resolvedId = resolveUrlToChannelId(normalizeToChannelUrl(query));
                    if (resolvedId != null) {
                        GhostChannelStore.ChannelEntry entry = fetchChannelDetails(resolvedId);
                        if (entry != null) {
                            results.add(entry);
                        }
                    }
                }

                // 2. If not resolved directly (or keyword search), query YouTube channel search.
                if (results.isEmpty()) {
                    results.addAll(searchChannelsByKeyword(query));
                }

                List<GhostChannelStore.ChannelEntry> finalList = results;
                Utils.runOnMainThread(() -> callback.onResults(finalList, false));
            } catch (Exception ex) {
                Logger.printException(() -> "GhostChannelSearchApi.searchAsync failed", ex);
                Utils.runOnMainThread(() -> callback.onResults(Collections.emptyList(), true));
            }
        });
    }

    /**
     * Fetches full channel metadata and up to 3 recent video titles in the background.
     */
    public static void fetchDetailsAsync(@NonNull GhostChannelStore.ChannelEntry base, @NonNull DetailCallback callback) {
        Utils.runOnBackgroundThread(() -> {
            try {
                String channelId = base.channelId();
                if (channelId.isEmpty() && !base.handle().isEmpty()) {
                    channelId = resolveUrlToChannelId(normalizeToChannelUrl(base.handle()));
                }
                if ((channelId == null || channelId.isEmpty()) && !base.name().isEmpty()) {
                    List<GhostChannelStore.ChannelEntry> found = searchChannelsByKeyword(base.name());
                    if (!found.isEmpty()) {
                        channelId = found.get(0).channelId();
                    }
                }
                if (channelId == null || channelId.isEmpty()) {
                    Utils.runOnMainThread(() -> callback.onDetails(null));
                    return;
                }
                GhostChannelStore.ChannelEntry fresh = fetchChannelDetails(channelId);
                if (fresh != null) {
                    GhostChannelStore.ChannelEntry merged = new GhostChannelStore.ChannelEntry(
                            fresh.channelId(),
                            !fresh.name().isEmpty() ? fresh.name() : base.name(),
                            !fresh.handle().isEmpty() ? fresh.handle() : base.handle(),
                            !fresh.avatarUrl().isEmpty() ? fresh.avatarUrl() : base.avatarUrl(),
                            !fresh.subscriberText().isEmpty() ? fresh.subscriberText() : base.subscriberText(),
                            !fresh.description().isEmpty() ? fresh.description() : base.description(),
                            !fresh.recentVideos().isEmpty() ? fresh.recentVideos() : base.recentVideos(),
                            base.addedAtMs() > 0 ? base.addedAtMs() : System.currentTimeMillis()
                    );
                    Utils.runOnMainThread(() -> callback.onDetails(merged));
                } else {
                    Utils.runOnMainThread(() -> callback.onDetails(null));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "fetchDetailsAsync failed", ex);
                Utils.runOnMainThread(() -> callback.onDetails(null));
            }
        });
    }

    /**
     * Loads and caches a circular channel avatar into an ImageView.
     */
    public static void loadAvatar(@NonNull ImageView target, @Nullable String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isEmpty()) {
            return;
        }
        String normalized = avatarUrl.startsWith("//") ? "https:" + avatarUrl : avatarUrl;
        target.setTag(normalized);
        Bitmap cached = avatarCache.get(normalized);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        WeakReference<ImageView> ref = new WeakReference<>(target);
        Utils.runOnBackgroundThread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(normalized).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                try (InputStream in = conn.getInputStream()) {
                    Bitmap raw = BitmapFactory.decodeStream(in);
                    if (raw == null) return;
                    Bitmap circle = toCircularBitmap(raw);
                    avatarCache.put(normalized, circle);
                    Utils.runOnMainThread(() -> {
                        ImageView iv = ref.get();
                        if (iv != null && normalized.equals(iv.getTag())) {
                            iv.setImageBitmap(circle);
                        }
                    });
                } finally {
                    conn.disconnect();
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Nullable
    static String extractChannelId(@NonNull String input) {
        Matcher m = CHANNEL_ID_PATTERN.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    static boolean looksLikeUrlOrHandle(@NonNull String input) {
        String trimmed = input.trim();
        return trimmed.startsWith("@")
                || trimmed.startsWith("http://")
                || trimmed.startsWith("https://")
                || trimmed.contains("youtube.com/")
                || trimmed.contains("youtu.be/");
    }

    static String normalizeToChannelUrl(@NonNull String input) {
        String trimmed = input.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("@")) {
            return "https://www.youtube.com/" + trimmed;
        }
        if (trimmed.contains("youtube.com/")) {
            return "https://" + trimmed;
        }
        Matcher m = HANDLE_URL_PATTERN.matcher(trimmed);
        if (m.find()) {
            return "https://www.youtube.com/" + m.group(1);
        }
        return "https://www.youtube.com/@" + trimmed;
    }

    @Nullable
    private static String resolveUrlToChannelId(@NonNull String url) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", createWebContext());
            body.put("url", url);
            JSONObject resp = postJson("navigation/resolve_url?prettyPrint=false", body);
            if (resp == null) return null;
            JSONObject endpoint = resp.optJSONObject("endpoint");
            if (endpoint == null) return null;
            JSONObject browse = endpoint.optJSONObject("browseEndpoint");
            if (browse == null) return null;
            String id = browse.optString("browseId", "");
            return id.startsWith("UC") ? id : null;
        } catch (Exception ex) {
            Logger.printDebug(() -> "resolveUrlToChannelId failed: " + ex.getMessage());
            return null;
        }
    }

    @NonNull
    private static List<GhostChannelStore.ChannelEntry> searchChannelsByKeyword(@NonNull String query) {
        List<GhostChannelStore.ChannelEntry> out = new ArrayList<>();
        try {
            JSONObject body = new JSONObject();
            body.put("context", createWebContext());
            body.put("query", query);
            body.put("params", CHANNEL_FILTER_PARAMS);
            JSONObject resp = postJson("search?prettyPrint=false", body);
            if (resp == null) return out;
            collectChannelRenderers(resp, out);
        } catch (Exception ex) {
            Logger.printDebug(() -> "searchChannelsByKeyword failed: " + ex.getMessage());
        }
        return out;
    }

    @Nullable
    private static GhostChannelStore.ChannelEntry fetchChannelDetails(@NonNull String channelId) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", createWebContext());
            body.put("browseId", channelId);
            // Videos tab params ("videos") so recent uploads are directly included.
            body.put("params", "EgZ2aWRlb3PyBgQKAjoA");
            JSONObject resp = postJson("browse?prettyPrint=false", body);
            if (resp == null) return null;

            String name = "";
            String handle = "";
            String avatar = "";
            String subs = "";
            String desc = "";

            JSONObject metadata = resp.optJSONObject("metadata");
            if (metadata != null) {
                JSONObject cm = metadata.optJSONObject("channelMetadataRenderer");
                if (cm != null) {
                    name = cm.optString("title", "");
                    desc = cm.optString("description", "");
                    String vanity = cm.optString("vanityChannelUrl", "");
                    Matcher hm = HANDLE_URL_PATTERN.matcher(vanity);
                    if (hm.find()) {
                        handle = hm.group(1);
                    }
                    avatar = extractBestThumbnail(cm.optJSONObject("avatar"));
                }
            }

            // Fallback / richer metadata from pageHeaderViewModel or c4TabbedHeaderRenderer.
            JSONObject header = resp.optJSONObject("header");
            if (header != null) {
                JSONObject c4 = header.optJSONObject("c4TabbedHeaderRenderer");
                if (c4 != null) {
                    if (name.isEmpty()) name = c4.optString("title", "");
                    if (avatar.isEmpty()) avatar = extractBestThumbnail(c4.optJSONObject("avatar"));
                    if (subs.isEmpty()) subs = readText(c4.optJSONObject("subscriberCountText"));
                }
                if (subs.isEmpty() || handle.isEmpty()) {
                    String[] hs = new String[]{handle, subs};
                    extractFromPageHeader(header, hs);
                    handle = hs[0];
                    subs = hs[1];
                }
            }

            List<String> recentVideos = new ArrayList<>();
            collectVideoTitles(resp, recentVideos, MAX_RECENT_VIDEOS);

            if (name.isEmpty()) {
                name = channelId;
            }
            return new GhostChannelStore.ChannelEntry(
                    channelId,
                    name,
                    handle,
                    avatar,
                    subs,
                    trimDescription(desc),
                    recentVideos,
                    System.currentTimeMillis()
            );
        } catch (Exception ex) {
            Logger.printDebug(() -> "fetchChannelDetails failed: " + ex.getMessage());
            return null;
        }
    }

    private static void extractFromPageHeader(JSONObject header, String[] outHandleSubs) {
        try {
            JSONObject p = header.optJSONObject("pageHeaderRenderer");
            if (p == null) return;
            JSONObject content = p.optJSONObject("content");
            if (content == null) return;
            JSONObject vm = content.optJSONObject("pageHeaderViewModel");
            if (vm == null) return;
            JSONObject meta = vm.optJSONObject("metadata");
            if (meta == null) return;
            JSONObject rowsObj = meta.optJSONObject("contentMetadataViewModel");
            if (rowsObj == null) return;
            JSONArray rows = rowsObj.optJSONArray("metadataRows");
            if (rows == null) return;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                JSONArray parts = row.optJSONArray("metadataParts");
                if (parts == null) continue;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    if (part == null) continue;
                    JSONObject textObj = part.optJSONObject("text");
                    String text = textObj != null ? textObj.optString("content", "") : "";
                    if (text.startsWith("@") && outHandleSubs[0].isEmpty()) {
                        outHandleSubs[0] = text;
                    } else if (!text.isEmpty() && !text.startsWith("@") && outHandleSubs[1].isEmpty()) {
                        outHandleSubs[1] = text;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void collectChannelRenderers(Object node, List<GhostChannelStore.ChannelEntry> out) {
        if (out.size() >= 15 || node == null) return;
        if (node instanceof JSONObject obj) {
            JSONObject cr = obj.optJSONObject("channelRenderer");
            if (cr != null) {
                String id = cr.optString("channelId", "");
                String title = readText(cr.optJSONObject("title"));
                if (!id.isEmpty() && !title.isEmpty()) {
                    String avatar = extractBestThumbnail(cr.optJSONObject("thumbnail"));
                    String subs = readText(cr.optJSONObject("videoCountText"));
                    String subOrHandle = readText(cr.optJSONObject("subscriberCountText"));
                    String handle = "";
                    String subscriberText = "";
                    if (subOrHandle.startsWith("@")) {
                        handle = subOrHandle;
                        subscriberText = subs;
                    } else {
                        subscriberText = subOrHandle;
                        if (subs.startsWith("@")) handle = subs;
                    }
                    String desc = trimDescription(readText(cr.optJSONObject("descriptionSnippet")));
                    out.add(new GhostChannelStore.ChannelEntry(
                            id, title, handle, avatar, subscriberText, desc,
                            Collections.emptyList(), System.currentTimeMillis()
                    ));
                }
                return;
            }
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    collectChannelRenderers(obj.opt(k), out);
                }
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                collectChannelRenderers(arr.opt(i), out);
            }
        }
    }

    /**
     * Pure helper for deterministic unit testing of recent video title extraction.
     */
    @NonNull
    static List<String> extractVideoTitlesFromJson(@Nullable String rawJson, int limit) {
        List<String> out = new ArrayList<>();
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return out;
        }
        try {
            collectVideoTitles(new JSONObject(rawJson), out, limit);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void collectVideoTitles(Object node, List<String> out, int limit) {
        if (out.size() >= limit || node == null) return;
        if (node instanceof JSONObject obj) {
            // Modern YouTube WEB channel videos tab format: richItemRenderer -> content -> lockupViewModel
            JSONObject lockupMeta = obj.optJSONObject("lockupMetadataViewModel");
            if (lockupMeta != null) {
                String t = readTitleContent(lockupMeta.optJSONObject("title"));
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                }
                return;
            }
            JSONObject vr = obj.optJSONObject("videoRenderer");
            if (vr != null) {
                String t = readText(vr.optJSONObject("title"));
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                }
                return;
            }
            JSONObject gridVr = obj.optJSONObject("gridVideoRenderer");
            if (gridVr != null) {
                String t = readText(gridVr.optJSONObject("title"));
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                }
                return;
            }
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    collectVideoTitles(obj.opt(names.optString(i)), out, limit);
                    if (out.size() >= limit) return;
                }
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                collectVideoTitles(arr.opt(i), out, limit);
                if (out.size() >= limit) return;
            }
        }
    }

    @NonNull
    private static String readTitleContent(@Nullable JSONObject titleObj) {
        if (titleObj == null) return "";
        String content = titleObj.optString("content", "");
        if (!content.isEmpty()) return content;
        return readText(titleObj);
    }

    @NonNull
    private static String readText(@Nullable JSONObject obj) {
        if (obj == null) return "";
        String simple = obj.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = obj.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) sb.append(r.optString("text", ""));
        }
        return sb.toString();
    }

    @NonNull
    private static String extractBestThumbnail(@Nullable JSONObject thumbObj) {
        if (thumbObj == null) return "";
        JSONArray list = thumbObj.optJSONArray("thumbnails");
        if (list == null || list.length() == 0) return "";
        // Prefer a mid-sized square avatar (~88-176px) to save bandwidth.
        String chosen = "";
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "");
            if (!url.isEmpty()) {
                chosen = url;
                if (item.optInt("width", 0) >= 88) break;
            }
        }
        return chosen.startsWith("//") ? "https:" + chosen : chosen;
    }

    @NonNull
    private static String trimDescription(@Nullable String desc) {
        if (desc == null) return "";
        String collapsed = desc.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 140 ? collapsed.substring(0, 137) + "..." : collapsed;
    }

    private static JSONObject createWebContext() throws Exception {
        Locale locale = Requester.getAppLocale();
        String hl = locale.getLanguage().isEmpty() ? "en" : locale.getLanguage();
        String gl = locale.getCountry().isEmpty() ? "US" : locale.getCountry();

        JSONObject client = new JSONObject();
        client.put("clientName", WEB_CLIENT_NAME);
        client.put("clientVersion", WEB_CLIENT_VERSION);
        client.put("hl", hl);
        client.put("gl", gl);

        JSONObject ctx = new JSONObject();
        ctx.put("client", client);
        return ctx;
    }

    @Nullable
    private static JSONObject postJson(@NonNull String endpoint, @NonNull JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(YT_API_BASE + endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Origin", "https://www.youtube.com");

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            return null;
        }

        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            conn.disconnect();
        }
    }

    private static Bitmap toCircularBitmap(@NonNull Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = size / 2f;
        canvas.drawCircle(r, r, r, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        int left = (src.getWidth() - size) / 2;
        int top = (src.getHeight() - size) / 2;
        canvas.drawBitmap(src, new Rect(left, top, left + size, top + size), new Rect(0, 0, size, size), paint);
        return out;
    }
}
