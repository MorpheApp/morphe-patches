/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AiDetectionPolicyTest {

    private static ArtistRecord artist(int humanVotes, int aiVotes) {
        return new ArtistRecord("artist", "Artist", humanVotes, aiVotes, false);
    }

    @Test public void defaultPolicyFlagsAnAiMajority() {
        AiDetectionPolicy policy = new AiDetectionPolicy(
                AiDetectionPolicy.DEFAULT_MINIMUM_AI_PERCENT, AiDetectionPolicy.DEFAULT_MINIMUM_VOTES);
        assertTrue(policy.isAi(artist(0, 1)));
        assertTrue(policy.isAi(artist(0, 1000)));
        assertFalse(policy.isAi(artist(1, 0)));
        assertFalse(policy.isAi(artist(100, 50)));
    }

    @Test public void percentThresholdIsInclusive() {
        AiDetectionPolicy policy = new AiDetectionPolicy(50, 1);
        assertTrue(policy.isAi(artist(1, 1)));
        assertFalse(policy.isAi(artist(51, 49)));
    }

    @Test public void percentBoundaryDoesNotRoundUp() {
        AiDetectionPolicy policy = new AiDetectionPolicy(67, 1);
        // 2 / 3 is 66.66 %, which must stay below a 67 % threshold.
        assertFalse(policy.isAi(artist(1, 2)));
    }

    @Test public void tooFewVotesIsNeverAi() {
        AiDetectionPolicy policy = new AiDetectionPolicy(50, 10);
        assertFalse(policy.isAi(artist(0, 9)));
        assertTrue(policy.isAi(artist(0, 10)));
    }

    @Test public void artistWithoutVotesIsNeverAi() {
        assertFalse(new AiDetectionPolicy(50, 1).isAi(artist(0, 0)));
    }

    @Test public void displayPercentIsRoundedAndSafeWithoutVotes() {
        assertEquals(67, artist(1, 2).aiVotePercent());
        assertEquals(0, artist(0, 0).aiVotePercent());
        assertEquals(100, artist(0, 1000).aiVotePercent());
    }
}
