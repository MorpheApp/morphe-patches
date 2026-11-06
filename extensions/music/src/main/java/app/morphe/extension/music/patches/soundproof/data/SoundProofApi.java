/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.music.patches.soundproof.domain.LiveArtistStatus;
import app.morphe.extension.music.patches.soundproof.domain.VoteMajority.VoteSide;
import app.morphe.extension.music.patches.soundproof.domain.VoteTarget;
import app.morphe.extension.shared.requests.Requester;

/**
 * The two calls of the SoundProof server that the browser extension makes: read the votes of
 * one artist, and vote. The server has no accounts, it tells voters apart by their IP address.
 * <p>
 * Only used when the user opens the sheet or votes. The skip decision stays on the daily dump,
 * so that playing music sends nothing. All methods do I/O and must be called off the main thread.
 */
public final class SoundProofApi {

    private static final String RPC_URL = "https://solsneywdlhvwbtghopw.supabase.co/rest/v1/rpc/";
    /** Public client key of the SoundProof browser extension, meant to be shipped in clients. */
    private static final String PUBLISHABLE_KEY = "sb_publishable_dlFufI-1QXA4VJdGoMWxMw_ouWHyl3k";
    /** SoundProof platform of music services, as opposed to "video" for YouTube channels. */
    private static final String PLATFORM = "music";
    private static final String VOTE_ACCEPTED = "\"SUCCESS\"";
    private static final int TIMEOUT_MILLIS = (int) TimeUnit.SECONDS.toMillis(10);

    private SoundProofApi() {
    }

    /**
     * @return Empty when the server has no entry for the artist.
     */
    public static Optional<LiveArtistStatus> fetchStatus(VoteTarget target) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("target_id", target.name())
                    .put("platform_input", PLATFORM);
            JSONArray rows = new JSONArray(post("get_artist_status", body));
            if (rows.length() == 0) {
                return Optional.empty();
            }
            return Optional.of(parseStatus(rows.getJSONObject(0)));
        } catch (JSONException | IllegalArgumentException ex) {
            throw new IOException("Malformed SoundProof status", ex);
        }
    }

    /**
     * Casts a vote. Voting again for the side already voted for removes the vote.
     */
    public static void vote(VoteTarget target, VoteSide side) throws IOException {
        String response;
        try {
            JSONObject body = new JSONObject()
                    .put("artist_id_input", target.name())
                    .put("vote_type_input", voteTypeOf(side))
                    .put("platform_input", PLATFORM);
            response = post("handle_vote", body);
        } catch (JSONException ex) {
            throw new IOException("Cannot build the SoundProof vote", ex);
        }
        if (!VOTE_ACCEPTED.equals(response.trim())) {
            throw new IOException("SoundProof vote rejected: " + response);
        }
    }

    private static LiveArtistStatus parseStatus(JSONObject row) throws JSONException {
        Optional<VoteSide> myVote = Optional.empty();
        if (!row.isNull("my_current_vote")) {
            String vote = row.getString("my_current_vote");
            if (vote.equals(voteTypeOf(VoteSide.HUMAN))) {
                myVote = Optional.of(VoteSide.HUMAN);
            } else if (vote.equals(voteTypeOf(VoteSide.AI))) {
                myVote = Optional.of(VoteSide.AI);
            } else {
                throw new JSONException("Unknown vote type: " + vote);
            }
        }
        return new LiveArtistStatus(
                row.getInt("out_human"),
                row.getInt("out_ai"),
                row.optBoolean("out_verified", false),
                myVote
        );
    }

    private static String voteTypeOf(VoteSide side) {
        return switch (side) {
            case HUMAN -> "human";
            case AI -> "ai";
        };
    }

    private static String post(String function, JSONObject body) throws IOException {
        HttpURLConnection connection = Requester.openConnection(RPC_URL + function);
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("apikey", PUBLISHABLE_KEY);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                throw new IOException("SoundProof " + function + " failed: HTTP " + responseCode
                        + " " + Requester.parseErrorString(connection));
            }
            return Requester.parseString(connection);
        } finally {
            connection.disconnect();
        }
    }
}
