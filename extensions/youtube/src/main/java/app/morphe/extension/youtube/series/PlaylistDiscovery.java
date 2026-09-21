package app.morphe.extension.youtube.series;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.*;

/** On-demand public discovery. Search results are candidates, never proof of membership. */
final class PlaylistDiscovery {
    static final int MAX_REQUESTS = 18, MAX_BYTES = 16 * 1024 * 1024, MAX_CANDIDATES = 8;
    static final long TIME_MS = 25000, HIT_TTL = 3600000, MISS_TTL = 300000;

    interface Transport {
        CatalogClient.Reply request(String endpoint, String body, int timeout, int bytes)
                throws IOException;
    }

    static final class Match {
        final String id, title, owner, thumbnailVideo;

        Match(String id, String title, String owner, String thumbnailVideo) {
            this.id = id;
            this.title =
                    (title.isEmpty() ? id : title)
                            .substring(0, Math.min(500, (title.isEmpty() ? id : title).length()));
            this.owner = owner.substring(0, Math.min(120, owner.length()));
            this.thumbnailVideo = thumbnailVideo;
        }
    }

    private static final class Cached {
        final long until;
        final List<Match> matches;

        Cached(long until, List<Match> matches) {
            this.until = until;
            this.matches = matches;
        }
    }

    private final Transport transport;
    private final LongSupplier clock;
    private final java.util.function.Consumer<Exception> errors;
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(32, .75f, true);
    private volatile long retryUntil;

    PlaylistDiscovery() {
        this(CatalogClient::request, () -> System.nanoTime() / 1000000);
    }

    PlaylistDiscovery(Transport transport, LongSupplier clock) {
        this(transport, clock, ignored -> {});
    }

    PlaylistDiscovery(
            Transport transport,
            LongSupplier clock,
            java.util.function.Consumer<Exception> errors) {
        this.transport = transport;
        this.clock = clock;
        this.errors = errors;
        retryUntil = clock.getAsLong();
    }

