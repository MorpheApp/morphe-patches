/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import java.util.Optional;

/**
 * The side most community votes are on, and its share, for display only.
 * <p>
 * It ignores the user's thresholds: an artist below the minimum number of votes can have an AI
 * majority and still not be {@link ArtistStatus#AI}. A tie counts as an AI majority.
 *
 * @param side    The side with the most votes.
 * @param percent Share of the votes on that side, rounded to the nearest percent. The two
 *                sides always add up to 100.
 */
public record VoteMajority(VoteSide side, int percent) {

    public enum VoteSide {
        HUMAN,
        AI
    }

    /**
     * @return Empty when the artist has no vote.
     */
    public static Optional<VoteMajority> of(ArtistRecord artist) {
        if (artist.totalVotes() == 0) {
            return Optional.empty();
        }
        int aiPercent = artist.aiVotePercent();
        if (artist.aiVotes() >= artist.humanVotes()) {
            return Optional.of(new VoteMajority(VoteSide.AI, aiPercent));
        } else {
            return Optional.of(new VoteMajority(VoteSide.HUMAN, 100 - aiPercent));
        }
    }
}
