/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsManager.ProviderFetch;
import app.morphe.extension.music.patches.lyrics.LyricsManager.ScoredCandidate;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;

final class DisplayState {

    final boolean wordSync;
    /** An explicit search waits for every stage, so the best result of the run is the one shown. */
    final boolean exhaustive;
    @Nullable Lyrics published;
    long firstPaintAt;
    @Nullable Lyrics bestResult;
    int bestDisplayRank;
    @Nullable Lyrics bestFallback;
    int bestFallbackRank;
    boolean windowClosed;

    DisplayState(boolean wordSync, boolean exhaustive) {
        this.wordSync = wordSync;
        this.exhaustive = exhaustive;
        this.bestDisplayRank = wordSync ? -1 : -2;
        // Plain (rank 0) high-match must qualify as endgame fallback; default 0 would not.
        this.bestFallbackRank = -1;
    }

    boolean accept(@Nullable ProviderFetch pf, List<ScoredCandidate> scoreCandidates,
                   boolean stage12) {
        if (pf == null || (!pf.highMatch() && !pf.blind())) {
            return false;
        }
        Lyrics fetched = pf.lyrics();
        if (!LyricsMatch.isValidLyrics(fetched, null)) {
            return false;
        }
        final int match = pf.matchScore();
        final int rank = LyricsRequests.syncRank(fetched);
        final int composite = LyricsRequests.composite(match, fetched, pf.penalty());
        scoreCandidates.add(new ScoredCandidate(composite, rank, fetched));

        if (LyricsRequests.isCustomMatchMode()) {
            if (composite > bestFallbackRank) {
                bestFallback = fetched;
                bestFallbackRank = composite;
            }
            if (composite > bestDisplayRank) {
                bestResult = fetched;
                bestDisplayRank = composite;
            }
            return false;
        }

        if (!pf.highMatch()) {
            return false;
        }

        if (rank > bestFallbackRank) {
            bestFallback = fetched;
            bestFallbackRank = rank;
        }

        if (wordSync) {
            if (rank == 2) {
                bestResult = fetched;
                return !exhaustive;
            }
            return false;
        }

        // Demoting word timing below the weaker kinds let a later result replace it on screen.
        if (rank > bestDisplayRank) {
            bestResult = fetched;
            bestDisplayRank = rank;
        }
        return !exhaustive && stage12 && rank == 1 && bestResult != null;
    }
}
