/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.google.android.libraries.youtube.media.interfaces.HttpHeader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.MediaHttpHeader;
import app.morphe.extension.shared.patches.MediaHttpRequest;
import app.morphe.extension.youtube.patches.LoadVideoPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.videoplayer.GhostPlayButton;

/**
 * Ghost mode: watch specific videos, Shorts, search opens, channels, or whole sessions
 * without affecting YouTube watch history or recommendations, while keeping the current
 * account and settings intact.
 */
@SuppressWarnings("unused")
public final class GhostPlayPatch {

    private static final int MAX_TRACKED_GHOST_VIDEOS = 128;
    private static final long HISTORY_SAVE_DELAY_MS = 2_500L;
    private static final long HISTORY_PERIODIC_SAVE_MS = 10_000L;
    private static final long PENDING_LAUNCH_WINDOW_MS = 15_000L;
    private static final long MIN_RESUME_POSITION_MS = 5_000L;

    private static final String INNERTUBE_PATH = "/youtubei/v1/";
    private static final String HEADER_AUTHORIZATION = "authorization";
    private static final String HEADER_PAGE_ID = "x-goog-pageid";
    private static final String HEADER_COOKIE = "cookie";
    private static final String HEADER_VISITOR_ID = "x-goog-visitor-id";

    // Protobuf wire constants for reading videoId from InnerTube request bodies.
    private static final int VIDEO_ID_FIELD = 2;
    private static final int PLAYER_REQUEST_FIELD = 2;
    private static final int WIRE_TYPE_VARINT = 0;
    private static final int WIRE_TYPE_64BIT = 1;
    private static final int WIRE_TYPE_LENGTH_DELIMITED = 2;
    private static final int WIRE_TYPE_32BIT = 5;
    private static final int GZIP_MAGIC_FIRST = 0x1F;
    private static final int GZIP_MAGIC_SECOND = 0x8B;

