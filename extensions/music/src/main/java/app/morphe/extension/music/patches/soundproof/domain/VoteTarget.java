/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import androidx.annotation.Nullable;

import java.util.Optional;

/**
 * The artist a vote from the sheet applies to.
 *
 * @param id   SoundProof id, used to replace the database entry with fresher votes.
 * @param name Name sent to the SoundProof server, which derives its own id from it.
 */
public record VoteTarget(String id, String name) {

    /**
     * The artist the verdict is based on, or else the first artist of the credit.
     *
     * @return Empty when there is neither.
     */
    public static Optional<VoteTarget> of(ArtistAssessment assessment, @Nullable String credit) {
        Optional<ArtistRecord> artist = assessment.artist();
        if (artist.isPresent()) {
            // The database name, not the credit: the server derived the id from this name.
            return Optional.of(new VoteTarget(artist.get().id(), artist.get().name()));
        }
        if (credit == null) {
            return Optional.empty();
        }
        for (String artistName : ArtistCreditSplitter.split(credit)) {
            String displayName = ArtistNameNormalizer.toDisplayName(artistName);
            if (!displayName.isEmpty()) {
                return Optional.of(new VoteTarget(ArtistNameNormalizer.toArtistId(displayName), displayName));
            }
        }
        return Optional.empty();
    }
}
