/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback.livestream;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * Fetches public live chat without touching YouTube's internal chat
 * renderer. This is the v1 chat source, chosen deliberately because it
 * does not need a version-specific fingerprint for the in-app chat UI:
 *
 * <ol>
 *   <li>Load the public live-chat page for the video and extract the
 *       initial timed continuation token.</li>
 *   <li>Poll {@code youtubei/v1/live_chat/get_live_chat} with that
 *       continuation, following the next continuation in each response.</li>
 *   <li>Deliver each new text / paid / membership message to the overlay
 *       on the main thread.</li>
 * </ol>
 *
 * Limitations, stated plainly: this only works for public streams. A
 * members-only or otherwise login-gated chat will not return messages
 * here, because no signed-in session is attached. The long-term fix is
 * to replace this source with a hook into the app's own live chat
 * renderer (see {@code LiveChatDanmakuPatch.onChatMessage}), which
 * inherits the app's session automatically. The overlay and settings do
 * not depend on which source feeds them, so that swap is localised.
 */
@SuppressWarnings("unused")
public final class LiveChatPollingSource {

    public interface Listener {
        void onDanmaku(DanmakuMessage message);
    }

    /** Public YouTube web client key (not a secret, shipped in every web client). */
    private static final String INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String CLIENT_VERSION = "2.20240111.09.00";
    private static final long DEFAULT_POLL_MS = 3000;
    private static final long ERROR_RETRY_MS = 5000;

    private static final Pattern TIMED_CONTINUATION_PATTERN = Pattern.compile(
            "\"timedContinuationData\"\\s*:\\s*\\{\\s*\"continuation\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern GENERIC_CONTINUATION_PATTERN = Pattern.compile(
            "\"continuation\"\\s*:\\s*\"([A-Za-z0-9%_\\-]{20,})\"");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> seenMessageIds = new HashSet<>();

    private volatile boolean stopped;
    private volatile Thread worker;

    public synchronized void start(String videoId, Listener listener) {
        stop();
        if (videoId == null || videoId.isEmpty() || listener == null) {
            return;
        }
        stopped = false;
        worker = new Thread(() -> runLoop(videoId, listener), "morphe-danmaku-poll");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        stopped = true;
        Thread oldWorker = worker;
        worker = null;
        if (oldWorker != null) {
            oldWorker.interrupt();
        }
        seenMessageIds.clear();
    }

    private void runLoop(String videoId, Listener listener) {
        try {
            String continuation = fetchInitialContinuation(videoId);
            if (continuation == null) {
                Logger.printDebug(() -> "Danmaku: no live chat continuation for " + videoId);
                return;
            }
            while (!stopped && continuation != null) {
                PollResult result = pollOnce(continuation, listener);
                if (result == null) {
                    sleep(ERROR_RETRY_MS);
                    continue;
                }
                continuation = result.nextContinuation;
                sleep(result.timeoutMs > 0 ? result.timeoutMs : DEFAULT_POLL_MS);
            }
        } catch (InterruptedException ignored) {
            // Stopped.
        } catch (Exception ex) {
            Logger.printException(() -> "Danmaku polling failure", ex);
        }
    }

    private void sleep(long ms) throws InterruptedException {
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }

