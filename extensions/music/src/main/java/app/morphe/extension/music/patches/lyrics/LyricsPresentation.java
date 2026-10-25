/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsTextFormatter;
import app.morphe.extension.music.patches.lyrics.model.MiniLyricsFormatter;
import app.morphe.extension.music.patches.lyrics.model.MiniLyricsTimeline;
import app.morphe.extension.music.settings.Settings;

/**
 * Display only formatting shared by the lyrics panel, the miniplayer and the media session.
 * The lyrics themselves are never changed. Only used on the main thread.
 */
public final class LyricsPresentation {

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

    public static String trackLabel(String title, String artist) {
        return MiniLyricsFormatter.trackLabel(Settings.LYRICS_MINI_TRACK_FORMAT.get(), title, artist);
    }

    /**
     * The text the miniplayer and the media session show at a playback position.
     *
     * @return Null if no lyric applies and the track title should be shown instead.
     *         An empty string is a blank instrumental frame the user configured.
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
            return vocalText(lines, vocal, position);
        }

        MiniLyricsTimeline.Gap gap = selection.gap();
        MiniLyricsTimeline.Vocal previous = gap == null ? selection.previous() : gap.previous();
        // The text the previous line ended with.
        String previousText = previous == null ? null : vocalText(lines, previous, previous.endMs() - 1);
        if (gap == null) {
            // A pause between lines keeps the previous line.
            return previousText;
        }

        return MiniLyricsFormatter.instrumental(Settings.LYRICS_MINI_INSTRUMENTAL.get(),
                gap.startMs(), gap.endMs(), position, title, artist,
                previousText == null ? title : previousText);
    }

    private static String vocalText(List<LyricsLine> lines, MiniLyricsTimeline.Vocal vocal, long positionMs) {
        LyricsLine line = vocal.line();
        final int limit = Settings.LYRICS_MINI_CHAR_LIMIT.get();
        final boolean capitalize = Settings.LYRICS_FORCE_CAPITALIZE.get();
        final boolean strip = Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.get();
        if (line != chunksLine || vocal.endMs() != chunksEndMs || limit != chunksLimit
                || capitalize != chunksCapitalize || strip != chunksStrip) {
            chunks = MiniLyricsFormatter.chunks(line, vocal.endMs(), limit, capitalize, strip);
            chunksLine = line;
            chunksEndMs = vocal.endMs();
            chunksLimit = limit;
            chunksCapitalize = capitalize;
            chunksStrip = strip;
        }

        return MiniLyricsFormatter.current(chunks, positionMs, Settings.LYRICS_MINI_ENCAPSULATION.get(),
                MiniLyricsFormatter.repeatCount(lines, vocal.index()));
    }
}
