/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3714
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;
import com.reddit.domain.model.LinkMedia;
import com.reddit.domain.model.Preview;
import com.reddit.domain.model.RedditVideo;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Reddit plays Redgifs posts from its own copy of the video, which has no audio.
 * This patch plays the original Redgifs video instead.
 * <p>
 * Video player urls are created without the post, so the Reddit video of each Redgifs post
 * is recorded when the post is loaded, and replaced when a player url is created.
 * <p>
 * The Redgifs video url needs an API call, which starts when the post is loaded.
 * If it hasn't finished when the player url is created, the player is given a placeholder url,
 * which is resolved when the player loads it (on a loader thread).
 */
@SuppressWarnings("unused")
public final class RedgifsAudioPatch {

    private static final String API_URL = "https://api.redgifs.com/v2/";

    /**
     * Redgifs binds temporary tokens to the user agent that requested them.
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android) Morphe-Reddit";

    /**
     * Tokens are valid for 24 hours.
     */
    private static final long TOKEN_LIFETIME_MS = TimeUnit.HOURS.toMillis(23);

    private static final String PLACEHOLDER_HOST = "media.redgifs.com";
    private static final String PLACEHOLDER_PATH = "/morphe-pending/";
    private static final String PLACEHOLDER_FALLBACK_PARAMETER = "fallback";

    /**
     * How long the player waits for the Redgifs video url, before playing Reddit's video.
     */
    private static final long PLAYER_WAIT_MS = 8_000;
    private static final long DOWNLOAD_WAIT_MS = 5_000;
    private static final long FAILED_LOOKUP_RETRY_MS = 30_000;
    private static final int TIMEOUT_MS = 5_000;
    private static final int MAX_CACHE_SIZE = 500;

    private static final Pattern REDGIFS_ID_PATTERN = Pattern.compile("^[a-z0-9]+$");

    private static final String METADATA_CELL_ID_PREFIX = "MetadataCell-";

    /**
     * Redgifs id to lookup.
     */
    private static final Map<String, Lookup> lookups = createCache();

    /**
     * Feed post id (t3_...) to Redgifs id.
     */
    private static final Map<String, String> postIdToRedgifsId = createCache();

    /**
     * Reddit video id to Redgifs id.
     */
    private static final Map<String, String> redditVideoIdToRedgifsId = createCache();

    @Nullable
    private static String token;
    private static long tokenExpires;

    private static final class Lookup {
        final String id;
        final long createdAt = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        @Nullable
        volatile String sdUrl;
        @Nullable
        volatile String hdUrl;

        Lookup(String id) {
            this.id = id;
        }

        @Nullable
        String getUrl() {
            String sd = sdUrl;
            String hd = hdUrl;
            if (Settings.REDGIFS_HD.get()) {
                return hd != null ? hd : sd;
            }
            return sd != null ? sd : hd;
        }

        boolean isFailed() {
            return done.getCount() == 0 && getUrl() == null;
        }
    }