    List<Match> find(String video) throws IOException {
        if (!video.matches("[A-Za-z0-9_-]{11}")) return Collections.emptyList();
        synchronized (cache) {
            Cached saved = cache.get(video);
            if (saved != null && clock.getAsLong() < saved.until) return saved.matches;
        }
        if (clock.getAsLong() < retryUntil) return Collections.emptyList();
        Budget budget = new Budget();
        List<Match> found = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        try {
            JSONObject details =
                    budget.json("player", new JSONObject().put("videoId", video))
                            .optJSONObject("videoDetails");
            if (details == null || !video.equals(details.optString("videoId"))) {
                errors.accept(new IOException("Public player metadata unavailable"));
                return Collections.emptyList();
            }
            String title = details.optString("title"),
                    author = details.optString("author"),
                    channel = details.optString("channelId");
            LinkedHashMap<String, Match> linked = new LinkedHashMap<>();
            for (String id : descriptionPlaylists(details.optString("shortDescription")))
                linked.put(id, new Match(id, "", "", ""));
            verify(linked.values(), video, checked, found, budget);
            if (!found.isEmpty()) return remember(video, found);

            // The channel's playlist tab can expose seasons absent from public search.
            // Failure here must leave the existing search and manual fallbacks available.
            try {
                List<Candidate> listed =
                        candidates(
                                ChannelPlaylists.fetch(channel, budget::webBrowse), title, channel);
                verifyRanked(
                        listed,
                        video,
                        checked,
                        found,
                        budget,
                        Math.min(MAX_CANDIDATES, checked.size() + 4));
                if (!found.isEmpty()) return remember(video, found);
            } catch (IOException | JSONException failure) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("Discovery cancelled");
                budget.incomplete = true;
                errors.accept(failure);
            }

            // Search remains a fallback for channels without a usable playlist tab.
            for (String query : queries(title, author)) {
                JSONObject response =
                        budget.json(
                                "search",
                                new JSONObject().put("query", query).put("params", "EgIQAw=="));
                List<Candidate> candidates = candidates(response, title, channel);
                verifyRanked(candidates, video, checked, found, budget, MAX_CANDIDATES);
                if (!found.isEmpty() || checked.size() >= MAX_CANDIDATES) break;
            }
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            budget.incomplete = true;
            errors.accept(e);
            // A partial search may still have fully verified individual matches.
        } catch (IOException | JSONException failure) {
            budget.incomplete = true;
            errors.accept(failure);
            // Unavailable discovery never changes the library or hides the manual fallback.
        }
        if (found.isEmpty() && budget.incomplete) return Collections.emptyList();
        return remember(video, found);
    }

    private void verifyRanked(
            List<Candidate> candidates,
            String video,
            Set<String> checked,
            List<Match> found,
            Budget budget,
            int limit)
            throws IOException {
        for (int start = 0; start < candidates.size() && checked.size() < limit; ) {
            int end = start;
            List<Match> group = new ArrayList<>();
            while (end < candidates.size()
                    && candidates.get(end).score == candidates.get(start).score) {
                Match match = candidates.get(end++).match;
                if (!checked.contains(match.id) && group.size() < limit - checked.size())
                    group.add(match);
            }
            verify(group, video, checked, found, budget);
            if (!found.isEmpty()) break;
            start = end;
        }
    }

    private List<Match> remember(String video, List<Match> found) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Discovery cancelled");
        List<Match> result = Collections.unmodifiableList(new ArrayList<>(found));
        synchronized (cache) {
            cache.put(
                    video,
                    new Cached(
                            clock.getAsLong() + (result.isEmpty() ? MISS_TTL : HIT_TTL), result));
            while (cache.size() > 32) cache.remove(cache.keySet().iterator().next());
        }
        return result;
    }

    private void verify(
            Collection<Match> candidates,
            String video,
            Set<String> checked,
            List<Match> found,
            Budget budget)
            throws IOException {
        for (Match candidate : candidates) {
            if (checked.size() >= MAX_CANDIDATES || found.size() >= 4) return;
            if (!checked.add(candidate.id)) continue;
            try {
                CatalogClient.Catalog catalog =
                        new CatalogClient(
                                        (body, timeout, bytes) ->
                                                budget.request("browse", body, bytes),
                                        clock)
                                .fetch(candidate.id);
                if (catalog.episodes.stream()
                        .noneMatch(e -> e.available && e.videoId.equals(video))) continue;
                String image =
                        catalog.episodes.stream()
                                .filter(e -> e.available)
                                .map(e -> e.videoId)
                                .findFirst()
                                .orElse(video);
                found.add(
                        new Match(
                                candidate.id,
                                catalog.title.isEmpty() ? candidate.title : catalog.title,
                                candidate.owner,
                                image));
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException failure) {
                budget.incomplete = true;
                errors.accept(failure);
                if (clock.getAsLong() >= budget.deadline || clock.getAsLong() < retryUntil) return;
            }
        }
    }

    private final class Budget {
        final long deadline = clock.getAsLong() + TIME_MS;
        int requests, bytes;
        boolean incomplete;

        CatalogClient.Reply request(String endpoint, String body, int limit) throws IOException {
            long remaining = deadline - clock.getAsLong();
            if (Thread.currentThread().isInterrupted()
                    || remaining <= 0
                    || requests >= MAX_REQUESTS
                    || bytes >= MAX_BYTES)
                throw new InterruptedIOException("Discovery limit reached");
            if (clock.getAsLong() < retryUntil) throw new IOException("Discovery throttled");
            requests++;
            int allowed = Math.min(Math.min(CatalogClient.PAGE_BYTES, MAX_BYTES - bytes), limit);
            CatalogClient.Reply reply =
                    transport.request(endpoint, body, (int) Math.min(remaining, 12000), allowed);
            bytes += reply.bytes;
            if (reply.bytes > allowed || clock.getAsLong() >= deadline)
                throw new InterruptedIOException("Discovery limit reached");
            if (reply.status == 429 || reply.status == 503)
                retryUntil =
                        clock.getAsLong() + Math.max(30000, Math.min(3600000, reply.retryAfterMs));
            if (reply.status != 200)
                throw new IOException(
                        "Discovery request failed: " + endpoint + " HTTP " + reply.status);
            return reply;
        }

        JSONObject webBrowse(JSONObject payload) throws IOException, JSONException {
            payload.put(
                    "context",
                    new JSONObject()
                            .put(
                                    "client",
                                    new JSONObject()
                                            .put("clientName", "WEB")
                                            .put("clientVersion", "2.20260910.00.00")
                                            .put("hl", "en")
                                            .put("gl", "US")));
            return new JSONObject(
                    request("browse", payload.toString(), CatalogClient.PAGE_BYTES).body);
        }

        JSONObject json(String endpoint, JSONObject payload) throws IOException, JSONException {
            payload.put(
                    "context",
                    new JSONObject()
                            .put(
                                    "client",
                                    new JSONObject()
                                            .put("clientName", "ANDROID")
                                            .put("clientVersion", CatalogClient.CLIENT_VERSION)
                                            .put("androidSdkVersion", 30)
                                            .put("hl", "en")
                                            .put("gl", "US")));
            return new JSONObject(
                    request(endpoint, payload.toString(), CatalogClient.PAGE_BYTES).body);
        }
    }

    static List<String> descriptionPlaylists(String description) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher urls =
                Pattern.compile("https?://[^\\s<>\"]+")
                        .matcher(description.substring(0, Math.min(description.length(), 20000)));
        while (urls.find() && ids.size() < 4) {
            try {
                String id = PlaylistInput.parse(urls.group().replaceAll("[).,;]+$", ""));
                if (eligible(id)) ids.add(id);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new ArrayList<>(ids);
    }

    static boolean eligible(String id) {
        return PlaylistInput.suggestible(id) && !id.startsWith("UU") && !id.startsWith("UUL");
    }

    private static final Pattern SEASON =
            Pattern.compile("(?i)\\b(?:season|series)\\s+0*(\\d{1,3})\\b");

    private static String seasonNumber(String title) {
        Matcher matcher = SEASON.matcher(title);
        return matcher.find() ? matcher.group(1) : "";
    }

    static List<String> queries(String title, String author) {
        // Season identity is more useful than an episode subtitle or a format suffix.
        Matcher season = SEASON.matcher(title);
        String series;
        if (season.find()) {
            series = title.substring(0, season.end()).replaceAll("[|｜]", " ").trim();
        } else {
            String[] parts = title.split("[|｜]");
            series = "";
            for (int i = parts.length - 1; i >= 0 && series.isEmpty(); i--) {
                String part = parts[i].trim();
                if (!part.matches("(?i)(?:full\\s+episodes?|official\\s+video|4k|hd)"))
                    series = part;
            }
            series =
                    series.replaceAll(
                                    "\\[[^\\]]*\\]|(?i)\\b(?:episode|ep|part)\\s*#?\\s*\\d+|#\\d+",
                                    " ")
                            .replaceAll("\\s+", " ")
                            .trim();
        }
        String primary =
                series.toLowerCase(Locale.ROOT).contains(author.toLowerCase(Locale.ROOT))
                        ? series
                        : author + " " + series;
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (String value : new String[] {primary, author + " " + title}) {
            value = value.trim();
            if (!value.isEmpty()) queries.add(value.substring(0, Math.min(180, value.length())));
        }
        return new ArrayList<>(queries);
    }

    private static final class Candidate {
        final Match match;
        final int score;

        Candidate(Match match, int score) {
            this.match = match;
            this.score = score;
        }
    }

    static List<Candidate> candidates(JSONObject response, String title, String channel) {
        LinkedHashMap<String, Candidate> result = new LinkedHashMap<>();
        collect(
                response.opt("contents"),
                title.toLowerCase(Locale.ROOT),
                channel,
                result,
                0,
                new int[] {0});
        List<Candidate> sorted = new ArrayList<>(result.values());
        sorted.sort(Comparator.comparingInt((Candidate c) -> c.score).reversed());
        return sorted;
    }

    private static void collect(
            Object node,
            String title,
            String channel,
            Map<String, Candidate> result,
            int depth,
            int[] visits) {
        if (depth > 24 || visits[0]++ > 20000 || result.size() >= ChannelPlaylists.MAX_ITEMS)
            return;
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++)
                collect(array.opt(i), title, channel, result, depth + 1, visits);
        } else if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (String key :
                    new String[] {
                        "compactPlaylistRenderer", "playlistRenderer", "gridPlaylistRenderer"
                    }) {
                JSONObject playlist = object.optJSONObject(key);
                if (playlist == null) continue;
                String id = playlist.optString("playlistId");
                if (!eligible(id)) continue;
                String name = CatalogParser.text(playlist.optJSONObject("title"));
                JSONObject byline = playlist.optJSONObject("shortBylineText");
                if (byline == null) byline = playlist.optJSONObject("longBylineText");
                String owner = CatalogParser.text(byline);
                boolean creator = false;
                JSONArray runs = byline == null ? null : byline.optJSONArray("runs");
                if (runs != null)
                    for (int i = 0; i < runs.length(); i++) {
                        JSONObject run = runs.optJSONObject(i);
                        String ownerId =
                                CatalogParser.object(
                                                CatalogParser.object(run, "navigationEndpoint"),
                                                "browseEndpoint")
                                        .optString("browseId");
                        if (!channel.isEmpty() && channel.equals(ownerId)) creator = true;
                    }
                int score = creator ? 1000 : 0;
                String expectedSeason = seasonNumber(title), candidateSeason = seasonNumber(name);
                if (!expectedSeason.isEmpty() && !candidateSeason.isEmpty())
                    score += expectedSeason.equals(candidateSeason) ? 100 : -100;
                for (String word : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                    if (word.length() > 2 && title.contains(word)) score++;
                result.putIfAbsent(id, new Candidate(new Match(id, name, owner, ""), score));
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext())
                collect(object.opt(keys.next()), title, channel, result, depth + 1, visits);
        }
    }
}
