package app.morphe.extension.youtube.niconicocomments;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fetches public YouTube live and replay chat without another Morphe patch bundle. */
final class YoutubeChatSource implements Runnable {
    interface Listener {
        long getVideoTimeMs();

        void onComments(
                String videoId,
                List<NiconicoComment> comments,
                long liveSpreadWindowMs
        );

        void onReplayReset(String videoId);
    }

    private static final String LOG_TAG = "NiconicoComments";
    private static final String YOUTUBE_ORIGIN = "https://www.youtube.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/138.0.0.0 Safari/537.36";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int DEFAULT_CONTINUATION_TIMEOUT_MS = 5_000;
    private static final int LIVE_POLL_MIN_DELAY_MS = 100;
    private static final int LIVE_POLL_MAX_DELAY_MS = 250;
    private static final int REPLAY_LOOP_DELAY_MS = 1_000;
    private static final int INITIAL_DISPLAY_LIMIT = 30;
    private static final long REPLAY_LOOKAHEAD_MS = 45_000;
    private static final long REPLAY_SEEK_THRESHOLD_MS = 12_000;
    private static final long REPLAY_START_MARGIN_MS = 5_000;
    private static final int REPLAY_MAX_FETCHES_PER_UPDATE = 12;
    private static final int MAX_COMMENT_LENGTH = 200;
    private static final int MAX_SEEN_IDS = 4_096;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final String videoId;
    private final Listener listener;
    private final Set<String> seenIds = new LinkedHashSet<>();
    private final ArrayDeque<String> seenIdOrder = new ArrayDeque<>();

    private volatile boolean stopped;
    private volatile HttpURLConnection activeConnection;

    YoutubeChatSource(String videoId, Listener listener) {
        this.videoId = videoId;
        this.listener = listener;
    }

