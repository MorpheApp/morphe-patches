/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.shared.sponsorblock.SponsorBlockHelpers;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Fetches all DeArrow titles of a video, and submits and votes on titles.
 * <p>
 * DeArrow uses the same user id as SponsorBlock. Submitting a title that was already submitted
 * is an upvote of it, and each user has one upvote per video, so an upvote moves the earlier
 * upvote of the user to the new title.
 */
final class DeArrowVoteRequest {

    /**
     * The video id is sent, instead of the start of its hash, since the vote is for the video anyway.
     * Titles with negative votes are included, such as the titles of new users that are not yet verified.
     */
    private static final Route GET_TITLES = new Route(Route.Method.GET,
            "/api/branding?videoID={video_id}&fetchAll=true");

    private static final Route POST_BRANDING = new Route(Route.Method.POST, "/api/branding");

    private static final int TIMEOUT_MILLISECONDS = 10_000;

    /**
     * Longest title the server accepts from users who are not VIP.
     */
    static final int MAX_TITLE_LENGTH = 110;

    /**
     * A title submitted to DeArrow.
     *
     * @param title    The title as submitted, which is sent again to upvote it.
     * @param original If the title is the original title of the video.
     * @param votes    The upvotes minus the downvotes, which is negative for the titles
     *                 of users that are not yet verified.
     * @param locked   If a VIP locked the title.
     */
    record TitleSubmission(String title, boolean original, int votes, boolean locked) {
        /**
         * @return The title as shown, without the formatting characters.
         */
        String shownTitle() {
            return DeArrowBrandingRequest.formatTitle(title);
        }
    }

    private DeArrowVoteRequest() {
    }

    /**
     * @return The titles of the video, sorted by the server with the locked titles first and then by votes.
     * @throws IOException If the titles failed to fetch.
     */
    static List<TitleSubmission> fetchTitles(String videoId) throws IOException, JSONException {
        Utils.verifyOffMainThread();

        HttpURLConnection connection = Requester.getConnectionFromRoute(
                DeArrowBrandingRequest.API_URL, GET_TITLES, videoId);
        connection.setConnectTimeout(TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(TIMEOUT_MILLISECONDS);

        final int responseCode = connection.getResponseCode();
        // The video has no DeArrow data.
        if (responseCode == Requester.HTTP_STATUS_CODE_NOT_FOUND) {
            connection.disconnect();
            return Collections.emptyList();
        }
        if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            connection.disconnect();
            throw new IOException("DeArrow response code: " + responseCode);
        }

        JSONArray titles = Requester.parseJSONObjectAndDisconnect(connection).optJSONArray("titles");
        if (titles == null) {
            return Collections.emptyList();
        }
        final int length = titles.length();
        List<TitleSubmission> submissions = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            JSONObject title = titles.getJSONObject(i);
            String text = title.optString("title");
            if (!text.isEmpty()) {
                submissions.add(new TitleSubmission(text, title.optBoolean("original"),
                        title.optInt("votes"), title.optBoolean("locked")));
            }
        }
        return submissions;
    }

    /**
     * Submits a new title, or votes on a title that was already submitted.
     *
     * @param title          The title as submitted, or as typed for a new title.
     * @param original       If the title is the original title of the video.
     * @param downvote       If the vote is a downvote of a title that was already submitted.
     * @param videoLength    Length of the video in milliseconds, or 0 if not known.
     *                       The server rejects the vote if the video it knows has another length.
     * @return The error to show, or null if the vote was saved.
     */
    @Nullable
    static String vote(String videoId, String title, boolean original, boolean downvote, long videoLength) {
        Utils.verifyOffMainThread();

        try {
            JSONObject titleJson = new JSONObject();
            titleJson.put("title", title);
            titleJson.put("original", original);

            JSONObject body = new JSONObject();
            body.put("userID", SponsorBlockHelpers.getOrGeneratePrivateUserID(Settings.SB_PRIVATE_USER_ID));
            body.put("userAgent", "Morphe/" + Utils.getAppVersionName());
            body.put("service", "YouTube");
            body.put("videoID", videoId);
            body.put("title", titleJson);
            body.put("downvote", downvote);
            body.put("casualMode", Settings.DEARROW_CASUAL_MODE.get());
            if (videoLength > 0) {
                body.put("videoDuration", videoLength / 1000.0);
            }
            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection connection = Requester.getConnectionFromRoute(
                    DeArrowBrandingRequest.API_URL, POST_BRANDING);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bodyBytes.length);
            connection.setConnectTimeout(TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(TIMEOUT_MILLISECONDS);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bodyBytes);
            }

            final int responseCode = connection.getResponseCode();
            Logger.printDebug(() -> "DeArrow vote response code: " + responseCode
                    + " title: " + title + " downvote: " + downvote);
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                connection.disconnect();
                return null;
            }

            // The server explains why the vote was rejected, such as a title that is too long.
            String reason = Requester.parseErrorStringAndDisconnect(connection).trim();
            if (responseCode == Requester.HTTP_STATUS_CODE_TOO_MANY_REQUESTS) {
                return str("morphe_dearrow_vote_failed_rate_limit");
            }
            if (!reason.isEmpty() && (responseCode == Requester.HTTP_STATUS_CODE_BAD_REQUEST
                    || responseCode == Requester.HTTP_STATUS_CODE_FORBIDDEN)) {
                return str("morphe_dearrow_vote_failed_reason", reason);
            }
            // Only VIP users can downvote locked titles, and the server sends no reason then.
            if (downvote && responseCode == Requester.HTTP_STATUS_CODE_FORBIDDEN) {
                return str("morphe_dearrow_vote_failed_locked");
            }
            return str("morphe_dearrow_vote_failed_code", responseCode);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not vote on DeArrow title of: " + videoId, ex);
            return str("morphe_dearrow_vote_failed_connection");
        } catch (Exception ex) {
            Logger.printException(() -> "vote failure", ex);
            return str("morphe_dearrow_vote_failed_connection");
        }
    }
}
