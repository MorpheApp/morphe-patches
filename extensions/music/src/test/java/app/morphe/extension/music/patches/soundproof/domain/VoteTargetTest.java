/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoteTargetTest {

    @Test public void listedArtistIsTargetedWithItsDatabaseIdAndName() {
        ArtistRecord artist = new ArtistRecord("tony------diffraction-beatz", "tony      diffraction beatz", 0, 2, false);
        VoteTarget target = VoteTarget.of(ArtistAssessment.ai(artist), "Tony Diffraction Beatz - Topic").orElseThrow();
        assertEquals(new VoteTarget("tony------diffraction-beatz", "tony      diffraction beatz"), target);
    }

    @Test public void unknownCreditTargetsItsFirstArtist() {
        VoteTarget target = VoteTarget.of(ArtistAssessment.unknown(), "Daft Punk - Topic & Pharrell Williams").orElseThrow();
        assertEquals(new VoteTarget("daft-punk", "Daft Punk"), target);
    }

    @Test public void unavailableDatabaseStillTargetsTheCredit() {
        VoteTarget target = VoteTarget.of(ArtistAssessment.unavailable(), "  Tame   Impala ").orElseThrow();
        assertEquals(new VoteTarget("tame-impala", "Tame Impala"), target);
    }

    @Test public void noCreditHasNoTarget() {
        assertTrue(VoteTarget.of(ArtistAssessment.unknown(), null).isEmpty());
        assertTrue(VoteTarget.of(ArtistAssessment.unknown(), "   ").isEmpty());
    }
}
