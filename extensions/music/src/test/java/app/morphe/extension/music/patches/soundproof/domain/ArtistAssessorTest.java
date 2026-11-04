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

import java.util.List;

public class ArtistAssessorTest {

    private static final ArtistRecord DAFT_PUNK = new ArtistRecord("daft-punk", "Daft Punk", 1, 0, true);
    private static final ArtistRecord TEN_MM = new ArtistRecord("10mm", "10mm", 0, 1000, true);
    private static final ArtistRecord HONEY_AND_BUCK = new ArtistRecord("honey-&-buck", "Honey & Buck", 0, 3, false);
    private static final ArtistRecord HONEY = new ArtistRecord("honey", "Honey", 5, 0, false);

    private final ArtistAssessor assessor = new ArtistAssessor(
            new ArtistDirectory(List.of(DAFT_PUNK, TEN_MM, HONEY_AND_BUCK, HONEY)),
            new AiDetectionPolicy(50, 1)
    );

    @Test public void listedAiArtistIsAi() {
        ArtistAssessment assessment = assessor.assess("10mm");
        assertEquals(ArtistStatus.AI, assessment.status());
        assertEquals(TEN_MM, assessment.artist().orElseThrow());
    }

    @Test public void listedHumanArtistIsHuman() {
        ArtistAssessment assessment = assessor.assess("Daft Punk - Topic");
        assertEquals(ArtistStatus.HUMAN, assessment.status());
        assertEquals(DAFT_PUNK, assessment.artist().orElseThrow());
    }

    @Test public void unlistedArtistIsUnknown() {
        ArtistAssessment assessment = assessor.assess("Unlisted Artist");
        assertEquals(ArtistStatus.UNKNOWN, assessment.status());
        assertFalse(assessment.artist().isPresent());
    }

    @Test public void oneAiArtistInACollaborationIsEnough() {
        ArtistAssessment assessment = assessor.assess("Daft Punk & 10mm");
        assertEquals(ArtistStatus.AI, assessment.status());
        assertEquals(TEN_MM, assessment.artist().orElseThrow());
    }

    @Test public void collaborationOfHumansIsHumanWithTheFirstListedArtist() {
        ArtistAssessment assessment = assessor.assess("Unlisted Artist feat. Daft Punk");
        assertEquals(ArtistStatus.HUMAN, assessment.status());
        assertEquals(DAFT_PUNK, assessment.artist().orElseThrow());
    }

    @Test public void wholeCreditListedAsADuoWinsOverItsParts() {
        // "Honey" alone is human, but the duo entry itself is listed as AI.
        ArtistAssessment assessment = assessor.assess("Honey & Buck");
        assertEquals(ArtistStatus.AI, assessment.status());
        assertEquals(HONEY_AND_BUCK, assessment.artist().orElseThrow());
    }
}
