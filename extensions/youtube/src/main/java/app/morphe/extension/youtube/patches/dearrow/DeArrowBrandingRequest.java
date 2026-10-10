/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 * https://github.com/MorpheApp/morphe-patches/pull/3531
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Fetches the video titles and thumbnails submitted to DeArrow (<a href="https://dearrow.ajay.app">...</a>).
 * Used by {@link app.morphe.extension.youtube.patches.originaltitles.RestoreOriginalTitlesPatch}
 * to replace the video titles, and by {@link DeArrowPatch} to show only the crowdsourced thumbnails.
 * <p>
 * The title and the thumbnail of a video are fetched with a single request.
 */
public final class DeArrowBrandingRequest {

    /**
     * DeArrow is not available, or the branding failed to fetch and can be fetched again later.
     */
    public static final class DeArrowException extends Exception {
        DeArrowException(Throwable cause) {
            super(cause);
        }
    }

    private static final String API_URL = "https://sponsor.ajay.app";

    /**
     * Videos are requested by the start of the SHA-256 hash of the video id,
     * so the server does not know which video is shown.
     */
    private static final Route GET_BRANDING = new Route(Route.Method.GET, "/api/branding/{hash_prefix}");

    /**
     * The '>' at the start of each word of a title, separated by spaces.
     */
    private static final Pattern FORMATTER_OVERRIDE_PATTERN = Pattern.compile("(^| )>");

    /**
     * The title is shown as loading until DeArrow responds, so DeArrow is not waited for long.
     */
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 2 * 1000;

    /**
     * DeArrow title of a video.
     *
     * @param title        The DeArrow title, or null if the video has no DeArrow title
     *                     or DeArrow keeps the original title.
     * @param casualTitles The original titles in lowercase with enough casual votes, for which casual mode
     *                     shows the original title instead of the DeArrow title. An empty title is of votes
     *                     that do not include the original title, and are for any original title.
     *                     Empty if casual mode is off.
     */
    public record DeArrowTitle(@Nullable String title, Set<String> casualTitles) {
        static final DeArrowTitle NONE = new DeArrowTitle(null, Collections.emptySet());

        /**
         * @return If the original title is needed to choose the title, as casual mode applies only
         *         if the casual votes are for the current original title.
         */
        public boolean needsOriginalTitle() {
            return title != null && !casualTitles.isEmpty() && !casualTitles.contains("");
        }

        /**
         * @param originalTitle The original title, or null if not needed or it failed to fetch.
         * @return The DeArrow title, or null if the video has no DeArrow title, DeArrow keeps
         *         the original title, or casual mode shows the original title.
         */
        @Nullable
        public String titleFor(@Nullable String originalTitle) {
            if (title == null) {
                return null;
            }
            if (casualTitles.contains("") || (originalTitle != null
                    && casualTitles.contains(originalTitle.trim().toLowerCase(Locale.ROOT)))) {
                Logger.printDebug(() -> "Casual mode shows the original title instead of: " + title);
                return null;
            }
            fetchedTitles.add(title);
            return title;
        }
    }

    /**
     * DeArrow title and thumbnail of a video.
     *
     * @param title         The DeArrow title.
     * @param thumbnailTime The time in seconds of the video frame of the DeArrow thumbnail, or null if the
     *                      video has no DeArrow thumbnail or DeArrow keeps the original thumbnail.
     */
    private record Branding(DeArrowTitle title, @Nullable Double thumbnailTime) {
        static final Branding NONE = new Branding(DeArrowTitle.NONE, null);
    }