    void stop() {
        stopped = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    @Override
    public void run() {
        Log.i(LOG_TAG, "Starting public live/replay chat source for " + videoId);

        PageState state = null;
        for (int attempt = 1; attempt <= 3 && !stopped; attempt++) {
            try {
                state = fetchPageState();
                break;
            } catch (Exception exception) {
                Log.w(LOG_TAG, "Chat initialization attempt " + attempt + " failed", exception);
                sleepBeforeRetry(attempt);
            }
        }

        if (stopped) {
            return;
        }
        if (state == null || state.continuation == null) {
            Log.i(LOG_TAG, "No public live/replay continuation found for " + videoId);
            return;
        }

        if (state.replay) {
            runReplay(state);
        } else {
            runLive(state);
        }
    }

    private void runLive(PageState state) {
        boolean firstPage = true;
        int consecutiveFailures = 0;
        Continuation continuation = state.continuation;
        while (!stopped && continuation != null) {
            try {
                ChatPage page = fetchChatPage(state, continuation.value);
                consecutiveFailures = 0;

                List<NiconicoComment> comments = deduplicate(page.comments);
                if (firstPage && comments.size() > INITIAL_DISPLAY_LIMIT) {
                    comments = new ArrayList<>(
                            comments.subList(comments.size() - INITIAL_DISPLAY_LIMIT, comments.size())
                    );
                }
                firstPage = false;

                Continuation nextContinuation = page.continuation;
                if (!comments.isEmpty() && !stopped) {
                    long spreadWindowMs = getLivePollDelayMs(
                            nextContinuation == null
                                    ? DEFAULT_CONTINUATION_TIMEOUT_MS
                                    : nextContinuation.timeoutMs
                    );
                    listener.onComments(videoId, comments, spreadWindowMs);
                    Log.d(LOG_TAG, "Received " + comments.size() + " live-chat comments");
                }

                continuation = nextContinuation;
                if (continuation == null) {
                    Log.i(LOG_TAG, "Live-chat continuation ended for " + videoId);
                    break;
                }
                sleep(getLivePollDelayMs(continuation.timeoutMs));
            } catch (Exception exception) {
                if (stopped) {
                    return;
                }
                consecutiveFailures++;
                Log.w(
                        LOG_TAG,
                        "Live-chat request failed (" + consecutiveFailures + "/3)",
                        exception
                );
                if (consecutiveFailures >= 3) {
                    break;
                }
                sleepBeforeRetry(consecutiveFailures);
            }
        }
    }

    private void runReplay(PageState state) {
        Log.i(LOG_TAG, "Replay-chat source selected for " + videoId);
        Continuation initialContinuation = state.continuation;
        Continuation continuation = initialContinuation;
        long loadedUntilMs = -1;
        long windowStartMs = 0;
        long lastVideoTimeMs = -1;
        boolean hasFetched = false;
        boolean done = false;
        int consecutiveFailures = 0;

        while (!stopped) {
            long videoTimeMs = listener.getVideoTimeMs();
            if (videoTimeMs < 0) {
                sleep(REPLAY_LOOP_DELAY_MS);
                continue;
            }

            boolean seeked = hasFetched
                    && lastVideoTimeMs >= 0
                    && Math.abs(videoTimeMs - lastVideoTimeMs) > REPLAY_SEEK_THRESHOLD_MS;
            boolean outsideWindow = hasFetched
                    && (videoTimeMs < windowStartMs
                    || videoTimeMs > loadedUntilMs + REPLAY_SEEK_THRESHOLD_MS);
            if (seeked || outsideWindow) {
                continuation = initialContinuation;
                windowStartMs = Math.max(0, videoTimeMs - REPLAY_START_MARGIN_MS);
                loadedUntilMs = windowStartMs;
                hasFetched = false;
                done = false;
                consecutiveFailures = 0;
                clearSeenIds();
                listener.onReplayReset(videoId);
                Log.d(LOG_TAG, "Replay-chat window reset at " + videoTimeMs + " ms");
            }
            if (loadedUntilMs < 0) {
                windowStartMs = Math.max(0, videoTimeMs - REPLAY_START_MARGIN_MS);
                loadedUntilMs = windowStartMs;
            }
            lastVideoTimeMs = videoTimeMs;

            long targetMs = videoTimeMs + REPLAY_LOOKAHEAD_MS;
            int fetches = 0;
            while (!stopped
                    && !done
                    && continuation != null
                    && loadedUntilMs < targetMs
                    && fetches < REPLAY_MAX_FETCHES_PER_UPDATE) {
                fetches++;
                long playerOffsetMs = Math.max(
                        0,
                        hasFetched ? loadedUntilMs - REPLAY_START_MARGIN_MS : windowStartMs
                );
                String previousContinuation = continuation.value;
                long previousLoadedUntilMs = loadedUntilMs;
                try {
                    ChatPage page = fetchReplayChatPage(
                            state,
                            continuation.value,
                            playerOffsetMs
                    );
                    consecutiveFailures = 0;
                    hasFetched = true;

                    List<NiconicoComment> comments = deduplicate(page.comments);
                    if (!comments.isEmpty() && !stopped) {
                        listener.onComments(videoId, comments, 0);
                        Log.d(LOG_TAG, "Received " + comments.size() + " replay-chat comments");
                    }
                    if (page.maxVideoOffsetMs >= 0) {
                        loadedUntilMs = Math.max(loadedUntilMs, page.maxVideoOffsetMs);
                    }

                    continuation = page.continuation;
                    if (continuation == null
                            || (previousContinuation.equals(continuation.value)
                            && loadedUntilMs <= previousLoadedUntilMs)) {
                        done = true;
                    }
                    if (page.comments.isEmpty() && page.maxVideoOffsetMs < 0) {
                        break;
                    }
                } catch (Exception exception) {
                    if (stopped) {
                        return;
                    }
                    consecutiveFailures++;
                    Log.w(
                            LOG_TAG,
                            "Replay-chat request failed (" + consecutiveFailures + "/3)",
                            exception
                    );
                    if (consecutiveFailures >= 3) {
                        done = true;
                    } else {
                        sleepBeforeRetry(consecutiveFailures);
                    }
                    break;
                }
            }
            sleep(REPLAY_LOOP_DELAY_MS);
        }
    }

    private PageState fetchPageState() throws IOException, JSONException {
        String watchUrl = YOUTUBE_ORIGIN + "/watch?v="
                + URLEncoder.encode(videoId, StandardCharsets.UTF_8.name())
                + "&hl=en&bpctr=9999999999&has_verified=1";
        String html = request(watchUrl, null, null, null);

        JSONObject config = parseYoutubeConfig(html);
        JSONObject context = config.optJSONObject("INNERTUBE_CONTEXT");
        String apiKey = config.optString("INNERTUBE_API_KEY", "");

        JSONObject client = context == null ? null : context.optJSONObject("client");
        String clientVersion = client == null ? "" : client.optString("clientVersion", "");
        if (clientVersion.isEmpty()) {
            clientVersion = config.optString("INNERTUBE_CLIENT_VERSION", "");
        }
        if (apiKey.isEmpty() || clientVersion.isEmpty()) {
            throw new JSONException("Watch page did not contain an Innertube API key and client version");
        }

        if (context == null) {
            context = new JSONObject();
            client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", clientVersion);
            String visitorData = config.optString("VISITOR_DATA", "");
            if (!visitorData.isEmpty()) {
                client.put("visitorData", visitorData);
            }
            context.put("client", client);
            context.put("request", new JSONObject().put("useSsl", true));
        }

        JSONObject initialData = parseMarkedObject(
                html,
                "var ytInitialData =",
                "ytInitialData =",
                "window[\"ytInitialData\"] ="
        );
        if (initialData == null) {
            throw new JSONException("Watch page did not contain ytInitialData");
        }

        JSONObject playerResponse = parseMarkedObject(
                html,
                "var ytInitialPlayerResponse =",
                "ytInitialPlayerResponse ="
        );
        JSONObject liveChatRenderer = getObjectPath(
                initialData,
                "contents",
                "twoColumnWatchNextResults",
                "conversationBar",
                "liveChatRenderer"
        );
        Object continuations = liveChatRenderer == null
                ? null
                : liveChatRenderer.opt("continuations");
        Continuation replayContinuation = findContinuation(
                continuations,
                "playerSeekContinuationData",
                "liveChatReplayContinuationData"
        );
        if (replayContinuation == null && isEndedLiveBroadcast(playerResponse)) {
            replayContinuation = findContinuation(
                    initialData,
                    "playerSeekContinuationData",
                    "liveChatReplayContinuationData",
                    "reloadContinuationData"
            );
        }

        if (liveChatRenderer == null) {
            return new PageState(apiKey, clientVersion, context, null, false);
        }
        if (replayContinuation != null) {
            try {
                Continuation allMessages = fetchAllMessagesReplayContinuation(
                        replayContinuation.value
                );
                if (allMessages != null) {
                    replayContinuation = allMessages;
                }
            } catch (Exception exception) {
                Log.w(LOG_TAG, "Could not select the all-messages replay filter", exception);
            }

            return new PageState(
                    apiKey,
                    clientVersion,
                    context,
                    replayContinuation,
                    true
            );
        }

        Continuation continuation = findContinuation(
                continuations,
                "timedContinuationData",
                "invalidationContinuationData",
                "reloadContinuationData"
        );
        return new PageState(apiKey, clientVersion, context, continuation, false);
    }

    private Continuation fetchAllMessagesReplayContinuation(String continuation)
            throws IOException, JSONException {
        String url = YOUTUBE_ORIGIN + "/live_chat_replay?continuation="
                + URLEncoder.encode(continuation, StandardCharsets.UTF_8.name())
                + "&hl=en";
        String html = request(url, null, null, null);
        JSONObject initialData = parseMarkedObject(
                html,
                "var ytInitialData =",
                "ytInitialData =",
                "window[\"ytInitialData\"] ="
        );
        if (initialData == null) {
            return null;
        }

        JSONArray items = getReplayFilterItems(initialData);
        if (items == null) {
            return null;
        }

        int selectedTopIndex = -1;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String text = getFilterItemText(item).toLowerCase(Locale.ROOT);
            boolean topChat = text.contains("top chat") || text.contains("some messages");
            boolean allMessages = !topChat
                    && (text.contains("live chat replay") || text.contains("all messages"));
            if (allMessages) {
                return item.optBoolean("selected", false)
                        ? null
                        : findContinuation(
                                item.opt("continuation"),
                                "reloadContinuationData",
                                "liveChatReplayContinuationData",
                                "playerSeekContinuationData"
                        );
            }
            if (topChat && item.optBoolean("selected", false)) {
                selectedTopIndex = index;
            }
        }

        if (items.length() == 2 && selectedTopIndex >= 0) {
            JSONObject other = items.optJSONObject(selectedTopIndex == 0 ? 1 : 0);
            return other == null
                    ? null
                    : findContinuation(
                            other.opt("continuation"),
                            "reloadContinuationData",
                            "liveChatReplayContinuationData",
                            "playerSeekContinuationData"
                    );
        }
        return null;
    }