    private static <V> Map<String, V> createCache() {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        });
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point. Called when a post is created.
     */
    public static void onLinkCreated(Link link) {
        try {
            if (!Settings.REDGIFS_AUDIO.get()) return;

            String id = getRedgifsId(link.getUrl());
            if (id == null) return;

            Preview preview = link.getPreview();
            RedditVideo previewVideo = preview == null ? null : preview.getRedditVideoPreview();
            // Downloads use the media video instead of the preview video.
            LinkMedia media = link.getMedia();
            RedditVideo mediaVideo = media == null ? null : media.getRedditVideo();
            if (previewVideo == null && mediaVideo == null) return;

            recordRedditVideo(previewVideo, id);
            recordRedditVideo(mediaVideo, id);
            getLookup(id);
        } catch (Exception ex) {
            Logger.printException(() -> "onLinkCreated failure", ex);
        }
    }

    /**
     * Injection point. Called when a feed post header is created.
     * <p>
     * The feed is not created from posts, and only the post header has the post url.
     *
     * @param cellId    "MetadataCell-" and the post id, or Base64 of it in older versions.
     * @param mediaPath Post url.
     */
    public static void onFeedMetadataCellCreated(@Nullable String cellId, @Nullable String mediaPath) {
        try {
            if (!Settings.REDGIFS_AUDIO.get()) return;
            if (cellId == null || mediaPath == null || !mediaPath.contains("redgifs.com/")) return;

            String id = getRedgifsId(mediaPath);
            if (id == null) return;

            String decodedCellId = cellId.startsWith(METADATA_CELL_ID_PREFIX)
                    ? cellId
                    : decodeBase64CellId(cellId);
            if (decodedCellId == null || !decodedCellId.startsWith(METADATA_CELL_ID_PREFIX)) return;

            postIdToRedgifsId.put(decodedCellId.substring(METADATA_CELL_ID_PREFIX.length()), id);
            getLookup(id);
        } catch (Exception ex) {
            Logger.printException(() -> "onFeedMetadataCellCreated failure", ex);
        }
    }

    @Nullable
    private static String decodeBase64CellId(String cellId) {
        try {
            return new String(Base64.decode(cellId, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Injection point. Called when a feed video is created.
     */
    public static void onFeedVideoCreated(@Nullable String postId, @Nullable String url) {
        try {
            if (!Settings.REDGIFS_AUDIO.get() || postId == null) return;

            String id = postIdToRedgifsId.get(postId);
            if (id != null) {
                recordRedditVideo(url, id);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onFeedVideoCreated failure", ex);
        }
    }

    /**
     * Injection point. Called when a video player url is created.
     *
     * @return The Redgifs video url, a placeholder url if it's not known yet, or the original url.
     */
    public static String getPlaybackUrl(@Nullable String url) {
        try {
            if (url == null || !Settings.REDGIFS_AUDIO.get()) return url;

            String redditVideoId = getRedditVideoId(url);
            String id = redditVideoId == null ? null : redditVideoIdToRedgifsId.get(redditVideoId);
            if (id == null) return url;

            Lookup lookup = getLookup(id);
            String redgifsUrl = lookup.getUrl();
            if (redgifsUrl != null) return redgifsUrl;
            if (lookup.isFailed()) return url;

            // The placeholder is played as a progressive video, so the fallback must be one too.
            String fallbackUrl = url.contains(".mp4")
                    ? url
                    // The lowest quality of the DASH and HLS playlists, which every video has.
                    : "https://v.redd.it/" + redditVideoId + "/CMAF_220.mp4";

            return new Uri.Builder()
                    .scheme("https")
                    .authority(PLACEHOLDER_HOST)
                    .path(PLACEHOLDER_PATH + id + ".mp4")
                    .appendQueryParameter(PLACEHOLDER_FALLBACK_PARAMETER, fallbackUrl)
                    .build()
                    .toString();
        } catch (Exception ex) {
            Logger.printException(() -> "getPlaybackUrl failure", ex);
            return url;
        }
    }

    /**
     * Injection point. Called when the video player loads an url.
     *
     * @return The Redgifs video url if the uri is a placeholder, otherwise the original uri.
     */
    public static Uri resolvePlaceholderUri(Uri uri) {
        try {
            if (!Settings.REDGIFS_AUDIO.get() || !PLACEHOLDER_HOST.equals(uri.getHost())) return uri;

            String path = uri.getPath();
            if (path == null || !path.startsWith(PLACEHOLDER_PATH) || !path.endsWith(".mp4")) return uri;

            Lookup lookup = getLookup(path.substring(PLACEHOLDER_PATH.length(), path.length() - ".mp4".length()));
            if (!Utils.isCurrentlyOnMainThread()) {
                lookup.done.await(PLAYER_WAIT_MS, TimeUnit.MILLISECONDS);
            }

            String url = lookup.getUrl();
            if (url == null) {
                Logger.printDebug(() -> "Redgifs video url not found, playing Reddit's video: " + lookup.id);
                url = uri.getQueryParameter(PLACEHOLDER_FALLBACK_PARAMETER);
            }

            return url == null ? uri : Uri.parse(url);
        } catch (InterruptedException ex) {
            // The player cancelled the load.
            Logger.printDebug(() -> "Redgifs placeholder resolve interrupted: " + uri);
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            Logger.printException(() -> "resolvePlaceholderUri failure", ex);
        }

        return uri;
    }

    /**
     * Injection point. Called when a video download is enqueued.
     *
     * @return The HD Redgifs video uri, or the original uri if it's not known.
     */
    public static Uri getDownloadUri(Uri uri) {
        String url = getDownloadUrl(uri.toString());
        return url.equals(uri.toString()) ? uri : Uri.parse(url);
    }

    private static String getDownloadUrl(String url) {
        try {
            if (!Settings.REDGIFS_AUDIO.get()) return url;

            String redditVideoId = getRedditVideoId(url);
            String id = redditVideoId == null ? null : redditVideoIdToRedgifsId.get(redditVideoId);
            if (id == null) return url;

            Lookup lookup = getLookup(id);
            // Downloads are usually enqueued on the main thread, where the lookup can't be awaited.
            // It started when the post was loaded, so it's usually done by now.
            if (!Utils.isCurrentlyOnMainThread()) {
                lookup.done.await(DOWNLOAD_WAIT_MS, TimeUnit.MILLISECONDS);
            }

            // Downloads aren't streamed, so the HD quality setting doesn't apply.
            String hd = lookup.hdUrl;
            String redgifsUrl = hd != null ? hd : lookup.sdUrl;
            if (redgifsUrl == null) {
                Logger.printDebug(() -> "Redgifs video url not found, downloading Reddit's video: " + id);
                return url;
            }

            return redgifsUrl;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            Logger.printException(() -> "getDownloadUrl failure", ex);
        }

        return url;
    }

    /**
     * @return The Redgifs id of a Redgifs video url, or null if it's not one.
     */
    @Nullable
    private static String getRedgifsId(@Nullable String url) {
        if (url == null) return null;

        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null || !(host.equals("redgifs.com") || host.endsWith(".redgifs.com"))) return null;

        String id = null;
        List<String> segments = uri.getPathSegments();
        if (segments.size() == 2 && (segments.get(0).equals("watch") || segments.get(0).equals("ifr"))) {
            // Video page: redgifs.com/watch/<id>
            id = segments.get(1);
        } else if (host.equals("media.redgifs.com") && segments.size() == 1) {
            // Video file: media.redgifs.com/<Id>-mobile.mp4
            id = segments.get(0).split("[-.]", 2)[0];
        }

        if (id == null) return null;
        id = id.toLowerCase(Locale.ROOT);
        return REDGIFS_ID_PATTERN.matcher(id).matches() ? id : null;
    }

    /**
     * Reddit urls of the same video differ in format (DASH, HLS, mp4), but share the video id.
     *
     * @return The video id of a Reddit video url, or null if it's not one.
     */
    @Nullable
    private static String getRedditVideoId(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        // v.redd.it, cf.v.redd.it or packaged-media.redd.it
        if (host == null || !(host.endsWith("v.redd.it") || host.equals("packaged-media.redd.it"))) return null;

        List<String> segments = uri.getPathSegments();
        return segments.isEmpty() ? null : segments.get(0);
    }

    private static void recordRedditVideo(@Nullable RedditVideo video, String id) {
        if (video == null) return;

        recordRedditVideo(video.getDashUrl(), id);
        recordRedditVideo(video.getHlsUrl(), id);
        recordRedditVideo(video.getFallBackUrl(), id);
        recordRedditVideo(video.getDownloadUrl(), id);
    }

    private static void recordRedditVideo(@Nullable String url, String id) {
        String redditVideoId = url == null ? null : getRedditVideoId(url);
        if (redditVideoId != null) {
            redditVideoIdToRedgifsId.put(redditVideoId, id);
        }
    }

    /**
     * @return The lookup of a Redgifs id, which is started if it's not cached.
     */
    private static Lookup getLookup(String id) {
        Lookup lookup;
        synchronized (lookups) {
            lookup = lookups.get(id);
            if (lookup != null && !(lookup.isFailed()
                    && System.currentTimeMillis() - lookup.createdAt > FAILED_LOOKUP_RETRY_MS)) {
                return lookup;
            }

            lookup = new Lookup(id);
            lookups.put(id, lookup);
        }

        Lookup newLookup = lookup;
        Utils.runOnBackgroundThread(() -> fetchUrl(newLookup));
        return newLookup;
    }

    private static void fetchUrl(Lookup lookup) {
        try {
            JSONObject urls = fetchGif(lookup.id).getJSONObject("urls");
            lookup.sdUrl = urls.optString("sd", null);
            lookup.hdUrl = urls.optString("hd", null);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Redgifs lookup failed: " + lookup.id, ex);
        } finally {
            lookup.done.countDown();
        }
    }

    private static JSONObject fetchGif(String id) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            String currentToken = getToken();
            HttpURLConnection connection = openConnection(API_URL + "gifs/" + id);
            connection.setRequestProperty("Authorization", "Bearer " + currentToken);

            final int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return Requester.parseJSONObject(connection).getJSONObject("gif");
            }

            connection.disconnect();
            if (responseCode != HttpURLConnection.HTTP_UNAUTHORIZED
                    && responseCode != HttpURLConnection.HTTP_FORBIDDEN) {
                throw new IOException("Response code: " + responseCode);
            }

            // The token expired, or the network changed since it was issued.
            invalidateToken(currentToken);
        }

        throw new IOException("Token rejected");
    }

    private static synchronized String getToken() throws Exception {
        if (token == null || System.currentTimeMillis() > tokenExpires) {
            token = Requester.parseJSONObject(openConnection(API_URL + "auth/temporary")).getString("token");
            tokenExpires = System.currentTimeMillis() + TOKEN_LIFETIME_MS;
        }

        return token;
    }

    private static synchronized void invalidateToken(String rejectedToken) {
        if (rejectedToken.equals(token)) {
            token = null;
        }
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }
}