    /**
     * Video IDs explicitly marked for per-video ghost protection (flyout, local history,
     * auto-Shorts, auto-Search, auto-Channel). Session-wide mode does not populate this set
     * so turning Ghost mode OFF immediately restores normal tracking for previously watched videos.
     */
    private static final Set<String> ghostVideoIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_TRACKED_GHOST_VIDEOS;
                }
            })
    );

    private static volatile boolean runtimeInitialized = false;
    private static volatile long timedGhostUntilMs = 0L;

    @Nullable
    private static volatile String pendingGhostVideoId;
    private static volatile long pendingGhostUntilMs = 0L;

    @Nullable
    private static volatile String pendingToastVideoId;

    /**
     * Ghost video whose position is saved to the local history.
     */
    @Nullable
    private static volatile String activeGhostHistoryVideoId;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable periodicHistorySaver = new Runnable() {
        @Override
        public void run() {
            String videoId = activeGhostHistoryVideoId;
            if (videoId == null || !isEnabled() || !Settings.GHOST_PLAY_LOCAL_HISTORY.get()) {
                return;
            }
            String currentId = getCurrentVideoId();
            if (!videoId.equals(currentId)) {
                return;
            }
            if (PlayerType.getCurrent().isNoneOrHidden()) {
                return;
            }
            captureAndSaveCurrentVideo(videoId);
            mainHandler.postDelayed(this, HISTORY_PERIODIC_SAVE_MS);
        }
    };

    private GhostPlayPatch() {
    }

    private static void ensureRuntimeInitialized() {
        if (runtimeInitialized) {
            return;
        }
        synchronized (GhostPlayPatch.class) {
            if (runtimeInitialized) {
                return;
            }
            runtimeInitialized = true;
            try {
                if (!Settings.GHOST_PLAY_PERSIST_MODE.get() && Settings.GHOST_PLAY_MODE_ACTIVE.get()) {
                    Settings.GHOST_PLAY_MODE_ACTIVE.save(false);
                }
                VideoInformation.onChannelIdChange.addObserver((String channelId) ->
                        markCurrentVideoIfMatchesChannel());
            } catch (Exception ex) {
                Logger.printException(() -> "GhostPlayPatch.ensureRuntimeInitialized failure", ex);
            }
        }
    }

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    public static boolean isEnabled() {
        if (!isPatchIncluded()) {
            return false;
        }
        ensureRuntimeInitialized();
        return Settings.GHOST_PLAY_ENABLED.get();
    }

    public static boolean isSessionGhostActive() {
        if (!isEnabled()) {
            return false;
        }
        if (Settings.GHOST_PLAY_MODE_ACTIVE.get()) {
            return true;
        }
        long until = timedGhostUntilMs;
        if (until > 0L) {
            if (System.currentTimeMillis() < until) {
                return true;
            }
            timedGhostUntilMs = 0L;
            refreshAllUi();
        }
        return false;
    }

    public static long getTimedGhostRemainingMinutes() {
        long until = timedGhostUntilMs;
        if (until <= 0L) {
            return 0L;
        }
        long diff = until - System.currentTimeMillis();
        if (diff <= 0L) {
            timedGhostUntilMs = 0L;
            refreshAllUi();
            return 0L;
        }
        return (diff + 59_999L) / 60_000L;
    }

    public static void setSessionGhostMode(boolean enabled) {
        ensureRuntimeInitialized();
        Settings.GHOST_PLAY_MODE_ACTIVE.save(enabled);
        if (!enabled) {
            timedGhostUntilMs = 0L;
            pendingGhostVideoId = null;
            pendingGhostUntilMs = 0L;
            unmarkCurrentVideo();
        } else {
            String currentId = getCurrentVideoId();
            if (Utils.isNotEmpty(currentId)) {
                scheduleLocalHistoryRecording(currentId);
            }
        }
        refreshAllUi();
    }

    public static void toggleSessionGhostMode() {
        boolean next = !isSessionGhostActive();
        setSessionGhostMode(next);
        Utils.showSnackbarShort(str(next
                ? "morphe_ghost_play_session_on_toast"
                : "morphe_ghost_play_session_off_toast"));
    }

    /**
     * Toggles Ghost protection when tapped from the video player overlay button.
     */
    public static void toggleFromPlayerButton() {
        if (isSessionGhostActive()) {
            setSessionGhostMode(false);
            Utils.showSnackbarShort(str("morphe_ghost_play_session_off_toast"));
        } else if (isCurrentVideoGhost()) {
            pendingGhostVideoId = null;
            pendingGhostUntilMs = 0L;
            unmarkCurrentVideo();
            refreshAllUi();
            Utils.showSnackbarShort(str("morphe_ghost_play_video_off_toast"));
        } else {
            setSessionGhostMode(true);
            Utils.showSnackbarShort(str("morphe_ghost_play_session_on_toast"));
        }
    }

    public static void cancelTimerKeepGhostOn() {
        ensureRuntimeInitialized();
        timedGhostUntilMs = 0L;
        Settings.GHOST_PLAY_MODE_ACTIVE.save(true);
        refreshAllUi();
        Utils.showSnackbarShort(str("morphe_ghost_play_timer_cancelled_keep_on_toast"));
    }

    public static void enableTimedGhostMode(int minutes) {
        ensureRuntimeInitialized();
        if (minutes <= 0) {
            timedGhostUntilMs = 0L;
            Settings.GHOST_PLAY_MODE_ACTIVE.save(false);
        } else {
            Settings.GHOST_PLAY_MODE_ACTIVE.save(false);
            timedGhostUntilMs = System.currentTimeMillis() + (minutes * 60_000L);
            String currentId = getCurrentVideoId();
            if (Utils.isNotEmpty(currentId)) {
                scheduleLocalHistoryRecording(currentId);
            }
        }
        refreshAllUi();
    }

    public static void markGhostVideo(@Nullable String videoId) {
        if (Utils.isNotEmpty(videoId)) {
            ghostVideoIds.add(videoId);
        }
    }

    public static void unmarkGhostVideo(@Nullable String videoId) {
        if (Utils.isNotEmpty(videoId)) {
            ghostVideoIds.remove(videoId);
        }
    }

    private static void unmarkCurrentVideo() {
        unmarkGhostVideo(VideoInformation.getStartingOrPlayingVideoId());
        unmarkGhostVideo(VideoInformation.getVideoId());
        clearActiveHistoryTracking();
    }

    public static boolean isPendingGhostActive() {
        String pending = pendingGhostVideoId;
        if (pending == null) {
            return false;
        }
        if (System.currentTimeMillis() <= pendingGhostUntilMs) {
            return true;
        }
        pendingGhostVideoId = null;
        return false;
    }

    public static boolean isGhostVideo(@Nullable String videoId) {
        if (!isEnabled()) {
            return false;
        }
        if (isSessionGhostActive()) {
            return true;
        }
        return Utils.isNotEmpty(videoId) && ghostVideoIds.contains(videoId);
    }

    public static boolean isCurrentVideoGhost() {
        if (!isEnabled()) {
            return false;
        }
        if (isSessionGhostActive()) {
            return true;
        }
        String startingId = VideoInformation.getStartingOrPlayingVideoId();
        if (Utils.isNotEmpty(startingId) && ghostVideoIds.contains(startingId)) {
            return true;
        }
        String playingId = VideoInformation.getVideoId();
        if (Utils.isNotEmpty(playingId) && ghostVideoIds.contains(playingId)) {
            return true;
        }
        return isPendingGhostActive();
    }

    /**
     * Marks the currently playing video protected if it belongs to a saved auto-ghost channel.
     */
    public static void markCurrentVideoIfMatchesChannel() {
        if (!isEnabled()) {
            return;
        }
        try {
            String videoId = getCurrentVideoId();
            if (!Utils.isNotEmpty(videoId)) {
                return;
            }
            String channelId = VideoInformation.getChannelId();
            String channelName = VideoInformation.getChannelName();
            if (GhostChannelStore.contains(channelId, channelName)) {
                markGhostVideo(videoId);
                scheduleLocalHistoryRecording(videoId);
                GhostPlayButton.refreshButtonState();
                Logger.printDebug(() -> "Auto-ghosted video by channel rule: " + channelName + " (" + channelId + ")");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "markCurrentVideoIfMatchesChannel failure", ex);
        }
    }

    /**
     * Launches a video directly in Ghost mode from a flyout menu or local history.
     */
    public static void playVideoInGhostMode(String videoId, long resumeMillis) {
        if (!Utils.isNotEmpty(videoId)) {
            return;
        }
        ensureRuntimeInitialized();
        markGhostVideo(videoId);
        pendingGhostVideoId = videoId;
        pendingGhostUntilMs = System.currentTimeMillis() + PENDING_LAUNCH_WINDOW_MS;
        pendingToastVideoId = videoId;

        StringBuilder url = new StringBuilder("https://www.youtube.com/watch?v=").append(videoId);
        if (resumeMillis >= MIN_RESUME_POSITION_MS) {
            url.append("&t=").append(resumeMillis / 1000L).append('s');
        }

        boolean closeCurrent = !PlayerType.getCurrent().isNoneOrHidden();
        LoadVideoPatch.openVideoIntent(url.toString(), closeCurrent);
        scheduleButtonRefresh();
    }

    /**
     * Called from FlyoutUtils when building video menus in feeds, search, history, and related lists.
     */
    public static int addFlyoutButton(Object flyoutPanel, int index, String videoId) {
        if (!isEnabled() || !Settings.GHOST_PLAY_FLYOUT_BUTTON.get() || !Utils.isNotEmpty(videoId)) {
            return index;
        }

        int drawableId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "morphe_ghost_play_flyout");
        Drawable icon = drawableId != 0 ? Utils.getContext().getDrawable(drawableId) : null;
        if (icon == null) {
            return index;
        }

        return FlyoutUtils.addFlyoutButton(
                flyoutPanel,
                icon,
                str("morphe_ghost_play_flyout_action"),
                v -> {
                    FlyoutUtils.dismissFlyout();
                    playVideoInGhostMode(videoId, 0L);
                },
                index
        );
    }

    /**
     * Injection point (PlayerResponseMethodHookPatch Hook.VideoId).
     */
    public static void onVideoResponse(@Nullable String videoId, boolean isShortAndOpeningOrPlaying) {
        if (!isEnabled() || !Utils.isNotEmpty(videoId)) {
            return;
        }
        try {
            if (isSessionGhostActive()) {
                return;
            }
            if (videoId.equals(pendingGhostVideoId)) {
                markGhostVideo(videoId);
                return;
            }
            if (isShortAndOpeningOrPlaying && Settings.GHOST_PLAY_AUTO_SHORTS.get()) {
                markGhostVideo(videoId);
                Logger.printDebug(() -> "Auto-ghosted Short: " + videoId);
                return;
            }
            if (!isShortAndOpeningOrPlaying
                    && Settings.GHOST_PLAY_AUTO_SEARCH.get()
                    && NavigationBar.isSearchBarActive()) {
                markGhostVideo(videoId);
                Logger.printDebug(() -> "Auto-ghosted video opened from search: " + videoId);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onVideoResponse failure", ex);
        }
    }

    /**
     * Injection point (startVideoInformerPatch).
     */
    public static void onVideoStarted() {
        if (!isEnabled()) {
            return;
        }
        try {
            clearActiveHistoryTracking();

            final String resolvedVideoId = getCurrentVideoId();
            if (!Utils.isNotEmpty(resolvedVideoId)) {
                scheduleButtonRefresh();
                return;
            }

            if (resolvedVideoId.equals(pendingGhostVideoId)) {
                markGhostVideo(resolvedVideoId);
                pendingGhostVideoId = null;
                pendingGhostUntilMs = 0L;
            }

            if (GhostChannelStore.contains(VideoInformation.getChannelId(), VideoInformation.getChannelName())) {
                markGhostVideo(resolvedVideoId);
                Logger.printDebug(() -> "Auto-ghosted video by channel: "
                        + VideoInformation.getChannelName() + " (" + resolvedVideoId + ")");
            }

            if (isGhostVideo(resolvedVideoId)) {
                if (resolvedVideoId.equals(pendingToastVideoId)) {
                    pendingToastVideoId = null;
                    Utils.showSnackbarShort(str("morphe_ghost_play_video_active_toast"));
                }
                scheduleLocalHistoryRecording(resolvedVideoId);
            }

            scheduleButtonRefresh();
        } catch (Exception ex) {
            Logger.printException(() -> "onVideoStarted failure", ex);
        }
    }

    /**
     * Injection point in VideoStats2Client, VideoStats3Client, and PtrackingClient.
     */
    public static boolean shouldBlockStats(@Nullable String videoId) {
        if (!isEnabled()) {
            return false;
        }
        boolean block = isSessionGhostActive()
                || (Utils.isNotEmpty(videoId) && ghostVideoIds.contains(videoId))
                || (!Utils.isNotEmpty(videoId) && isCurrentVideoGhost());
        if (block) {
            Logger.printDebug(() -> "Blocked playback/watchtime stats for ghost video: " + videoId);
        }
        return block;
    }

    /**
     * Injection point (MediaRequestPatch hookMediaRequest with replacesRequest = true).
     */
    public static MediaHttpRequest onMediaRequest(@Nullable MediaHttpRequest request, @Nullable Object callbacks) {
        if (request == null || !isEnabled() || !Settings.GHOST_PLAY_ANONYMIZE_REQUESTS.get()) {
            return request;
        }
        try {
            String url = request.patch_getUrl();
            if (url == null || url.isEmpty() || !isNativeGhostTargetUrl(url)) {
                return request;
            }
            boolean protect = isSessionGhostActive() || isCurrentVideoGhost();
            if (!protect) {
                String id = extractQueryParam(url, "docid");
                if (id == null) id = extractQueryParam(url, "v");
                if (id == null) id = extractQueryParam(url, "id");
                if (id != null && ghostVideoIds.contains(id)) {
                    protect = true;
                }
            }
            if (!protect) {
                return request;
            }
            ArrayList<HttpHeader> headers = request.patch_getHeaders();
            if (headers == null || headers.isEmpty()) {
                return request;
            }
            ArrayList<HttpHeader> filtered = new ArrayList<>(headers.size());
            boolean modified = false;
            for (HttpHeader h : headers) {
                if (h instanceof MediaHttpHeader mh) {
                    String name = mh.patch_getName();
                    if (name != null && isAuthOrVisitorHeader(name)) {
                        modified = true;
                        continue;
                    }
                }
                filtered.add(h);
            }
            return modified ? request.patch_withBodyAndHeaders(request.patch_getBody(), filtered) : request;
        } catch (Exception ex) {
            Logger.printException(() -> "GhostPlayPatch.onMediaRequest failure", ex);
            return request;
        }
    }

    static boolean isNativeGhostTargetUrl(String url) {
        return url.contains("/youtubei/v1/player")
                || url.contains("/youtubei/v1/reel/reel_item_watch")
                || url.contains("/youtubei/v1/get_watch")
                || url.contains("/initplayback")
                || url.contains("/api/stats/")
                || url.contains("/ptracking");
    }

    /**
     * Injection point (BuildRequestPatch hookBuildRequest with hookHeader=true, includeBody=true, highPriority=true).
     */
    public static Map<String, String> overrideRequestHeaders(
            @Nullable String url,
            @Nullable Map<String, String> headers,
            @Nullable byte[] body
    ) {
        if (!isEnabled() || url == null || headers == null || headers.isEmpty()) {
            return headers;
        }
        if (!Settings.GHOST_PLAY_ANONYMIZE_REQUESTS.get()) {
            return headers;
        }
        try {
            return processRequestHeaders(
                    url,
                    headers,
                    body,
                    isSessionGhostActive(),
                    Settings.GHOST_PLAY_AUTO_SHORTS.get(),
                    Settings.GHOST_PLAY_AUTO_SEARCH.get() && NavigationBar.isSearchBarActive(),
                    isCurrentVideoGhost(),
                    isPendingGhostActive()
            );
        } catch (Exception ex) {
            Logger.printException(() -> "overrideRequestHeaders failure", ex);
            return headers;
        }
    }

    /**
     * Core header anonymization logic, separated for deterministic unit testing.
     */
    static Map<String, String> processRequestHeaders(
            String url,
            Map<String, String> headers,
            @Nullable byte[] body,
            boolean sessionActive,
            boolean autoShorts,
            boolean autoSearchNow,
            boolean currentVideoGhost,
            boolean pendingGhostActive
    ) {
        if (!sessionActive && !autoShorts && !autoSearchNow && !currentVideoGhost
                && !pendingGhostActive && ghostVideoIds.isEmpty()) {
            return headers;
        }

        String path = extractPath(url);
        if (path == null) {
            return headers;
        }

        if (path.contains("/api/stats/watchtime")
                || path.contains("/api/stats/playback")
                || path.contains("/api/stats/delayplay")
                || path.contains("/api/stats/atr")
                || path.contains("/ptracking")
                || path.contains("/initplayback")) {
            String docId = extractQueryParam(url, "docid");
            if (docId == null) {
                docId = extractQueryParam(url, "v");
            }
            if (sessionActive || currentVideoGhost || pendingGhostActive
                    || (docId != null && ghostVideoIds.contains(docId))) {
                return stripAuthHeaders(headers);
            }
            return headers;
        }

        if (!path.startsWith(INNERTUBE_PATH)) {
            return headers;
        }

        if (path.contains("/log_event")) {
            return (sessionActive || currentVideoGhost || pendingGhostActive)
                    ? stripAuthHeaders(headers)
                    : headers;
        }

        if (path.contains("/navigation/resolve_url")) {
            return (sessionActive || pendingGhostActive)
                    ? stripAuthHeaders(headers)
                    : headers;
        }

        boolean isReelWatch = path.contains("reel/reel_item_watch");
        boolean isGetWatch = path.contains("get_watch");
        boolean isPlayer = path.endsWith("/player");
        boolean isNext = path.endsWith("/next");

        if (!isReelWatch && !isGetWatch && !isPlayer && !isNext) {
            return headers;
        }

        String videoId = extractQueryParam(url, "id");
        if ((videoId == null || videoId.isEmpty()) && body != null && body.length > 0) {
            videoId = readVideoIdFromBody(body, isReelWatch || isGetWatch);
        }

        if (!sessionActive) {
            if (isReelWatch && autoShorts && videoId != null && !videoId.isEmpty()) {
                ghostVideoIds.add(videoId);
            } else if (!isReelWatch && autoSearchNow && (isPlayer || isGetWatch) && videoId != null && !videoId.isEmpty()) {
                ghostVideoIds.add(videoId);
            }
        }

        boolean idMatched = videoId != null && !videoId.isEmpty() && ghostVideoIds.contains(videoId);
        boolean shouldAnonymize = sessionActive
                || idMatched
                || (isReelWatch && autoShorts)
                || (pendingGhostActive && (videoId == null || videoId.isEmpty()));

        return shouldAnonymize ? stripAuthHeaders(headers) : headers;
    }

    private static boolean isAuthOrVisitorHeader(String key) {
        String low = key.toLowerCase(Locale.ROOT);
        return low.equals(HEADER_AUTHORIZATION)
                || low.equals(HEADER_PAGE_ID)
                || low.equals(HEADER_COOKIE)
                || low.equals(HEADER_VISITOR_ID);
    }

    /**
     * Returns a shallow copy of headers with account-identifying headers removed.
     */
    static Map<String, String> stripAuthHeaders(Map<String, String> original) {
        Map<String, String> sanitized = new HashMap<>(original.size());
        for (Map.Entry<String, String> entry : original.entrySet()) {
            String key = entry.getKey();
            if (key == null || isAuthOrVisitorHeader(key)) {
                continue;
            }
            sanitized.put(key, entry.getValue());
        }
        return sanitized;
    }

    /**
     * Reads videoId (field 2) from a raw or GZIP-compressed InnerTube protobuf request body.
     */
    @Nullable
    static String readVideoIdFromBody(byte[] body, boolean holdsPlayerRequest) {
        try {
            byte[] message = body.length > 1
                    && (body[0] & 0xFF) == GZIP_MAGIC_FIRST
                    && (body[1] & 0xFF) == GZIP_MAGIC_SECOND
                    ? decompressGzip(body) : body;
            if (holdsPlayerRequest) {
                message = readLengthDelimitedField(message, PLAYER_REQUEST_FIELD);
                if (message == null) {
                    return null;
                }
            }
            byte[] idBytes = readLengthDelimitedField(message, VIDEO_ID_FIELD);
            if (idBytes == null || idBytes.length == 0) {
                return null;
            }
            return new String(idBytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Logger.printException(() -> "readVideoIdFromBody failure", ex);
            return null;
        }
    }

    @Nullable
    private static byte[] readLengthDelimitedField(byte[] data, int targetFieldNumber) {
        int pos = 0;
        int len = data.length;
        while (pos < len) {
            long tag = 0;
            int shift = 0;
            while (pos < len) {
                int b = data[pos++] & 0xFF;
                tag |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 63) return null;
            }
            int fieldNumber = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x07);
            if (fieldNumber <= 0) return null;

            switch (wireType) {
                case WIRE_TYPE_VARINT:
                    while (pos < len && (data[pos++] & 0x80) != 0) {
                        // skip varint
                    }
                    break;
                case WIRE_TYPE_64BIT:
                    pos += 8;
                    break;
                case WIRE_TYPE_LENGTH_DELIMITED: {
                    int size = 0;
                    int sShift = 0;
                    while (pos < len) {
                        int b = data[pos++] & 0xFF;
                        size |= (b & 0x7F) << sShift;
                        if ((b & 0x80) == 0) break;
                        sShift += 7;
                        if (sShift > 35) return null;
                    }
                    if (size < 0 || pos + size > len) return null;
                    if (fieldNumber == targetFieldNumber) {
                        byte[] out = new byte[size];
                        System.arraycopy(data, pos, out, 0, size);
                        return out;
                    }
                    pos += size;
                    break;
                }
                case WIRE_TYPE_32BIT:
                    pos += 4;
                    break;
                default:
                    return null;
            }
        }
        return null;
    }

    private static byte[] decompressGzip(byte[] data) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[4096];
            int n;
            while ((n = gzip.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    @Nullable
    static String extractPath(String url) {
        int schemeIdx = url.indexOf("://");
        int start = schemeIdx >= 0 ? url.indexOf('/', schemeIdx + 3) : 0;
        if (start < 0) {
            return null;
        }
        int q = url.indexOf('?', start);
        return q >= 0 ? url.substring(start, q) : url.substring(start);
    }

    @Nullable
    static String extractQueryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0 || q + 1 >= url.length()) {
            return null;
        }
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        // The values used are video ids, which are not URL encoded.
        String prefix = key + "=";
        for (String part : query.split("&")) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }

    @Nullable
    private static String getCurrentVideoId() {
        String id = VideoInformation.getStartingOrPlayingVideoId();
        return Utils.isNotEmpty(id) ? id : VideoInformation.getVideoId();
    }

    private static void refreshAllUi() {
        GhostPlayButton.refreshButtonState();
        GhostPlayToolbarButton.refreshAll();
    }

    private static void scheduleButtonRefresh() {
        GhostPlayButton.refreshButtonState();
        mainHandler.postDelayed(GhostPlayButton::refreshButtonState, 300L);
        mainHandler.postDelayed(GhostPlayButton::refreshButtonState, 1000L);
    }

    private static void scheduleLocalHistoryRecording(String videoId) {
        if (!Settings.GHOST_PLAY_LOCAL_HISTORY.get()) {
            return;
        }
        activeGhostHistoryVideoId = videoId;
        mainHandler.removeCallbacks(periodicHistorySaver);
        mainHandler.postDelayed(periodicHistorySaver, HISTORY_SAVE_DELAY_MS);
    }

    private static void captureAndSaveCurrentVideo(String expectedVideoId) {
        try {
            if (!Settings.GHOST_PLAY_LOCAL_HISTORY.get()) {
                return;
            }
            if (!expectedVideoId.equals(getCurrentVideoId())) {
                return;
            }
            GhostHistoryStore.record(
                    expectedVideoId,
                    VideoInformation.getVideoTitle(),
                    VideoInformation.getChannelName(),
                    VideoInformation.getVideoTime(),
                    VideoInformation.getVideoLength()
            );
        } catch (Exception ex) {
            Logger.printException(() -> "captureAndSaveCurrentVideo failure", ex);
        }
    }

    private static void clearActiveHistoryTracking() {
        mainHandler.removeCallbacks(periodicHistorySaver);
        activeGhostHistoryVideoId = null;
    }

    // Test hooks (package-private).
    static void resetForTests() {
        ghostVideoIds.clear();
        timedGhostUntilMs = 0L;
        pendingGhostVideoId = null;
        pendingGhostUntilMs = 0L;
        pendingToastVideoId = null;
        activeGhostHistoryVideoId = null;
    }

    static void addGhostVideoForTests(String videoId) {
        ghostVideoIds.add(videoId);
    }

    static boolean containsGhostVideoForTests(String videoId) {
        return ghostVideoIds.contains(videoId);
    }
}