    private ChatPage fetchChatPage(PageState state, String continuation)
            throws IOException, JSONException {
        String endpoint = YOUTUBE_ORIGIN + "/youtubei/v1/live_chat/get_live_chat?key="
                + URLEncoder.encode(state.apiKey, StandardCharsets.UTF_8.name())
                + "&prettyPrint=false";

        JSONObject body = new JSONObject();
        body.put("context", state.context);
        body.put("continuation", continuation);

        String responseText = request(
                endpoint,
                body.toString(),
                state.clientVersion,
                getVisitorData(state.context)
        );
        JSONObject response = new JSONObject(stripJsonPrefix(responseText));
        JSONObject liveChatContinuation = getLiveChatContinuation(response);
        if (liveChatContinuation == null) {
            return new ChatPage(new ArrayList<>(), null, -1);
        }

        List<NiconicoComment> comments = parseComments(liveChatContinuation.optJSONArray("actions"));
        Continuation next = findContinuation(
                liveChatContinuation.opt("continuations"),
                "timedContinuationData",
                "invalidationContinuationData",
                "reloadContinuationData"
        );
        return new ChatPage(comments, next, -1);
    }

    private ChatPage fetchReplayChatPage(
            PageState state,
            String continuation,
            long playerOffsetMs
    ) throws IOException, JSONException {
        String endpoint = YOUTUBE_ORIGIN + "/youtubei/v1/live_chat/get_live_chat_replay?key="
                + URLEncoder.encode(state.apiKey, StandardCharsets.UTF_8.name())
                + "&prettyPrint=false";

        JSONObject body = new JSONObject();
        body.put("context", state.context);
        body.put("continuation", continuation);
        body.put(
                "currentPlayerState",
                new JSONObject().put("playerOffsetMs", Long.toString(playerOffsetMs))
        );

        String responseText = request(
                endpoint,
                body.toString(),
                state.clientVersion,
                getVisitorData(state.context)
        );
        JSONObject response = new JSONObject(stripJsonPrefix(responseText));
        JSONObject liveChatContinuation = getLiveChatContinuation(response);
        if (liveChatContinuation == null) {
            return new ChatPage(new ArrayList<>(), null, -1);
        }

        ReplayParseResult parsed = parseReplayComments(
                liveChatContinuation.optJSONArray("actions")
        );
        Continuation next = findContinuation(
                liveChatContinuation.opt("continuations"),
                "liveChatReplayContinuationData",
                "playerSeekContinuationData",
                "timedContinuationData",
                "invalidationContinuationData",
                "reloadContinuationData"
        );
        return new ChatPage(parsed.comments, next, parsed.maxVideoOffsetMs);
    }

