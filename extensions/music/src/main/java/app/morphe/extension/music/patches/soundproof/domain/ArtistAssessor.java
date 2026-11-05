/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides the status of a track from its artist credit.
 * <p>
 * The whole credit is looked up first, since some database entries are themselves duos
 * ("Honey &amp; Buck"). Only when it is not listed is the credit split into artists, and a
 * single AI artist among them is enough to mark the track as AI.
 */
public final class ArtistAssessor {

    private final ArtistDirectory directory;
    private final AiDetectionPolicy policy;

    public ArtistAssessor(ArtistDirectory directory, AiDetectionPolicy policy) {
        this.directory = directory;
        this.policy = policy;
    }

    public ArtistAssessment assess(String credit) {
        Optional<ArtistRecord> wholeCreditArtist = directory.findByName(credit);
        if (wholeCreditArtist.isPresent()) {
            return classify(wholeCreditArtist.get());
        } else {
            return assessEachArtist(ArtistCreditSplitter.split(credit));
        }
    }

    private ArtistAssessment assessEachArtist(List<String> artistNames) {
        List<ArtistRecord> listedArtists = new ArrayList<>();
        for (String artistName : artistNames) {
            directory.findByName(artistName).ifPresent(listedArtists::add);
        }
        for (ArtistRecord artist : listedArtists) {
            if (policy.isAi(artist)) {
                return ArtistAssessment.ai(artist);
            }
        }
        if (listedArtists.isEmpty()) {
            return ArtistAssessment.unknown();
        } else {
            return ArtistAssessment.human(listedArtists.get(0));
        }
    }

    private ArtistAssessment classify(ArtistRecord artist) {
        if (policy.isAi(artist)) {
            return ArtistAssessment.ai(artist);
        } else {
            return ArtistAssessment.human(artist);
        }
    }
}
