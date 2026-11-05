/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.domain;

/**
 * The single rule deciding whether a listed artist counts as AI.
 * <p>
 * An artist is AI when it has at least {@code minimumVotes} votes in total and at least
 * {@code minimumAiPercent} percent of them say AI. The verified flag is not part of the rule.
 */
public final class AiDetectionPolicy {

    public static final int DEFAULT_MINIMUM_AI_PERCENT = 50;
    public static final int DEFAULT_MINIMUM_VOTES = 1;

    private final int minimumAiPercent;
    private final int minimumVotes;

    public AiDetectionPolicy(int minimumAiPercent, int minimumVotes) {
        this.minimumAiPercent = minimumAiPercent;
        this.minimumVotes = minimumVotes;
    }

    public boolean isAi(ArtistRecord artist) {
        int totalVotes = artist.totalVotes();
        if (totalVotes < minimumVotes) {
            return false;
        } else {
            // Integer cross-multiplication avoids rounding at the threshold boundary.
            return (long) artist.aiVotes() * 100 >= (long) minimumAiPercent * totalVotes;
        }
    }
}