    /**
     * Video id -> branding. Requests that failed are removed, so they are fetched again later.
     */
    private static final Map<String, CompletableFuture<Branding>> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * DeArrow titles that were fetched. Titles replaced before they are laid out
     * are only known by their text.
     */
    private static final Set<String> fetchedTitles = Collections.newSetFromMap(
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000)));

    private DeArrowBrandingRequest() {
    }

    /**
     * @return If the title is a DeArrow title that was fetched.
     */
    public static boolean isDeArrowTitle(String title) {
        return fetchedTitles.contains(title);
    }

    /**
     * Waits until the branding is fetched.
     *
     * @throws DeArrowException If DeArrow is not available or the title failed to fetch,
     *                          so it can be fetched again later.
     */
    public static DeArrowTitle fetchTitle(String videoId) throws DeArrowException {
        try {
            return fetch(videoId).get().title();
        } catch (ExecutionException ex) {
            throw new DeArrowException(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DeArrowException(ex);
        }
    }

    /**
     * Waits until the branding is fetched, or the timeout passes.
     *
     * @return The time in seconds of the video frame of the DeArrow thumbnail, or null if the video
     *         has no DeArrow thumbnail, DeArrow keeps the original thumbnail, or the thumbnail
     *         was not fetched before the timeout.
     */
    @Nullable
    static Double fetchThumbnailTime(String videoId, long timeoutMilliseconds) {
        try {
            return fetch(videoId).get(timeoutMilliseconds, TimeUnit.MILLISECONDS).thumbnailTime();
        } catch (TimeoutException ex) {
            Logger.printDebug(() -> "Timed out waiting for DeArrow thumbnail of: " + videoId);
        } catch (ExecutionException ex) {
            Logger.printDebug(() -> "Could not fetch DeArrow thumbnail of: " + videoId, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static CompletableFuture<Branding> fetch(String videoId) {
        synchronized (cache) {
            CompletableFuture<Branding> future = cache.get(videoId);
            if (future != null) {
                return future;
            }

            future = new CompletableFuture<>();
            // Branding fetched before DeArrow failed is still used.
            if (!DeArrowPatch.canUseDeArrowAPI()) {
                future.completeExceptionally(new IOException("DeArrow is not available"));
                return future;
            }

            CompletableFuture<Branding> newFuture = future;
            cache.put(videoId, newFuture);
            Utils.runOnBackgroundThread(() -> {
                try {
                    newFuture.complete(fetchBranding(videoId));
                } catch (Exception ex) {
                    cache.remove(videoId, newFuture);
                    newFuture.completeExceptionally(ex);
                }
            });
            return newFuture;
        }
    }

    /**
     * @throws IOException If the branding failed to fetch, so it can be fetched again later.
     */
    private static Branding fetchBranding(String videoId) throws IOException {
        Route.CompiledRoute route;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(videoId.getBytes(StandardCharsets.UTF_8));
            // The first 4 hex characters of the hash.
            route = GET_BRANDING.compile(String.format(Locale.US, "%02x%02x", hash[0], hash[1]));
        } catch (Exception ex) {
            Logger.printException(() -> "fetchBranding failure", ex);
            return Branding.NONE;
        }

        final int responseCode;
        HttpURLConnection connection;
        try {
            connection = Requester.getConnectionFromCompiledRoute(API_URL, route);
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            responseCode = connection.getResponseCode();
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not fetch DeArrow branding of: " + videoId, ex);
            DeArrowPatch.handleDeArrowError(API_URL + route.getCompiledRoute(), 0);
            throw ex;
        }

        // No video with the hash prefix has DeArrow data.
        if (responseCode == 404) {
            return Branding.NONE;
        }
        if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            DeArrowPatch.handleDeArrowError(API_URL + route.getCompiledRoute(), responseCode);
            throw new IOException("DeArrow response code: " + responseCode);
        }

        try {
            JSONObject branding = Requester.parseJSONObject(connection).optJSONObject(videoId);
            if (branding == null) {
                return Branding.NONE;
            }
            return new Branding(new DeArrowTitle(parseTitle(branding.optJSONArray("titles")),
                    parseCasualTitles(branding.optJSONArray("casualVotes"))),
                    parseThumbnailTime(branding.optJSONArray("thumbnails")));
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not read DeArrow branding of: " + videoId, ex);
            throw ex;
        } catch (Exception ex) {
            Logger.printException(() -> "fetchBranding failure", ex);
            return Branding.NONE;
        }
    }

    /**
     * The server sorts the submissions by votes. As done by the DeArrow extension, the first submission
     * that is locked or not downvoted is used. If that submission is the original,
     * then DeArrow keeps the original.
     *
     * @return The submission to use, or null if none or DeArrow keeps the original.
     */
    @Nullable
    private static JSONObject findSubmission(@Nullable JSONArray submissions) {
        if (submissions == null) {
            return null;
        }
        for (int i = 0, length = submissions.length(); i < length; i++) {
            JSONObject submission = submissions.optJSONObject(i);
            if (submission == null || (!submission.optBoolean("locked") && submission.optInt("votes") < 0)) {
                continue;
            }
            return submission.optBoolean("original") ? null : submission;
        }
        return null;
    }

    @Nullable
    private static String parseTitle(@Nullable JSONArray titles) {
        JSONObject title = findSubmission(titles);
        if (title == null) {
            return null;
        }

        // Words that are not auto formatted by the DeArrow extension start with '>'.
        String text = FORMATTER_OVERRIDE_PATTERN.matcher(title.optString("title")).replaceAll("$1").trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Users of casual mode vote for the original titles they like, in categories such as 'funny'.
     * As done by the DeArrow extension, casual mode shows the original title if any category
     * has enough votes. The server subtracts the votes against the original title from the counts.
     *
     * @return The original titles in lowercase with enough casual votes,
     *         or an empty title for votes that do not include the original title.
     */
    private static Set<String> parseCasualTitles(@Nullable JSONArray casualVotes) {
        if (casualVotes == null || !Settings.DEARROW_CASUAL_MODE.get()) {
            return Collections.emptySet();
        }
        final int minimumVotes = Settings.DEARROW_CASUAL_MODE_MIN_VOTES.get();
        final int casualVoteCount = casualVotes.length();
        Set<String> titles = new HashSet<>(2 * casualVoteCount);

        for (int i = 0; i < casualVoteCount; i++) {
            JSONObject vote = casualVotes.optJSONObject(i);
            if (vote != null && vote.optInt("count") >= minimumVotes) {
                // optString() returns "null" for a null value.
                titles.add(vote.isNull("title")
                        ? ""
                        : vote.optString("title").trim().toLowerCase(Locale.ROOT));
            }
        }
        return titles;
    }

    @Nullable
    private static Double parseThumbnailTime(@Nullable JSONArray thumbnails) {
        JSONObject thumbnail = findSubmission(thumbnails);
        if (thumbnail == null) {
            return null;
        }
        final double time = thumbnail.optDouble("timestamp");
        return Double.isNaN(time) || time < 0 ? null : time;
    }
}
