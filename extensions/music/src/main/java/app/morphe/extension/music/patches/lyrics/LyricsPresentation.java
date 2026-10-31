/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.ArtistLineCycle;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsTextFormatter;
import app.morphe.extension.music.patches.lyrics.model.MiniLyricsFormatter;
import app.morphe.extension.music.patches.lyrics.model.MiniLyricsTimeline;
import app.morphe.extension.music.patches.lyrics.model.TrackVersion;
import app.morphe.extension.music.settings.Settings;

/**
 * Display only formatting shared by the lyrics panel, the miniplayer and the media session.
 * The lyrics themselves are never changed. Only used on the main thread.
 */
public final class LyricsPresentation {

    private static final ArtistLineCycle artistCycle = new ArtistLineCycle();
    @Nullable
    private static Lyrics timelineLyrics;
    private static long timelineDurationMs;
    @Nullable
    private static MiniLyricsTimeline timeline;
    @Nullable
    private static LyricsLine chunksLine;
    private static long chunksEndMs;
    private static int chunksLimit;
    private static boolean chunksCapitalize;
    private static boolean chunksStrip;
    private static float chunksMultiplier = 1;
    private static List<MiniLyricsFormatter.Chunk> chunks = List.of();

    private LyricsPresentation() {
    }

    /** If any of the filters that change the displayed text are on. */
    public static boolean isFormattingEnabled() {
        return Settings.LYRICS_FORCE_CAPITALIZE.get() || Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.get();
    }

    public static LyricsTextFormatter.Result format(String text) {
        return LyricsTextFormatter.format(text, Settings.LYRICS_FORCE_CAPITALIZE.get(),
                Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.get());
    }

    public static String formatText(String text) {
        return isFormattingEnabled() ? format(text).text() : text;
    }

    /**
     * The frames of the track format. A version the backend reported replaces the title with
     * the backend's title without the version, which {v} then shows.
     */
    private static List<String> trackFrames(@Nullable String title, @Nullable String artist) {
        TrackVersion version = LyricsManager.getInstance().getCurrentVersion();
        return MiniLyricsFormatter.trackFrames(Settings.LYRICS_MINI_TRACK_FORMAT.get(),
                version == null ? title : version.title(), artist,
                version == null ? null : version.variant(),
                Settings.LYRICS_MINI_ARTIST_CHAR_LIMIT.get(),
                Settings.LYRICS_MINI_FULL_WIDTH_MULTIPLIER.get());
    }

    /** The track format frame currently shown. */
    public static String trackLabel(@Nullable String title, @Nullable String artist) {
        return artistCycle.current(trackFrames(title, artist));
    }

    /** Starts the track format frames over, as the track changed. */
    public static void resetTrackCycle() {
        artistCycle.reset();
    }

    /** Advances the track format frames along with the lyric, and returns the lyric. */
    @Nullable
    private static String present(@Nullable String text, @Nullable LyricsLine line,
                                  @Nullable String title, @Nullable String artist) {
        artistCycle.update(trackFrames(title, artist), new FrameKey(line, text),
                SystemClock.elapsedRealtime(), Settings.LYRICS_MINI_ARTIST_MIN_MS.get(),
                Settings.LYRICS_MINI_ARTIST_MAX_MS.get());
        return text;
    }

    /**
     * The text the miniplayer and the media session show at a playback position.
     *
     * @return Null if no lyric applies and the track title should be shown instead.
     * An empty string is a blank instrumental frame the user configured.
     */
    @Nullable
    public static String miniLine(Lyrics lyrics, long durationMs, long positionMs,
                                  @Nullable String title, @Nullable String artist) {
        if (timeline == null || lyrics != timelineLyrics || durationMs != timelineDurationMs) {
            timeline = new MiniLyricsTimeline(lyrics.lines(), durationMs);
            timelineLyrics = lyrics;
            timelineDurationMs = durationMs;
        }

        List<LyricsLine> lines = lyrics.lines();
        final long position = MiniLyricsFormatter.selectionPosition(positionMs,
                Settings.LYRICS_MINI_NEXT_OFFSET_MS.get());
        MiniLyricsTimeline.Selection selection = timeline.at(position);

        MiniLyricsTimeline.Vocal vocal = selection.vocal();
        if (vocal != null) {
            return present(vocalText(lines, vocal, position), vocal.line(), title, artist);
        }

        MiniLyricsTimeline.Gap gap = selection.gap();
        MiniLyricsTimeline.Vocal previous = gap == null ? selection.previous() : gap.previous();
        // The text the previous line ended with.
        String previousText = previous == null ? null : vocalText(lines, previous, previous.endMs() - 1);
        if (gap == null) {
            // A pause between lines keeps the previous line.
            return present(previousText, previous == null ? null : previous.line(), title, artist);
        }

        return present(MiniLyricsFormatter.instrumental(Settings.LYRICS_MINI_INSTRUMENTAL.get(),
                gap.startMs(), gap.endMs(), position, title, artist,
                previousText == null ? title : previousText), null, title, artist);
    }

    private static String vocalText(List<LyricsLine> lines, MiniLyricsTimeline.Vocal vocal, long positionMs) {
        LyricsLine line = vocal.line();
        final int limit = Settings.LYRICS_MINI_CHAR_LIMIT.get();
        final boolean capitalize = Settings.LYRICS_FORCE_CAPITALIZE.get();
        final boolean strip = Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.get();
        final float multiplier = Settings.LYRICS_MINI_FULL_WIDTH_MULTIPLIER.get();
        if (line != chunksLine || vocal.endMs() != chunksEndMs || limit != chunksLimit
                || capitalize != chunksCapitalize || strip != chunksStrip
                || multiplier != chunksMultiplier) {
            chunks = MiniLyricsFormatter.chunks(line, vocal.endMs(), limit, capitalize, strip, multiplier);
            chunksLine = line;
            chunksEndMs = vocal.endMs();
            chunksLimit = limit;
            chunksCapitalize = capitalize;
            chunksStrip = strip;
            chunksMultiplier = multiplier;
        }

        return MiniLyricsFormatter.current(chunks, positionMs, Settings.LYRICS_MINI_ENCAPSULATION.get(),
                MiniLyricsFormatter.repeatCount(lines, vocal.index()));
    }

    /** The lyric shown with a track format frame. The line tells apart repeats of the same text. */
    private record FrameKey(@Nullable LyricsLine line, @Nullable String text) {
    }
}
