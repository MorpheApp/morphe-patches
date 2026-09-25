package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.Episode;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded unauthenticated browse adapter. Only complete recognized catalogs are returned. */
public final class CatalogClient {
    static final String CLIENT_VERSION = "20.26.46";
    static final int PAGE_BYTES = 5 * 1024 * 1024,
            TOTAL_BYTES = 20 * 1024 * 1024,
            MAX_PAGES = 100,
            MAX_ENTRIES = 5000;

    public static final class Catalog {
        public final String title;
        public final List<Episode> episodes;

        public Catalog(String title, List<Episode> episodes) {
            if (episodes.size() > MAX_ENTRIES)
                throw new IllegalArgumentException("Catalog exceeds entry limit");
            this.title = title;
            this.episodes = Collections.unmodifiableList(new ArrayList<>(episodes));
        }
    }

    public static final class Failure extends IOException {
        public final long retryAfterMs;

        Failure(String message, long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }
    }

    interface Transport {
        Reply request(String body, int remainingMs, int maxBytes) throws IOException;
    }

    static final class Reply {
        final int status, bytes;
        final String body;
        final long retryAfterMs;

        Reply(int status, String body, int bytes, long retry) {
            this.status = status;
            this.body = body;
            this.bytes = bytes;
            retryAfterMs = retry;
        }
    }

    private final Transport transport;
    private final LongSupplier clock;

    public CatalogClient() {
        this(CatalogClient::request, () -> System.nanoTime() / 1_000_000);
    }

    CatalogClient(Transport transport, LongSupplier clock) {
        this.transport = transport;
        this.clock = clock;
    }

    public Catalog fetch(String playlistId) throws IOException {
        playlistId = PlaylistInput.parse(playlistId);
        List<Episode> episodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String token = "", visitor = "", title = "";
        int bytes = 0;
        long deadline = clock.getAsLong() + 60000;
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("Catalog refresh cancelled");
                int remaining = (int) Math.max(0, deadline - clock.getAsLong());
                if (remaining == 0) throw limited(episodes.size(), "time limit");
                JSONObject client =
                        new JSONObject()
                                .put("clientName", "ANDROID")
                                .put("clientVersion", CLIENT_VERSION)
                                .put("androidSdkVersion", 30)
                                .put("hl", "en")
                                .put("gl", "US");
                if (!visitor.isEmpty()) client.put("visitorData", visitor);
                JSONObject body =
                        new JSONObject().put("context", new JSONObject().put("client", client));
                if (token.isEmpty())
                    // YouTube's "Show unavailable videos" browse option preserves episode slots.
                    body.put("browseId", "VL" + playlistId).put("params", "wgYCCAA%3D");
                else body.put("continuation", token);
                Reply reply =
                        transport.request(
                                body.toString(),
                                remaining,
                                Math.min(PAGE_BYTES, TOTAL_BYTES - bytes));
                bytes += reply.bytes;
                if (reply.status != 200)
                    throw new Failure(
                            "Catalog unavailable (HTTP "
                                    + reply.status
                                    + "). Cached episodes were kept.",
                            Math.max(30000, reply.retryAfterMs));
                if (bytes > TOTAL_BYTES
                        || reply.bytes > PAGE_BYTES
                        || clock.getAsLong() >= deadline)
                    throw limited(episodes.size(), "download limit");
                CatalogParser.Page parsed =
                        CatalogParser.parse(reply.body, episodes.size(), page > 0);
                if (!parsed.title.isEmpty()) title = parsed.title;
                if (!parsed.visitorData.isEmpty()) visitor = parsed.visitorData;
                episodes.addAll(parsed.episodes);
                if (episodes.size() > MAX_ENTRIES) throw limited(episodes.size(), "episode limit");
                token = parsed.continuation;
                if (token.isEmpty()) return new Catalog(title, episodes);
                if (!seen.add(token))
                    throw new Failure(
                            "Catalog continuation repeated. Cached episodes were kept.", 30000);
                if (bytes >= TOTAL_BYTES) throw limited(episodes.size(), "download limit");
            }
            throw limited(episodes.size(), "page limit");
        } catch (JSONException failure) {
            throw new Failure(failure.getMessage(), 30000);
        }
    }

    private static Failure limited(int count, String reason) {
        return new Failure(
                "Catalog incomplete: "
                        + count
                        + " episodes received before the "
                        + reason
                        + ". Cached episodes were kept.",
                30000);
    }

    static long retryDelay(String value, long now) {
        if (value == null) return 30000;
        try {
            return Math.max(
                    30000, Math.min(3600000, Math.multiplyExact(Long.parseLong(value), 1000)));
        } catch (NumberFormatException | ArithmeticException ignored) {
        }
        try {
            return Math.max(
                    30000,
                    Math.min(
                            3600000,
                            java.time.ZonedDateTime.parse(
                                                    value,
                                                    java.time.format.DateTimeFormatter
                                                            .RFC_1123_DATE_TIME)
                                            .toInstant()
                                            .toEpochMilli()
                                    - now));
        } catch (java.time.format.DateTimeParseException ignored) {
            return 30000;
        }
    }

    private static Reply request(String body, int remainingMs, int maxBytes) throws IOException {
        return request("browse", body, remainingMs, maxBytes);
    }

    static Reply request(String endpoint, String body, int remainingMs, int maxBytes)
            throws IOException {
        if (!endpoint.equals("browse") && !endpoint.equals("player") && !endpoint.equals("search"))
            throw new IllegalArgumentException("Unsupported public endpoint");
        long readDeadline = System.nanoTime() / 1_000_000 + remainingMs;
        HttpURLConnection connection =
                (HttpURLConnection)
                        new URL(
                                        "https://youtubei.googleapis.com/youtubei/v1/"
                                                + endpoint
                                                + "?prettyPrint=false")
                                .openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.min(10000, remainingMs));
            connection.setReadTimeout(Math.min(20000, remainingMs));
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty(
                    "User-Agent",
                    "com.google.android.youtube/"
                            + CLIENT_VERSION
                            + " (Linux; U; Android 11) gzip");
            connection.setRequestProperty("Accept-Encoding", "identity");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(payload);
            }
            int status = connection.getResponseCode();
            long retry =
                    retryDelay(
                            connection.getHeaderField("Retry-After"), System.currentTimeMillis());
            if (status != 200) return new Reply(status, "", 0, retry);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[16384];
                int size;
                while (true) {
                    long remaining = readDeadline - System.nanoTime() / 1_000_000;
                    if (remaining <= 0)
                        throw new InterruptedIOException("Catalog refresh timed out");
                    connection.setReadTimeout((int) Math.min(20000, remaining));
                    size = input.read(chunk);
                    if (size == -1) break;
                    if (bytes.size() + size > maxBytes)
                        throw new Failure("Catalog response exceeds the download limit", 30000);
                    if (Thread.currentThread().isInterrupted()
                            || System.nanoTime() / 1_000_000 > readDeadline)
                        throw new InterruptedIOException("Catalog refresh timed out");
                    bytes.write(chunk, 0, size);
                }
            }
            return new Reply(
                    status,
                    new String(bytes.toByteArray(), StandardCharsets.UTF_8),
                    bytes.size(),
                    retry);
        } finally {
            connection.disconnect();
        }
    }
}
