/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsFilter;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.settings.Settings;

public final class LyricsMatch {

    private LyricsMatch() {
    }

    public static boolean isValidLyrics(@Nullable Lyrics lyrics, @Nullable TrackInfo track) {
        if (lyrics == null || lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
            return false;
        }
        Lyrics filtered = LyricsFilter.filterCreditLines(lyrics, track);
        filtered = LyricsFilter.filterLyricsText(filtered);
        return !filtered.isEmpty();
    }

    /**
     * The id the cache stores this under: the name shown to the user, minus any qualifier such
     * as "(via Lyrically)", so the cache keeps one entry per provider.
     */
    public static String cacheSourceFor(@Nullable Lyrics lyrics) {
        if (lyrics == null) {
            return "";
        }
        String providerName = lyrics.providerName();
        for (String id : LyricsProviders.PROVIDER_ORDER) {
            if (providerName.equals(id)) {
                return id;
            }
        }
        for (String id : LyricsProviders.PROVIDER_ORDER) {
            if (providerName.startsWith(id + " ")) {
                return id;
            }
        }
        return providerName;
    }

    static String fingerprint(Lyrics lyrics) {
        if (lyrics == null) return "";
        String provider = lyrics.providerName();
        String raw = lyrics.rawFormat();
        if (raw != null && !raw.isEmpty()) {
            return provider + "|" + raw.hashCode();
        }
        StringBuilder sb = new StringBuilder();
        for (LyricsLine line : lyrics.lines()) {
            sb.append(line.text());
        }
        return provider + "|" + sb.toString().hashCode();
    }

    /**
     * The track the search asks with: the released audio's duration, the video minus its SponsorBlock
     * segments, so candidates written against it pass the duration filter. With
     * {@link Settings#LYRICS_SB_MATCHING} off none of that is requested or subtracted.
     */
    static TrackInfo withEffectiveDuration(TrackInfo track,
                                           @Nullable List<SkipSegments.Seg> segs) {
        if (!Settings.LYRICS_SB_MATCHING.get()) {
            return track;
        }
        if (segs == null || segs.isEmpty() || track.durationSeconds() <= 0) {
            return track;
        }
        long contentMs = SkipSegments.effectiveDurationMs(track.durationSeconds() * 1000L, segs);
        int contentSeconds = (int) (contentMs / 1000);
        if (contentSeconds <= 0 || contentSeconds == track.durationSeconds()) {
            return track;
        }
        return new TrackInfo(track.title(), track.artist(), track.album(), contentSeconds);
    }

    /**
     * Whether these lyrics were fetched by video id, and so are written against the timeline the
     * player reports: playback time is already their clock, and the SponsorBlock segments must not
     * be subtracted from it. Results matched by title come from the released audio and always get the
     * subtraction; Captions matches by prefix because its ASR track carries a name of its own.
     */
    static boolean isVideoIdMatchedLyrics(@Nullable Lyrics lyrics) {
        if (lyrics == null) {
            return false;
        }
        String provider = lyrics.providerName();
        return (provider != null && provider.startsWith(Lyrics.CAPTIONS_PROVIDER))
                || "Unison".equals(provider)
                || "SimpMusic".equals(provider)
                || (provider != null && provider.startsWith("YouTube Music"));
    }

    static boolean isBlindProvider(LyricsProvider provider) {
        return switch (provider.name()) {
            case "Spotify", "AMLL", "bLyrics", "BiniLyrics", "Lyricify" -> true;
            default -> false;
        };
    }
}
