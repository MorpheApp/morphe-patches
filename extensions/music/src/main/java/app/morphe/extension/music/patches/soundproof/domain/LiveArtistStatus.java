/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import java.util.Optional;

import app.morphe.extension.music.patches.soundproof.domain.VoteMajority.VoteSide;

/**
 * Votes for one artist as the SoundProof server reports them right now.
 *
 * @param myVote The vote the server attributes to this device. The server tells voters apart by
 *               their IP address, so it can change with the network.
 */
public record LiveArtistStatus(int humanVotes, int aiVotes, boolean verified, Optional<VoteSide> myVote) {

    public LiveArtistStatus {
        if (humanVotes < 0 || aiVotes < 0) {
            throw new IllegalArgumentException("Vote counts must not be negative");
        }
    }

    /**
     * @return The database entry these votes stand for, under the target's id.
     */
    public ArtistRecord toRecord(VoteTarget target) {
        return new ArtistRecord(target.id(), target.name(), humanVotes, aiVotes, verified);
    }
}
