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
 * The verdict for the credit of one track: its status, and the database entry it is based on.
 */
public final class ArtistAssessment {

    private static final ArtistAssessment UNKNOWN = new ArtistAssessment(ArtistStatus.UNKNOWN, null);
    private static final ArtistAssessment UNAVAILABLE = new ArtistAssessment(ArtistStatus.UNAVAILABLE, null);

    private final ArtistStatus status;
    @Nullable
    private final ArtistRecord artist;

    private ArtistAssessment(ArtistStatus status, @Nullable ArtistRecord artist) {
        this.status = status;
        this.artist = artist;
    }

    public static ArtistAssessment ai(ArtistRecord artist) {
        return new ArtistAssessment(ArtistStatus.AI, artist);
    }

    public static ArtistAssessment human(ArtistRecord artist) {
        return new ArtistAssessment(ArtistStatus.HUMAN, artist);
    }

    public static ArtistAssessment unknown() {
        return UNKNOWN;
    }

    public static ArtistAssessment unavailable() {
        return UNAVAILABLE;
    }

    public ArtistStatus status() {
        return status;
    }

    /**
     * @return The database entry behind the verdict, empty for {@link ArtistStatus#UNKNOWN}
     *         and {@link ArtistStatus#UNAVAILABLE}.
     */
    public Optional<ArtistRecord> artist() {
        return Optional.ofNullable(artist);
    }

    @Override
    public String toString() {
        return "ArtistAssessment{" + status + ", " + artist + "}";
    }
}
