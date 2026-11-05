/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import app.morphe.extension.music.patches.soundproof.domain.VoteMajority.VoteSide;

public class VoteMajorityTest {

    private static VoteMajority majorityOf(int humanVotes, int aiVotes) {
        return VoteMajority.of(new ArtistRecord("artist", "Artist", humanVotes, aiVotes, false)).orElseThrow();
    }

    @Test public void aiMajorityShowsTheAiShare() {
        assertEquals(new VoteMajority(VoteSide.AI, 93), majorityOf(3, 41));
    }

    @Test public void humanMajorityShowsTheHumanShare() {
        assertEquals(new VoteMajority(VoteSide.HUMAN, 92), majorityOf(37, 3));
    }

    @Test public void tieCountsAsAi() {
        assertEquals(new VoteMajority(VoteSide.AI, 50), majorityOf(4, 4));
    }

    @Test public void sharesAddUpToOneHundredAfterRounding() {
        // 1 / 3 rounds to 33 % AI, so the human share must be 67 %, not a rounded 66.67.
        assertEquals(new VoteMajority(VoteSide.HUMAN, 67), majorityOf(2, 1));
        // 1 / 1000 rounds to 0 % AI.
        assertEquals(new VoteMajority(VoteSide.HUMAN, 100), majorityOf(999, 1));
    }

    @Test public void artistWithoutVotesHasNoMajority() {
        assertFalse(VoteMajority.of(new ArtistRecord("artist", "Artist", 0, 0, false)).isPresent());
    }
}