    private String fetchInitialContinuation(String videoId) throws Exception {
        URL url = new URL("https://www.youtube.com/live_chat?is_popout=1&v=" + videoId);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            Matcher timed = TIMED_CONTINUATION_PATTERN.matcher(body);
            if (timed.find()) {
                return timed.group(1);
            }
            Matcher generic = GENERIC_CONTINUATION_PATTERN.matcher(body);
            if (generic.find()) {
                return generic.group(1);
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    private static final class PollResult {
        final String nextContinuation;
        final long timeoutMs;

        PollResult(String nextContinuation, long timeoutMs) {
            this.nextContinuation = nextContinuation;
            this.timeoutMs = timeoutMs;
        }
    }

    private PollResult pollOnce(String continuation, Listener listener) {
        try {
            URL url = new URL("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat"
                    + "?key=" + INNERTUBE_API_KEY);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject();
            JSONObject context = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", CLIENT_VERSION);
            context.put("client", client);
            body.put("context", context);
            body.put("continuation", continuation);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            } finally {
                connection.disconnect();
            }

            JSONObject root = new JSONObject(response.toString());
            JSONObject liveChat = child(child(root, "continuationContents"), "liveChatContinuation");
            if (liveChat == null) {
                return null;
            }

            JSONArray actions = liveChat.optJSONArray("actions");
            if (actions != null) {
                for (int i = 0; i < actions.length(); i++) {
                    DanmakuMessage message = parseAction(actions.optJSONObject(i));
                    if (message != null) {
                        mainHandler.post(() -> listener.onDanmaku(message));
                    }
                }
            }

            String next = null;
            long timeout = 0;
            JSONArray continuations = liveChat.optJSONArray("continuations");
            if (continuations != null) {
                for (int i = 0; i < continuations.length(); i++) {
                    JSONObject timed = continuations.optJSONObject(i)
                            .optJSONObject("timedContinuationData");
                    if (timed != null) {
                        next = timed.optString("continuation", null);
                        timeout = timed.optLong("timeoutMs", 0);
                        break;
                    }
                    JSONObject invalidation = continuations.optJSONObject(i)
                            .optJSONObject("invalidationContinuationData");
                    if (invalidation != null) {
                        next = invalidation.optString("continuation", null);
                        break;
                    }
                }
            }
            return new PollResult(next, timeout);
        } catch (Exception ex) {
            Logger.printException(() -> "Danmaku poll failure", ex);
            return null;
        }
    }

    private static JSONObject child(JSONObject parent, String key) {
        return parent == null ? null : parent.optJSONObject(key);
    }

    private DanmakuMessage parseAction(JSONObject action) {
        if (action == null) {
            return null;
        }
        JSONObject item = child(child(action, "addChatItemAction"), "item");
        JSONObject textRenderer = child(item, "liveChatTextMessageRenderer");
        if (textRenderer != null) {
            String id = textRenderer.optString("id", "");
            if (!id.isEmpty() && !seenMessageIds.add(id)) {
                return null; // Already shown.
            }
            return new DanmakuMessage(
                    runsToText(textRenderer.optJSONObject("message")),
                    simpleText(textRenderer.optJSONObject("authorName")));
        }

        JSONObject paidRenderer = child(item, "liveChatPaidMessageRenderer");
        if (paidRenderer != null) {
            String id = paidRenderer.optString("id", "");
            if (!id.isEmpty() && !seenMessageIds.add(id)) {
                return null;
            }
            int color = parseColor(paidRenderer.optString("bodyBackgroundColor", ""));
            String amount = simpleText(paidRenderer.optJSONObject("purchaseAmountText"));
            String text = runsToText(paidRenderer.optJSONObject("message"));
            String combined = amount.isEmpty() ? text : amount + " " + text;
            return new DanmakuMessage(combined, simpleText(paidRenderer.optJSONObject("authorName")),
                    true, color);
        }

        JSONObject membershipRenderer = child(item, "liveChatMembershipItemRenderer");
        if (membershipRenderer != null) {
            String id = membershipRenderer.optString("id", "");
            if (!id.isEmpty() && !seenMessageIds.add(id)) {
                return null;
            }
            return new DanmakuMessage(
                    runsToText(membershipRenderer.optJSONObject("headerSubtext")),
                    simpleText(membershipRenderer.optJSONObject("authorName")),
                    true, 0);
        }
        return null;
    }

    private static String simpleText(JSONObject object) {
        if (object == null) {
            return "";
        }
        return object.optString("simpleText", "");
    }

    private static String runsToText(JSONObject message) {
        if (message == null) {
            return "";
        }
        JSONArray runs = message.optJSONArray("runs");
        if (runs == null) {
            return simpleText(message);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            builder.append(runs.optJSONObject(i).optString("text", ""));
        }
        return builder.toString();
    }

    private static int parseColor(String raw) {
        try {
            // YouTube colours arrive as unsigned ARGB, e.g. "4294967295".
            long value = Long.parseLong(raw);
            return (int) value;
        } catch (Exception ignored) {
            return 0;
        }
    }
}
