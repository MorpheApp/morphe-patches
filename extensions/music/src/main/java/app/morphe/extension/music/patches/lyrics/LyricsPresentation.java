/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.app.Activity;
import java.util.List;
import java.util.Objects;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsTextFormatter;
import app.morphe.extension.music.patches.lyrics.model.MiniLyricsFormatter;
import app.morphe.extension.music.patches.lyrics.model.MiniLyricsTimeline;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.settings.Setting;

/** Formatting shared by the panel and Mini surfaces. Source lyrics stay untouched. */
public final class LyricsPresentation {
    private static LyricsLine cachedLine;
    private static long cachedEnd;
    private static int cachedLimit;
    private static boolean cachedCapitalize;
    private static boolean cachedStrip;
    private static List<MiniLyricsFormatter.Chunk> cachedChunks = List.of();
    private static boolean importCallbackRegistered;
    private static Object timelineSource;
    private static long timelineDuration;
    private static MiniLyricsTimeline timeline;
    private static int lastSungIndex = -1;
    private static long lastSungPosition = -1;
    private static String lastSungText;
    private static String lastSungRawText;
    private static String timelineTrackId;
    private static String timelineTitle;
    private static String timelineArtist;

    private LyricsPresentation() { }

    public static void registerSettingsImports() {
        if (importCallbackRegistered) return;
        importCallbackRegistered = true;
        Setting.addImportExportCallback(new Setting.ImportExportCallback() {
            @Override public void settingsImported(Activity activity) { Settings.migrateLyricsPresentation(); }
            @Override public void settingsExported(Activity activity) { }
        });
    }

    public static LyricsTextFormatter.Result format(String text) {
        return LyricsTextFormatter.format(text, Settings.LYRICS_FORCE_CAPITALIZE.get(),
                Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.get());
    }

    public static String trackLabel(String title, String artist) {
        return MiniLyricsFormatter.trackLabel(Settings.LYRICS_MINI_TRACK_FORMAT.get(), title, artist);
    }

    private static String miniLine(LyricsLine line, long endMs, long positionMs, int repeat) {
        final int limit = Settings.LYRICS_MINI_CHAR_LIMIT.get();
        final boolean capitalize = Settings.LYRICS_FORCE_CAPITALIZE.get();
        final boolean strip = Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.get();
        if (line != cachedLine || endMs != cachedEnd || limit != cachedLimit
                || capitalize != cachedCapitalize || strip != cachedStrip) {
            cachedChunks = MiniLyricsFormatter.chunks(line, endMs, limit, capitalize, strip);
            cachedLine = line;
            cachedEnd = endMs;
            cachedLimit = limit;
            cachedCapitalize = capitalize;
            cachedStrip = strip;
        }
        return MiniLyricsFormatter.current(cachedChunks, positionMs,
                Settings.LYRICS_MINI_ENCAPSULATION.get(), repeat);
    }

    /** Null means unavailable; an empty string is an intentional user-specified gap frame. */
    public static String miniFrame(Object source, List<LyricsLine> lines, long durationMs,
                                   long positionMs, String trackId, String title, String artist) {
        final boolean trackChanged = !Objects.equals(timelineTrackId, trackId)
                || !Objects.equals(timelineTitle, title) || !Objects.equals(timelineArtist, artist);
        if (timelineSource != source || timeline == null || timelineDuration != durationMs || trackChanged) {
            if (trackChanged) {
                lastSungIndex = -1;
                lastSungPosition = -1;
                lastSungText = null;
            }
            timeline = new MiniLyricsTimeline(lines, durationMs);
            timelineSource = source;
            timelineDuration = durationMs;
            timelineTrackId = trackId;
            timelineTitle = title;
            timelineArtist = artist;
        }
        final long shifted = MiniLyricsFormatter.selectionPosition(positionMs, Settings.LYRICS_MINI_NEXT_OFFSET_MS.get());
        final MiniLyricsTimeline.Selection selection = timeline.at(shifted);
        if (selection.vocal() != null) {
            final MiniLyricsTimeline.Vocal vocal = selection.vocal();
            final String rendered = miniLine(vocal.line(), vocal.endMs(), shifted,
                    MiniLyricsFormatter.repeatCount(lines, vocal.index()));
            lastSungIndex = vocal.index();
            lastSungPosition = shifted;
            lastSungText = rendered;
            lastSungRawText = vocal.line().text();
            return rendered;
        }
        final MiniLyricsTimeline.Gap gap = selection.gap();
        if (gap == null) return lastSungIndex >= 0 && lastSungText != null ? lastSungText : null;
        String previous = title == null ? "" : title;
        if (gap.previous() != null) {
            final MiniLyricsTimeline.Vocal vocal = gap.previous();
            previous = lastSungIndex == vocal.index() && lastSungText != null
                    && Objects.equals(lastSungRawText, vocal.line().text())
                    && lastSungPosition >= vocal.startMs() && lastSungPosition < gap.startMs()
                    ? lastSungText : miniLine(vocal.line(), vocal.endMs(), vocal.endMs() - 1,
                            MiniLyricsFormatter.repeatCount(lines, vocal.index()));
        }
        return MiniLyricsFormatter.instrumental(Settings.LYRICS_MINI_INSTRUMENTAL.get(),
                gap.startMs(), gap.endMs(), shifted, title, artist, previous);
    }
}
