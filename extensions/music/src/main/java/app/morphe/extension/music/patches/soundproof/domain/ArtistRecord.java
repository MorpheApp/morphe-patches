/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import java.util.Objects;

/**
 * One validated artist entry of the SoundProof database.
 *
 * @param id         SoundProof id: the artist name lowercased, spaces replaced by dashes.
 * @param name       Display name as written in the database.
 * @param humanVotes Number of community votes saying the artist is human.
 * @param aiVotes    Number of community votes saying the artist is AI.
 * @param verified   True only when a SoundProof maintainer checked the entry by hand.
 */
public record ArtistRecord(String id, String name, int humanVotes, int aiVotes, boolean verified) {

    public ArtistRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        if (id.trim().isEmpty()) {
            throw new IllegalArgumentException("Artist id must not be blank");
        }
        if (humanVotes < 0 || aiVotes < 0) {
            throw new IllegalArgumentException("Vote counts must not be negative: " + id);
        }
    }

    public int totalVotes() {
        return humanVotes + aiVotes;
    }

    /**
     * @return Share of AI votes rounded to the nearest percent, for display only.
     *         Use {@link AiDetectionPolicy} to decide whether the artist is AI.
     */
    public int aiVotePercent() {
        int totalVotes = totalVotes();
        if (totalVotes == 0) {
            return 0;
        } else {
            return Math.round(aiVotes * 100f / totalVotes);
        }
    }
}