    private String request(
            String url,
            String requestBody,
            String clientVersion,
            String visitorData
    ) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        activeConnection = connection;
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            if (requestBody != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Origin", YOUTUBE_ORIGIN);
                connection.setRequestProperty("X-Youtube-Client-Name", "1");
                connection.setRequestProperty("X-Youtube-Client-Version", clientVersion);
                if (visitorData != null && !visitorData.isEmpty()) {
                    connection.setRequestProperty("X-Goog-Visitor-Id", visitorData);
                }
                byte[] bytes = requestBody.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("YouTube returned HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return readUtf8(input);
            }
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("YouTube response exceeded " + MAX_RESPONSE_BYTES + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private List<NiconicoComment> deduplicate(List<NiconicoComment> comments) {
        List<NiconicoComment> result = new ArrayList<>();
        for (NiconicoComment comment : comments) {
            if (comment.id != null && !comment.id.isEmpty()) {
                if (!seenIds.add(comment.id)) {
                    continue;
                }
                seenIdOrder.addLast(comment.id);
                while (seenIdOrder.size() > MAX_SEEN_IDS) {
                    seenIds.remove(seenIdOrder.removeFirst());
                }
            }
            result.add(comment);
        }
        return result;
    }

    private void clearSeenIds() {
        seenIds.clear();
        seenIdOrder.clear();
    }

    static JSONObject parseYoutubeConfig(String html) throws JSONException {
        JSONObject result = new JSONObject();
        int searchIndex = 0;
        while (searchIndex < html.length()) {
            int markerIndex = html.indexOf("ytcfg.set(", searchIndex);
            if (markerIndex < 0) {
                break;
            }
            JSONObject value = parseObjectAt(html, markerIndex + "ytcfg.set(".length());
            if (value != null) {
                Iterator<String> keys = value.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    result.put(key, value.opt(key));
                }
            }
            searchIndex = markerIndex + "ytcfg.set(".length();
        }
        return result;
    }

    static JSONObject parseMarkedObject(String text, String... markers) {
        for (String marker : markers) {
            int markerIndex = text.indexOf(marker);
            if (markerIndex >= 0) {
                JSONObject value = parseObjectAt(text, markerIndex + marker.length());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    static JSONObject parseObjectAt(String text, int startIndex) {
        int start = text.indexOf('{', startIndex);
        if (start < 0) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char value = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }

            if (value == '"') {
                inString = true;
            } else if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        return new JSONObject(text.substring(start, index + 1));
                    } catch (JSONException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static JSONObject getObjectPath(JSONObject root, String... path) {
        JSONObject value = root;
        for (String key : path) {
            value = value.optJSONObject(key);
            if (value == null) {
                return null;
            }
        }
        return value;
    }

    private static JSONArray getReplayFilterItems(JSONObject data) {
        JSONObject liveChat = getLiveChatContinuation(data);
        if (liveChat == null) {
            liveChat = getObjectPath(
                    data,
                    "contents",
                    "twoColumnWatchNextResults",
                    "conversationBar",
                    "liveChatRenderer"
            );
        }
        JSONObject header = liveChat == null
                ? null
                : liveChat.optJSONObject("header");
        JSONObject headerRenderer = header == null
                ? null
                : header.optJSONObject("liveChatHeaderRenderer");
        JSONObject viewSelector = headerRenderer == null
                ? null
                : headerRenderer.optJSONObject("viewSelector");
        JSONObject menu = viewSelector == null
                ? null
                : viewSelector.optJSONObject("sortFilterSubMenuRenderer");
        return menu == null ? null : menu.optJSONArray("subMenuItems");
    }

    private static String getFilterItemText(JSONObject item) {
        return extractRendererText(item.opt("title"))
                + " " + extractRendererText(item.opt("accessibility"))
                + " " + extractRendererText(item.opt("subtitle"));
    }

    private static String extractRendererText(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (!(value instanceof JSONObject)) {
            return "";
        }
        JSONObject object = (JSONObject) value;
        String text = extractText(object);
        if (!text.isEmpty()) {
            return text;
        }
        JSONObject accessibilityData = object.optJSONObject("accessibilityData");
        return accessibilityData == null
                ? ""
                : accessibilityData.optString("label", "");
    }

    private static boolean isEndedLiveBroadcast(JSONObject playerResponse) {
        JSONObject microformat = playerResponse == null
                ? null
                : playerResponse.optJSONObject("microformat");
        JSONObject playerMicroformat = microformat == null
                ? null
                : microformat.optJSONObject("playerMicroformatRenderer");
        JSONObject liveDetails = playerMicroformat == null
                ? null
                : playerMicroformat.optJSONObject("liveBroadcastDetails");
        return liveDetails != null && !liveDetails.optString("endTimestamp", "").isEmpty();
    }

    private static JSONObject getLiveChatContinuation(JSONObject response) {
        JSONObject contents = response.optJSONObject("continuationContents");
        if (contents == null) {
            JSONObject nestedResponse = response.optJSONObject("response");
            contents = nestedResponse == null
                    ? null
                    : nestedResponse.optJSONObject("continuationContents");
        }
        return contents == null ? null : contents.optJSONObject("liveChatContinuation");
    }

    static Continuation findContinuation(Object value, String... keys) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                Continuation found = findContinuation(array.opt(index), keys);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!(value instanceof JSONObject)) {
            return null;
        }

        JSONObject object = (JSONObject) value;
        for (String key : keys) {
            JSONObject continuationData = object.optJSONObject(key);
            if (continuationData == null) {
                continue;
            }
            String continuation = continuationData.optString("continuation", "");
            if (!continuation.isEmpty()) {
                return new Continuation(
                        continuation,
                        continuationData.optLong("timeoutMs", DEFAULT_CONTINUATION_TIMEOUT_MS)
                );
            }
        }

        Iterator<String> childKeys = object.keys();
        while (childKeys.hasNext()) {
            Continuation found = findContinuation(object.opt(childKeys.next()), keys);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static List<NiconicoComment> parseComments(JSONArray actions) {
        List<NiconicoComment> comments = new ArrayList<>();
        if (actions == null) {
            return comments;
        }

        for (int index = 0; index < actions.length(); index++) {
            JSONObject action = actions.optJSONObject(index);
            JSONObject addAction = action == null
                    ? null
                    : action.optJSONObject("addChatItemAction");
            JSONObject item = addAction == null ? null : addAction.optJSONObject("item");
            NiconicoComment comment = parseComment(item, -1);
            if (comment != null) {
                comments.add(comment);
            }
        }
        return comments;
    }

    static ReplayParseResult parseReplayComments(JSONArray actions) {
        List<NiconicoComment> comments = new ArrayList<>();
        long maxVideoOffsetMs = -1;
        if (actions == null) {
            return new ReplayParseResult(comments, maxVideoOffsetMs);
        }

        for (int index = 0; index < actions.length(); index++) {
            JSONObject action = actions.optJSONObject(index);
            JSONObject replayAction = action == null
                    ? null
                    : action.optJSONObject("replayChatItemAction");
            if (replayAction == null) {
                continue;
            }

            long rawOffsetMs;
            try {
                rawOffsetMs = Long.parseLong(replayAction.getString("videoOffsetTimeMsec"));
            } catch (Exception ignored) {
                continue;
            }
            if (rawOffsetMs < 0) {
                continue;
            }
            maxVideoOffsetMs = Math.max(maxVideoOffsetMs, rawOffsetMs);

            JSONArray innerActions = replayAction.optJSONArray("actions");
            if (innerActions == null) {
                continue;
            }
            for (int innerIndex = 0; innerIndex < innerActions.length(); innerIndex++) {
                JSONObject innerAction = innerActions.optJSONObject(innerIndex);
                JSONObject addAction = innerAction == null
                        ? null
                        : innerAction.optJSONObject("addChatItemAction");
                JSONObject item = addAction == null ? null : addAction.optJSONObject("item");
                if (isBeforeReplayStart(item)) {
                    continue;
                }
                NiconicoComment comment = parseComment(item, rawOffsetMs);
                if (comment != null) {
                    comments.add(comment);
                }
            }
        }
        return new ReplayParseResult(comments, maxVideoOffsetMs);
    }

    private static boolean isBeforeReplayStart(JSONObject item) {
        JSONObject renderer = getCommentRenderer(item);
        if (renderer == null) {
            return false;
        }
        String timestampText = extractText(renderer.optJSONObject("timestampText")).trim();
        return timestampText.startsWith("-") || timestampText.startsWith("\u2212");
    }

    private static NiconicoComment parseComment(JSONObject item, long videoOffsetMs) {
        if (item == null) {
            return null;
        }

        JSONObject paid = item.optJSONObject("liveChatPaidMessageRenderer");
        JSONObject membership = item.optJSONObject("liveChatMembershipItemRenderer");
        JSONObject renderer = getCommentRenderer(item);
        if (renderer == null) {
            return null;
        }

        ParsedMessage message = parseMessage(renderer.optJSONObject("message"));
        if (message.text.isEmpty() || message.text.length() > MAX_COMMENT_LENGTH) {
            return null;
        }

        boolean isMembership = membership != null || hasMembershipBadge(renderer);
        int backgroundColor = getPaidMessageBackgroundColor(paid);
        return new NiconicoComment(
                renderer.optString("id", ""),
                message.text,
                message.segments,
                isMembership,
                backgroundColor,
                videoOffsetMs
        );
    }

    private static JSONObject getCommentRenderer(JSONObject item) {
        if (item == null) {
            return null;
        }
        JSONObject renderer = item.optJSONObject("liveChatTextMessageRenderer");
        if (renderer == null) {
            renderer = item.optJSONObject("liveChatPaidMessageRenderer");
        }
        if (renderer == null) {
            renderer = item.optJSONObject("liveChatMembershipItemRenderer");
        }
        if (renderer == null) {
            renderer = item.optJSONObject("liveChatViewerEngagementMessageRenderer");
        }
        return renderer;
    }

    private static String extractText(JSONObject textRenderer) {
        return parseMessage(textRenderer).text;
    }

    private static ParsedMessage parseMessage(JSONObject textRenderer) {
        List<NiconicoComment.Segment> segments = new ArrayList<>();
        if (textRenderer == null) {
            return new ParsedMessage("", segments);
        }
        String simpleText = textRenderer.optString("simpleText", "");
        if (!simpleText.isEmpty()) {
            String normalized = normalizeMessageText(simpleText).trim();
            if (!normalized.isEmpty()) {
                segments.add(NiconicoComment.Segment.text(normalized));
            }
            return new ParsedMessage(normalized, segments);
        }

        JSONArray runs = textRenderer.optJSONArray("runs");
        if (runs == null) {
            return new ParsedMessage("", segments);
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < runs.length(); index++) {
            JSONObject run = runs.optJSONObject(index);
            if (run == null) {
                continue;
            }
            String text = normalizeMessageText(run.optString("text", ""));
            if (!text.isEmpty()) {
                result.append(text);
                segments.add(NiconicoComment.Segment.text(text));
                continue;
            }

            JSONObject emoji = run.optJSONObject("emoji");
            JSONArray shortcuts = emoji == null ? null : emoji.optJSONArray("shortcuts");
            String fallback = shortcuts == null ? "" : shortcuts.optString(0, "");
            if (fallback.isEmpty() && emoji != null) {
                fallback = emoji.optString("emojiId", "");
            }
            if (!fallback.isEmpty()) {
                result.append(fallback);
                String imageUrl = getBestEmojiImageUrl(emoji);
                segments.add(imageUrl.isEmpty()
                        ? NiconicoComment.Segment.text(fallback)
                        : NiconicoComment.Segment.emoji(fallback, imageUrl));
            }
        }
        return new ParsedMessage(result.toString().trim(), segments);
    }

    private static String normalizeMessageText(String text) {
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private static String getBestEmojiImageUrl(JSONObject emoji) {
        JSONObject image = emoji == null ? null : emoji.optJSONObject("image");
        JSONArray thumbnails = image == null ? null : image.optJSONArray("thumbnails");
        if (thumbnails == null) {
            return "";
        }

        String bestUrl = "";
        long bestArea = -1;
        for (int index = 0; index < thumbnails.length(); index++) {
            JSONObject thumbnail = thumbnails.optJSONObject(index);
            if (thumbnail == null) {
                continue;
            }
            String url = thumbnail.optString("url", "");
            if (url.isEmpty()) {
                continue;
            }
            long area = (long) thumbnail.optInt("width", 0)
                    * thumbnail.optInt("height", 0);
            if (bestUrl.isEmpty() || area >= bestArea) {
                bestArea = area;
                bestUrl = url.startsWith("//") ? "https:" + url : url;
            }
        }
        return bestUrl;
    }

    private static boolean hasMembershipBadge(JSONObject renderer) {
        JSONArray badges = renderer.optJSONArray("authorBadges");
        if (badges == null) {
            return false;
        }
        for (int index = 0; index < badges.length(); index++) {
            JSONObject badge = badges.optJSONObject(index);
            JSONObject badgeRenderer = badge == null
                    ? null
                    : badge.optJSONObject("liveChatAuthorBadgeRenderer");
            if (badgeRenderer != null && badgeRenderer.has("customThumbnail")) {
                return true;
            }
        }
        return false;
    }

    private static int getPaidMessageBackgroundColor(JSONObject paidRenderer) {
        if (paidRenderer == null) {
            return 0;
        }
        String key = paidRenderer.has("bodyBackgroundColor")
                ? "bodyBackgroundColor"
                : "headerBackgroundColor";
        if (!paidRenderer.has(key)) {
            return 0;
        }
        long color = paidRenderer.optLong(key);
        return (97 << 24) | ((int) color & 0x00ffffff);
    }

    private static String getVisitorData(JSONObject context) {
        JSONObject client = context.optJSONObject("client");
        return client == null ? "" : client.optString("visitorData", "");
    }

    private static String stripJsonPrefix(String value) {
        int start = value.indexOf('{');
        return start > 0 ? value.substring(start) : value;
    }

    static long getLivePollDelayMs(long value) {
        if (value <= 0) {
            return LIVE_POLL_MAX_DELAY_MS;
        }
        return Math.max(LIVE_POLL_MIN_DELAY_MS, Math.min(LIVE_POLL_MAX_DELAY_MS, value));
    }

    private void sleepBeforeRetry(int attempt) {
        sleep(Math.min(15_000L, attempt * 3_000L));
    }

    private void sleep(long milliseconds) {
        if (stopped) {
            return;
        }
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
    }

    private static final class ParsedMessage {
        final String text;
        final List<NiconicoComment.Segment> segments;

        ParsedMessage(String text, List<NiconicoComment.Segment> segments) {
            this.text = text;
            this.segments = segments;
        }
    }

    private static final class PageState {
        final String apiKey;
        final String clientVersion;
        final JSONObject context;
        final Continuation continuation;
        final boolean replay;

        PageState(
                String apiKey,
                String clientVersion,
                JSONObject context,
                Continuation continuation,
                boolean replay
        ) {
            this.apiKey = apiKey;
            this.clientVersion = clientVersion;
            this.context = context;
            this.continuation = continuation;
            this.replay = replay;
        }
    }

    private static final class ChatPage {
        final List<NiconicoComment> comments;
        final Continuation continuation;
        final long maxVideoOffsetMs;

        ChatPage(
                List<NiconicoComment> comments,
                Continuation continuation,
                long maxVideoOffsetMs
        ) {
            this.comments = comments;
            this.continuation = continuation;
            this.maxVideoOffsetMs = maxVideoOffsetMs;
        }
    }

    static final class ReplayParseResult {
        final List<NiconicoComment> comments;
        final long maxVideoOffsetMs;

        ReplayParseResult(List<NiconicoComment> comments, long maxVideoOffsetMs) {
            this.comments = comments;
            this.maxVideoOffsetMs = maxVideoOffsetMs;
        }
    }

    static final class Continuation {
        final String value;
        final long timeoutMs;

        Continuation(String value, long timeoutMs) {
            this.value = value;
            this.timeoutMs = timeoutMs;
        }
    }
}
