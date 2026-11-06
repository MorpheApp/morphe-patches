package app.morphe.extension.reddit.patches.archive;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** A credential-free client for Arctic Shift's public posts/comments search API. */
public final class ArchiveClient implements ArchivePager.Source {
    static final String ORIGIN = "https://arctic-shift.photon-reddit.com";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Object RATE_LOCK = new Object();
    private static long nextRequestAt;
    private static long limitedUntil;
    private final Transport transport;

    public ArchiveClient() { this(new HttpTransport()); }
    ArchiveClient(Transport transport) { this.transport = transport; }

    public static final class Cancellation {
        private volatile boolean cancelled;
        private HttpURLConnection connection;
        public synchronized void cancel() {
            cancelled = true;
            if (connection != null) connection.disconnect();
        }
        synchronized void attach(HttpURLConnection value) throws InterruptedIOException {
            check();
            connection = value;
        }
        synchronized void detach() { connection = null; }
        public void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
        }
        void sleep(long millis) throws InterruptedIOException {
            long end = System.nanoTime() + millis * 1_000_000L;
            while (System.nanoTime() < end) {
                check();
                try { Thread.sleep(Math.min(250, Math.max(1, (end - System.nanoTime()) / 1_000_000L))); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); check(); }
            }
            check();
        }
    }

    static final class Response {
        final int status;
        final String body;
        final Map<String, List<String>> headers;
        Response(int status, String body, Map<String, List<String>> headers) {
            this.status = status; this.body = body; this.headers = headers;
        }
        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    interface Transport { Response get(URL url, Cancellation cancellation) throws IOException; }

    private static final class HttpTransport implements Transport {
        @Override public Response get(URL url, Cancellation cancellation) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                cancellation.attach(connection);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "RedditApp-ProfileArchive/0.1");
                // No Reddit API client, OAuth token, session headers, or cookies are copied.
                connection.setRequestProperty("Cookie", "");
                connection.setRequestProperty("Authorization", "");
                int code = connection.getResponseCode();
                String body = "";
                if (code == 200) {
                    try (InputStream input = connection.getInputStream();
                         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            cancellation.check();
                            if (output.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Archive response is too large.");
                            output.write(buffer, 0, count);
                        }
                        body = output.toString(StandardCharsets.UTF_8.name());
                    }
                }
                cancellation.check();
                return new Response(code, body, connection.getHeaderFields());
            } finally {
                cancellation.detach();
                connection.disconnect();
            }
        }
    }

    @Override public List<ArchiveItem> search(String username, boolean comments, Long before,
                                              Long after, int limit, Cancellation cancellation) throws IOException {
        URL url = searchUrl(username, comments, before, after, limit);
        return parse(request(url, cancellation), username, comments, limit);
    }

    /** Full records expose preview/gallery fields unavailable in the API's fields projection. */
    public Map<String, ArchivePostDetails> details(List<String> ids, Cancellation cancellation) throws IOException {
        URL url = detailsUrl(ids);
        return parseDetails(request(url, cancellation), new LinkedHashSet<>(ids));
    }

    public ArchiveItem record(String username, String id, boolean comments, Cancellation cancellation) throws IOException {
        if (ArchiveEligibility.username(username, "VISITOR_POST", true, false, true) == null
                || username.contains("/") || id == null || !id.matches("[a-z0-9]{1,16}")) throw new IOException("Invalid archive record.");
        List<ArchiveItem> records = parse(request(new URL(ORIGIN + "/api/" + (comments ? "comments" : "posts")
                + "/ids?ids=" + id), cancellation), username, comments, 1);
        if (records.size() != 1 || !records.get(0).id.equals(id)) throw new IOException("This archived item is no longer available.");
        return records.get(0);
    }

    /** ID recovery does not know the author: Reddit may have replaced it with [deleted]. */
    public Map<String, ArchiveItem> records(List<String> ids, boolean comments, Cancellation cancellation) throws IOException {
        return parseRecords(request(recordsUrl(ids, comments), cancellation), new LinkedHashSet<>(ids), comments);
    }

    static URL recordsUrl(List<String> ids, boolean comments) throws IOException {
        if (ids.isEmpty() || ids.size() > 8 || new LinkedHashSet<>(ids).size() != ids.size())
            throw new IOException("Invalid recovery batch.");
        for (String value : ids) if (value == null || !value.matches("[a-z0-9]{1,16}"))
            throw new IOException("Invalid recovery ID.");
        return new URL(ORIGIN + "/api/" + (comments ? "comments" : "posts") + "/ids?ids=" + String.join(",", ids));
    }

    static Map<String, ArchiveItem> parseRecords(String body, Set<String> requested, boolean comments) throws IOException {
        try {
            JSONArray rows = new JSONObject(body).getJSONArray("data");
            if (rows.length() > requested.size()) throw new JSONException("Unexpected recovery batch size");
            Map<String, ArchiveItem> result = new LinkedHashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String value = id(row.getString("id"), comments ? "t1_" : "t3_");
                String fullname = (comments ? "t1_" : "t3_") + value;
                if (!requested.contains(value) || result.containsKey(fullname)) throw new JSONException("Unexpected recovery ID");
                long time = row.getLong("created_utc");
                if (time <= 0 || time > 4102444800L) throw new JSONException("Unexpected recovery date");
                String author = recoveryText(row, "author");
                if (!author.isEmpty() && !ArchiveRecovery.placeholder(author)
                        && !author.matches("[A-Za-z0-9_-]{1,64}")) throw new JSONException("Invalid recovery author");
                String link = comments ? id(row.getString("link_id"), "t3_") : "";
                result.put(fullname, new ArchiveItem(value, author, recoveryText(row, "subreddit"), time,
                        recoveryText(row, "title"), recoveryText(row, comments ? "body" : "selftext"),
                        link, "", null, comments));
            }
            return java.util.Collections.unmodifiableMap(result);
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Archive returned an invalid recovery response.", error);
        }
    }

    private static String recoveryText(JSONObject row, String key) throws JSONException {
        if (row.isNull(key)) return "";
        Object value = row.get(key);
        if (!(value instanceof String)) throw new JSONException("Invalid recovery text");
        String text = (String) value;
        if (text.length() > 100000) throw new JSONException("Recovery text is too large");
        return text;
    }

    static URL detailsUrl(List<String> ids) throws IOException {
        if (ids.isEmpty() || ids.size() > 8) throw new IOException("Invalid preview batch.");
        for (String value : ids) {
            if (!value.matches("[a-z0-9]{1,16}")) throw new IOException("Invalid preview ID.");
        }
        return new URL(ORIGIN + "/api/posts/ids?ids=" + String.join(",", ids));
    }

    static Map<String, ArchivePostDetails> parseDetails(String body, Set<String> requested) throws IOException {
        try {
            JSONArray rows = new JSONObject(body).getJSONArray("data");
            if (rows.length() > requested.size()) throw new JSONException("Unexpected preview batch size");
            Map<String, ArchivePostDetails> result = new LinkedHashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String value = id(row.getString("id"), "t3_");
                if (!requested.contains(value) || result.containsKey(value)) throw new JSONException("Unexpected preview ID");
                result.put(value, ArchivePostDetails.parse(row));
            }
            return result;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Archive previews are unavailable.", error);
        }
    }

    private String request(URL url, Cancellation cancellation) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            cancellation.check();
            long wait;
            synchronized (RATE_LOCK) {
                long now = System.currentTimeMillis();
                if (limitedUntil - now > 10000) throw rateLimit(limitedUntil - now);
                long start = Math.max(now, Math.max(nextRequestAt, limitedUntil));
                wait = start - now;
                nextRequestAt = start + 1000;
            }
            cancellation.sleep(wait);
            Response response;
            try { response = transport.get(url, cancellation); }
            catch (IOException error) {
                cancellation.check();
                if (attempt == 0) { cancellation.sleep(1000); continue; }
                throw new IOException("Could not reach the archive. Check your connection and retry.", error);
            }
            if (response.status == 200) return response.body;
            if (response.status == 429) {
                long delay = retryDelayMillis(response, System.currentTimeMillis());
                synchronized (RATE_LOCK) {
                    long now = System.currentTimeMillis();
                    limitedUntil = Math.max(limitedUntil, now + Math.min(delay, Long.MAX_VALUE - now));
                }
                if (attempt == 0 && delay <= 10000) continue;
                throw rateLimit(delay);
            }
            if (response.status >= 500 && response.status <= 599 && attempt == 0) {
                cancellation.sleep(1000);
                continue;
            }
            throw new IOException("Archive unavailable (HTTP " + response.status + "). Try again later.");
        }
        throw new IOException("Archive unavailable. Try again later.");
    }

    static URL searchUrl(String username, boolean comments, Long before, Long after, int limit) throws IOException {
        if (ArchiveEligibility.username(username, "VISITOR_POST", true, false, true) == null
                || username.contains("/") || limit < 1 || limit > 100) throw new IOException("Invalid archive request.");
        String fields = "id,author,subreddit,created_utc,score," + (comments ? "body,link_id"
                : "title,selftext,post_hint,num_comments,link_flair_text,over_18,spoiler");
        return new URL(ORIGIN + "/api/" + (comments ? "comments" : "posts") + "/search?author="
                + username + "&sort=desc&limit=" + limit + "&fields=" + fields
                + (before == null ? "" : "&before=" + before)
                + (after == null ? "" : "&after=" + after));
    }

    static List<ArchiveItem> parse(String body, String username, boolean comments, int limit) throws IOException {
        try {
            JSONArray data = new JSONObject(body).getJSONArray("data");
            if (data.length() > limit) throw new JSONException("Unexpected page size");
            List<ArchiveItem> result = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject row = data.getJSONObject(i);
                String id = id(row.getString("id"), comments ? "t1_" : "t3_");
                String author = row.getString("author");
                long time = row.getLong("created_utc");
                if (!author.equalsIgnoreCase(username) || time <= 0 || time > 4102444800L) throw new JSONException("Unexpected author or date");
                String link = comments ? id(row.getString("link_id"), "t3_") : "";
                Object score = row.opt("score");
                result.add(new ArchiveItem(id, author, text(row, "subreddit"), time,
                        text(row, "title"), text(row, comments ? "body" : "selftext"), link,
                        text(row, "post_hint"), score instanceof Number ? ((Number) score).longValue() : null, comments,
                        ArchivePostDetails.count(row, "num_comments"), text(row, "link_flair_text"),
                        row.optBoolean("over_18"), row.optBoolean("spoiler")));
            }
            return result;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Archive returned an invalid response. Try again later.", error);
        }
    }

    private static String text(JSONObject row, String key) {
        return row.isNull(key) ? "" : row.optString(key, "");
    }
    private static String id(String id, String prefix) {
        if (id.startsWith(prefix)) id = id.substring(3);
        if (!id.matches("[a-zA-Z0-9]{1,16}")) throw new IllegalArgumentException("Invalid Reddit ID");
        return id.toLowerCase(Locale.ROOT);
    }

    static long retryDelayMillis(Response response, long now) {
        long delay = 0;
        String retry = response.header("Retry-After");
        delay = Math.max(delay, seconds(retry));
        if (retry != null) {
            try { delay = Math.max(delay, ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now); }
            catch (RuntimeException ignored) { }
        }
        delay = Math.max(delay, seconds(response.header("X-RateLimit-Reset")));
        long at = seconds(response.header("X-RateLimit-Reset-At"));
        if (at > 0) delay = Math.max(delay, at - now);
        return delay > 0 ? delay : 30000;
    }
    private static long seconds(String value) {
        if (value == null) return 0;
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) && number > 0 && number < Long.MAX_VALUE / 1000.0
                    ? (long) Math.ceil(number * 1000) : 0;
        } catch (NumberFormatException ignored) { return 0; }
    }
    private static IOException rateLimit(long delay) {
        return new IOException("Archive rate limit reached. Retry in about " + Math.max(1, delay / 1000) + " seconds.");
    }
}
