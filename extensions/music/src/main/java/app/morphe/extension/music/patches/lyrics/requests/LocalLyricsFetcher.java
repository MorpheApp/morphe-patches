/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.MetadataCleaner;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.parsers.TTMLParser;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class LocalLyricsFetcher {

    public static final String PROVIDER_NAME = "Local";

    /**
     * Value of {@code MediaMetadataRetriever.METADATA_KEY_LYRICS}. Kept as a literal because the
     * music modules compile SDK does not expose the constant symbol. The key is only honored by
     * the framework on API 30+, so it is used only as a best-effort fallback after direct parsing.
     */
    private static final int METADATA_KEY_LYRICS = 0x104;
    private static final int MAX_TAG_BYTES = 16 * 1024 * 1024;
    private static final int M4A_TAIL_BYTES = 4 * 1024 * 1024;
    private static final int MAX_M4A_DEPTH = 32;

    /** Embedded lyrics tag ranks, TTMLLYRIC outranking SYNCEDLYRICS. */
    private static final int RANK_TTMLLYRIC = 4;
    private static final int RANK_SYNCEDLYRICS = 3;
    private static final int RANK_LYRICS = 2;
    private static final int RANK_LYRIC = 1;
    private static final int RANK_UNSYNCEDLYRICS = 0;

    private static final String ITUNES_MEAN = "com.apple.iTunes";

    private LocalLyricsFetcher() {
    }

    @Nullable
    public static Lyrics fetch(@Nullable Uri mediaUri) {
        if (mediaUri == null) {
            return null;
        }

        try {
            return readEmbeddedLyrics(mediaUri);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Lyrics readEmbeddedLyrics(Uri mediaUri) {
        Context context = Utils.getContext();
        if (context == null) {
            return null;
        }

        byte[] header = readBytes(context, mediaUri, 10);
        byte[] data;
        if (header != null && header.length >= 10
                && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            final int tagSize = syncsafe(header[6], header[7], header[8], header[9]);
            final int totalRead = 10 + tagSize;
            data = readBytes(context, mediaUri,
                    Math.min(totalRead, MAX_TAG_BYTES));
        } else {
            // Not ID3v2 — read default limit for FLAC/OGG/M4A tail detection.
            data = readBytes(context, mediaUri, MAX_TAG_BYTES);
        }

        if (data != null) {
            Lyrics parsed = parseCandidates(extractLyricsCandidates(data));
            if (parsed != null) {
                return parsed;
            }
            if (isLikelyM4a(data)) {
                byte[] tail = readBytesTail(context, mediaUri);
                if (tail != null) {
                    Lyrics tailParsed = parseCandidates(extractLyricsCandidates(tail));
                    if (tailParsed != null) {
                        return tailParsed;
                    }
                }
            }
        }

        return fallbackViaMediaMetadataRetriever(context, mediaUri);
    }

    @Nullable
    public static Uri resolveMediaStoreUri(String title, String artist, int durationSeconds,
                                           @Nullable String rawTitle, @Nullable String rawArtist) {
        String primaryTitle = (title != null && !title.trim().isEmpty()) ? title : rawTitle;
        String primaryArtist = (artist != null && !artist.trim().isEmpty()) ? artist : rawArtist;
        if (primaryTitle == null || primaryTitle.trim().isEmpty()) {
            return null;
        }
        Context context = Utils.getContext();
        if (context == null) {
            return null;
        }
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return null;
        }

        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
        };

        // The raw title widens the query. A LIKE query can also find a different song whose title
        // contains this one, so every row is checked against the playing track.
        List<String> titleCandidates = new ArrayList<>(2);
        titleCandidates.add(title);
        if (rawTitle != null && !rawTitle.equals(title)) {
            titleCandidates.add(rawTitle);
        }

        Uri best = null;
        long bestScore = Long.MAX_VALUE;
        try {
            for (String t : titleCandidates) {
                if (t == null || t.trim().isEmpty()) {
                    continue;
                }
                String selection = MediaStore.Audio.Media.TITLE + " LIKE ? ESCAPE '\\'";
                Cursor cursor = resolver.query(base, projection, selection,
                        new String[]{likeEscape(t)}, null);
                if (cursor != null && cursor.getCount() == 0) {
                    cursor.close();
                    cursor = resolver.query(base, projection, selection,
                            new String[]{"%" + likeEscape(t) + "%"}, null);
                }
                if (cursor == null) {
                    continue;
                }
                try {
                    final int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                    final int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                    final int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    final int durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                    if (idCol < 0) {
                        continue;
                    }
                    while (cursor.moveToNext()) {
                        String storedTitle = titleCol >= 0 ? cursor.getString(titleCol) : null;
                        String storedArtist = artistCol >= 0 ? cursor.getString(artistCol) : null;
                        if (!isSameTrack(title, rawTitle, primaryArtist, rawArtist, storedTitle, storedArtist)) {
                            continue;
                        }

                        final long id = cursor.getLong(idCol);
                        final long durMs = (durCol >= 0) ? cursor.getLong(durCol) : 0;
                        long score = 0;
                        if (durationSeconds > 0 && durMs > 0) {
                            score = Math.abs(durMs - (long) durationSeconds * 1000L);
                        }
                        if (score < bestScore) {
                            bestScore = score;
                            best = ContentUris.withAppendedId(base, id);
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not query MediaStore for candidate URI", ex);
            return null;
        }
        return best;
    }

    /**
     * Whether a MediaStore row is the playing track. The title must be equal after normalizing,
     * and the stored artist must be the full credit or credit the playing artist by name.
     * A file by another artist is never used, even if its duration is closer.
     */
    static boolean isSameTrack(@Nullable String title, @Nullable String rawTitle,
                               @Nullable String artist, @Nullable String rawArtist,
                               @Nullable String storedTitle, @Nullable String storedArtist) {
        String candidateTitle = LyricsRequests.normalizeForMatch(storedTitle);
        if (candidateTitle.isEmpty()
                || !(candidateTitle.equals(LyricsRequests.normalizeForMatch(title))
                || candidateTitle.equals(LyricsRequests.normalizeForMatch(rawTitle)))) {
            return false;
        }

        String candidateArtist = LyricsRequests.normalizeForMatch(storedArtist);
        if (candidateArtist.isEmpty()) {
            return false;
        }
        if (candidateArtist.equals(LyricsRequests.normalizeForMatch(rawArtist))) {
            return true;
        }
        String wantArtist = LyricsRequests.normalizeForMatch(artist);
        if (wantArtist.isEmpty()) {
            return false;
        }
        for (String credited : MetadataCleaner.splitArtists(storedArtist)) {
            if (wantArtist.equals(LyricsRequests.normalizeForMatch(credited))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static byte[] readBytes(Context context, Uri uri, int limit) {
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return null;
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 1 << 16));
            byte[] buf = new byte[1 << 16];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (total + n > limit) {
                    final int remaining = limit - total;
                    if (remaining > 0) {
                        out.write(buf, 0, remaining);
                    }
                    break;
                }
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        } catch (IOException | SecurityException | NullPointerException ex) {
            Logger.printDebug(() -> "Could not read bytes from URI", ex);
            return null;
        }
    }

    @Nullable
    private static byte[] readBytesTail(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return null;
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            byte[] ring = new byte[M4A_TAIL_BYTES];
            int ringPos = 0;
            int ringLen = 0;
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                int remaining = n;
                int bufOff = 0;
                while (remaining > 0) {
                    final int space = M4A_TAIL_BYTES - ringLen;
                    if (space >= remaining) {
                        System.arraycopy(buf, bufOff, ring, ringPos, remaining);
                        ringPos = (ringPos + remaining) % M4A_TAIL_BYTES;
                        ringLen += remaining;
                        bufOff += remaining;
                        remaining = 0;
                    } else {
                        System.arraycopy(buf, bufOff, ring, ringPos, space);
                        ringPos = (ringPos + space) % M4A_TAIL_BYTES;
                        ringLen = M4A_TAIL_BYTES;
                        bufOff += space;
                        remaining -= space;
                    }
                }
            }
            if (ringLen == 0) {
                return null;
            }
            if (ringLen < M4A_TAIL_BYTES) {
                byte[] result = new byte[ringLen];
                System.arraycopy(ring, 0, result, 0, ringLen);
                return result;
            }
            byte[] result = new byte[M4A_TAIL_BYTES];
            System.arraycopy(ring, ringPos, result, 0, M4A_TAIL_BYTES - ringPos);
            System.arraycopy(ring, 0, result, M4A_TAIL_BYTES - ringPos, ringPos);
            return result;
        } catch (IOException | SecurityException | NullPointerException ex) {
            Logger.printDebug(() -> "Could not read tail bytes from URI", ex);
            return null;
        }
    }

    private static boolean isLikelyM4a(byte[] d) {
        return d.length >= 8
                && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p';
    }

    /** A parsed tag value; {@code ttml} marks one forced to TTML. */
    record LyricsValue(String value, boolean ttml) {
    }

    private record RankedLyricsValue(int rank, String value, boolean ttml) {
    }

    /** Descending priority; stable ties keep tag order. */
    private static List<LyricsValue> ordered(List<RankedLyricsValue> ranked) {
        ranked.sort((a, b) -> Integer.compare(b.rank(), a.rank()));
        List<LyricsValue> out = new ArrayList<>(ranked.size());
        for (RankedLyricsValue value : ranked) {
            out.add(new LyricsValue(value.value(), value.ttml()));
        }
        return out;
    }

    @Nullable
    static String extractLyrics(byte[] d) {
        List<LyricsValue> candidates = extractLyricsCandidates(d);
        return candidates.isEmpty() ? null : candidates.get(0).value();
    }

    /** Richest tag first so a broken value falls through to the next tag. */
    static List<LyricsValue> extractLyricsCandidates(byte[] d) {
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            List<LyricsValue> s = parseId3v2(d);
            if (!s.isEmpty()) {
                return s;
            }
        }
        if (d.length >= 4 && d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C') {
            List<LyricsValue> s = parseFlac(d);
            if (!s.isEmpty()) {
                return s;
            }
        }
        if (d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') {
            List<LyricsValue> s = parseOgg(d);
            if (!s.isEmpty()) {
                return s;
            }
        }
        if (d.length >= 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            List<RankedLyricsValue> found = new ArrayList<>();
            walkM4a(d, 0, d.length, 0, found);
            return ordered(found);
        }
        return List.of();
    }

    private static boolean isOutOfBounds(byte[] d, int off, int len) {
        return off < 0 || len <= 0 || off + (long) len > d.length;
    }

    private static List<LyricsValue> parseId3v2(byte[] d) {
        if (d.length < 10) {
            return List.of();
        }
        final int major = d[3] & 0xFF;
        final int tagSize = syncsafe(d[6], d[7], d[8], d[9]);
        int pos = 10;
        if ((major == 3 || major == 4) && (d[5] & 0x40) != 0) {
            if (d.length < 14) {
                return List.of();
            }
            // ID3v2.4 stores the extended header size as syncsafe, ID3v2.3 as plain big-endian.
            final int extSize = (major == 4)
                    ? syncsafe(d[10], d[11], d[12], d[13])
                    : readInt32BE(d, 10);
            if (extSize < 0 || extSize > d.length - 10) {
                return List.of();
            }
            pos = 10 + extSize;
        }
        final int end = Math.min(10 + tagSize, d.length);
        final int idLen;
        final int headerLen;
        final boolean syncsafeSize;
        if (major == 2) {
            idLen = 3;
            headerLen = 6;
            syncsafeSize = false;
        } else {
            idLen = 4;
            headerLen = 10;
            syncsafeSize = (major == 4);
        }
        List<RankedLyricsValue> found = new ArrayList<>();
        while (pos + headerLen <= end) {
            String id = new String(d, pos, idLen, StandardCharsets.ISO_8859_1);
            final int size = (headerLen == 6)
                    ? (((d[pos + 3] & 0xFF) << 16) | ((d[pos + 4] & 0xFF) << 8) | (d[pos + 5] & 0xFF))
                    : (syncsafeSize ? syncsafe(d[pos + 4], d[pos + 5], d[pos + 6], d[pos + 7])
                       : readInt32BE(d, pos + 4));
            final int bodyOff = pos + headerLen;
            if (size < 0 || bodyOff + size > d.length) {
                break;
            }
            String candidate = null;
            int candidateRank = -1;
            boolean candidateTtml = false;
            switch (id) {
                case "USLT", "ULT" -> {
                    candidate = parseTextFrame(d, bodyOff, size);
                    candidateRank = RANK_UNSYNCEDLYRICS;
                }
                case "SYLT", "SLT" -> {
                    candidate = parseSylt(d, bodyOff, size);
                    candidateRank = RANK_SYNCEDLYRICS;
                }
                case "TXXX" -> {
                    final String desc = parseTxxxDescription(d, bodyOff, size);
                    candidateRank = id3DescRank(desc);
                    if (candidateRank >= 0) {
                        candidate = parseTxxxValue(d, bodyOff, size);
                        candidateTtml = isTtmlLyricsName(desc);
                    }
                }
                case "COMM" -> {
                    final String desc = parseCommDescription(d, bodyOff, size);
                    candidateRank = id3DescRank(desc);
                    if (candidateRank >= 0) {
                        candidate = parseCommBody(d, bodyOff, size);
                        candidateTtml = isTtmlLyricsName(desc);
                    }
                }
            }
            if (candidateRank >= 0 && candidate != null && !candidate.trim().isEmpty()) {
                found.add(new RankedLyricsValue(candidateRank, candidate, candidateTtml));
            }
            // Null padding detection: all bytes of frame ID are 0 -> padding reached.
            boolean allNull = true;
            for (int k = 0; k < idLen; k++) {
                if (d[pos + k] != 0) {
                    allNull = false;
                    break;
                }
            }
            if (allNull) {
                break;
            }
            pos = bodyOff + size;
        }
        return ordered(found);
    }

    /** Rank of an ID3 lyrics description; -1 when it is not a lyrics' description. */
    private static int id3DescRank(@Nullable String desc) {
        if (desc == null) {
            return -1;
        }
        // Match the whole name: "LYRICIST" must not be treated as "LYRIC" lyrics.
        // A language suffix such as "LYRICS-zho" or "LYRICS:zho" is allowed.
        final String name = stripLanguageSuffix(desc.trim());
        if (name.equalsIgnoreCase("TTMLLYRIC")) {
            return RANK_TTMLLYRIC;
        }
        if (name.equalsIgnoreCase("SYNCEDLYRICS")) {
            return RANK_SYNCEDLYRICS;
        }
        if (name.equalsIgnoreCase("LYRICS")) {
            return RANK_LYRICS;
        }
        if (name.equalsIgnoreCase("LYRIC")) {
            return RANK_LYRIC;
        }
        if (name.equalsIgnoreCase("UNSYNCEDLYRICS")) {
            return RANK_UNSYNCEDLYRICS;
        }
        return -1;
    }

    /** Strip a trailing language code such as "-zho", ":zho" or "_zho" from a tag name. */
    private static String stripLanguageSuffix(String name) {
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == '-' || c == ':' || c == '_') {
                return name.substring(0, i);
            }
        }
        return name;
    }

    /** True when the tag itself forces TTML parsing. */
    private static boolean isTtmlLyricsName(@Nullable String name) {
        return name != null && "TTMLLYRIC".equalsIgnoreCase(stripLanguageSuffix(name.trim()));
    }

    @Nullable
    private static String parseTxxxDescription(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len <= 1) return null;
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;
        final int descEnd = indexOfNullTerm(d, off + 1, frameEnd, enc);
        if (descEnd < 0 || descEnd >= frameEnd) return null;
        return decodeAutoDetect(copy(d, off + 1, descEnd - off - 1), enc).trim();
    }

    @Nullable
    private static String parseTxxxValue(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len <= 1) return null;
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;
        final int descEnd = indexOfNullTerm(d, off + 1, frameEnd, enc);
        if (descEnd < 0 || descEnd >= frameEnd) return null;
        int valueStart = descEnd;
        valueStart = skipBomOrBidiMarker(d, valueStart, frameEnd, enc);
        final int actualEnd;
        if (enc == 1 || enc == 2) {
            actualEnd = frameEnd;
        } else {
            final int valEnd = indexOfNullTerm(d, valueStart, frameEnd, enc);
            actualEnd = (valEnd >= 0) ? valEnd : frameEnd;
        }
        final int textLen = actualEnd - valueStart;
        if (textLen <= 0) return null;
        return decodeAutoDetect(copy(d, valueStart, textLen), enc).trim();
    }

    @Nullable
    private static String parseCommDescription(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len <= 4) return null;
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;
        final int descStart = off + 1 + 3;
        final int descEnd = indexOfNullTerm(d, descStart, frameEnd, enc);
        if (descEnd < 0 || descEnd >= frameEnd) return null;
        return decodeAutoDetect(copy(d, descStart, descEnd - descStart), enc).trim();
    }

    @Nullable
    private static String parseCommBody(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len <= 4) return null;
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;
        final int descStart = off + 1 + 3;
        final int descEnd = indexOfNullTerm(d, descStart, frameEnd, enc);
        if (descEnd < 0 || descEnd >= frameEnd) return null;
        int bodyStart = descEnd;
        bodyStart = skipBomOrBidiMarker(d, bodyStart, frameEnd, enc);
        final int actualEnd;
        if (enc == 1 || enc == 2) {
            actualEnd = frameEnd;
        } else {
            final int bodyEnd = indexOfNullTerm(d, bodyStart, frameEnd, enc);
            actualEnd = (bodyEnd >= 0) ? bodyEnd : frameEnd;
        }
        final int bodyLen = actualEnd - bodyStart;
        if (bodyLen <= 0) return null;
        return decodeAutoDetect(copy(d, bodyStart, bodyLen), enc).trim();
    }

    @Nullable
    private static String parseTextFrame(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len <= 0) {
            return null;
        }
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;

        int descStart = off + 1 + 3;
        if (descStart > frameEnd) {
            descStart = off + 1;
        }

        int lyricsStart = indexOfNullTerm(d, descStart, frameEnd, enc);
        if (lyricsStart < 0 || lyricsStart >= frameEnd) {
            return null;
        }

        lyricsStart = skipBomOrBidiMarker(d, lyricsStart, frameEnd, enc);

        final int lyricsEnd;
        if (enc == 1 || enc == 2) {
            lyricsEnd = frameEnd;
        } else {
            final int end = indexOfNullTerm(d, lyricsStart, frameEnd, enc);
            lyricsEnd = (end >= 0) ? end : frameEnd;
        }

        final int textLen = lyricsEnd - lyricsStart;
        if (textLen <= 0) {
            return null;
        }
        return decodeAutoDetect(copy(d, lyricsStart, textLen), enc).trim();
    }

    @Nullable
    private static String parseSylt(byte[] d, int off, int len) {
        if (isOutOfBounds(d, off, len) || len < 5) {
            return null;
        }
        final int enc = d[off] & 0xFF;
        final int tsFormat = d[off + 4] & 0xFF;
        final int tsBytes = (tsFormat == 2) ? 4 : 2;
        final int frameEnd = off + len;
        int p = off + 1 + 3 + 1 + 1;
        if (p + 1 > frameEnd) {
            return null;
        }
        final int textStart = indexOfNullTerm(d, p, frameEnd, enc);
        if (textStart < 0) {
            return null;
        }
        final int term = termLength(enc);
        StringBuilder sb = new StringBuilder();
        int q = textStart;
        while (q < frameEnd) {
            final int tEnd = indexOfNullTerm(d, q, frameEnd, enc);
            final int segEnd = (tEnd < 0) ? frameEnd : tEnd;
            final int segLen = segEnd - q;
            if (segLen <= 0) {
                break;
            }
            String seg = decodeAutoDetect(copy(d, q, segLen), enc).trim();
            long tsMs = -1;
            if (tEnd >= 0 && tEnd + tsBytes <= frameEnd) {
                tsMs = (tsBytes == 4)
                        ? readInt32BE(d, tEnd) & 0xFFFFFFFFL
                        : ((d[tEnd] & 0xFF) << 8) | (d[tEnd + 1] & 0xFF);
            }
            if (!seg.isEmpty()) {
                if (tsMs >= 0) {
                    sb.append('[')
                            .append(LRCParser.formatCentiseconds(tsMs))
                            .append(']');
                }
                sb.append(seg).append('\n');
            }
            if (tEnd < 0) {
                break;
            }
            q = tEnd + term + tsBytes;
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static List<LyricsValue> parseFlac(byte[] d) {
        if (d.length < 4 || !(d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C')) {
            return List.of();
        }
        int pos = 4;
        while (pos + 4 <= d.length) {
            final int header = d[pos] & 0xFF;
            final boolean last = (header & 0x80) != 0;
            final int type = header & 0x7F;
            final int size = readU24BE(d, pos + 1);
            final int body = pos + 4;
            if (body + size > d.length) {
                break;
            }
            if (type == 4) {
                List<LyricsValue> s = parseVorbisComment(d, body, size);
                if (!s.isEmpty()) {
                    return s;
                }
            }
            pos = body + size;
            if (last) {
                break;
            }
        }
        return List.of();
    }

    private static List<LyricsValue> parseVorbisComment(byte[] d, int start, int size) {
        if (isOutOfBounds(d, start, size)) {
            return List.of();
        }
        final int end = start + size;
        int p = start;
        if (p + 4 > end) {
            return List.of();
        }
        final int vendorLen = readInt32LE(d, p);
        if (vendorLen < 0 || vendorLen > end - (p + 4)) {
            return List.of();
        }
        p += 4 + vendorLen;
        if (p + 4 > end) {
            return List.of();
        }
        final int count = readInt32LE(d, p);
        p += 4;
        List<RankedLyricsValue> found = new ArrayList<>();
        for (int i = 0; i < count && p + 4 <= end; i++) {
            final int len = readInt32LE(d, p);
            p += 4;
            if (len < 0 || p + (long) len > end) {
                break;
            }
            String comment = new String(d, p, len, StandardCharsets.UTF_8);
            p += len;
            final int eq = comment.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String name = comment.substring(0, eq).trim();
            final int rank = vorbisLyricsRank(name);
            if (rank < 0) {
                continue;
            }
            final String value = comment.substring(eq + 1);
            if (value.trim().isEmpty()) {
                continue;
            }
            found.add(new RankedLyricsValue(rank, value, isTtmlLyricsName(name)));
        }
        return ordered(found);
    }

    private static List<LyricsValue> parseOgg(byte[] d) {
        int pos = 0;
        StringBuilder packet = new StringBuilder();
        while (pos + 27 <= d.length) {
            if (d[pos] != 'O' || d[pos + 1] != 'g' || d[pos + 2] != 'g' || d[pos + 3] != 'S') {
                break;
            }
            final int numSegments = d[pos + 26] & 0xFF;
            final int tableEnd = pos + 27 + numSegments;
            if (tableEnd > d.length) {
                break;
            }
            int dataPos = tableEnd;
            for (int i = 0; i < numSegments; i++) {
                final int segLen = d[pos + 27 + i] & 0xFF;
                if (dataPos + segLen > d.length) {
                    return List.of();
                }
                packet.append(new String(d, dataPos, segLen, StandardCharsets.ISO_8859_1));
                dataPos += segLen;
            }
            // A packet continues on the next page when the last lacing value is 255.
            final boolean continues = numSegments > 0
                    && (d[pos + 27 + numSegments - 1] & 0xFF) == 255;
            if (!continues) {
                String payload = packet.toString();
                final int marker = payload.indexOf("\003vorbis");
                if (marker >= 0) {
                    List<LyricsValue> s = parseVorbisCommentFromPacket(payload, marker + 7);
                    if (!s.isEmpty()) {
                        return s;
                    }
                }
                packet.setLength(0);
            }
            pos = dataPos;
        }
        return List.of();
    }

    private static List<LyricsValue> parseVorbisCommentFromPacket(String payload, int off) {
        final int len = payload.length();
        if (off < 0 || off + 4 > len) {
            return List.of();
        }
        int p = off;
        final int vendorLen = ((payload.charAt(p) & 0xFF))
                | ((payload.charAt(p + 1) & 0xFF) << 8)
                | ((payload.charAt(p + 2) & 0xFF) << 16)
                | ((payload.charAt(p + 3) & 0xFF) << 24);
        if (vendorLen < 0 || vendorLen > len - (p + 4)) {
            return List.of();
        }
        p += 4 + vendorLen;
        if (p + 4 > len) {
            return List.of();
        }
        final int count = ((payload.charAt(p) & 0xFF))
                | ((payload.charAt(p + 1) & 0xFF) << 8)
                | ((payload.charAt(p + 2) & 0xFF) << 16)
                | ((payload.charAt(p + 3) & 0xFF) << 24);
        p += 4;
        List<RankedLyricsValue> found = new ArrayList<>();
        for (int i = 0; i < count && p + 4 <= len; i++) {
            final int entryLen = ((payload.charAt(p) & 0xFF))
                    | ((payload.charAt(p + 1) & 0xFF) << 8)
                    | ((payload.charAt(p + 2) & 0xFF) << 16)
                    | ((payload.charAt(p + 3) & 0xFF) << 24);
            p += 4;
            if (entryLen < 0 || p + (long) entryLen > len) {
                break;
            }
            String comment = payload.substring(p, p + entryLen);
            p += entryLen;
            final int eq = comment.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String name = comment.substring(0, eq).trim();
            final int rank = vorbisLyricsRank(name);
            if (rank < 0) {
                continue;
            }
            final String value = comment.substring(eq + 1);
            if (value.trim().isEmpty()) {
                continue;
            }
            final String decoded = decodeAutoDetect(
                    value.getBytes(StandardCharsets.ISO_8859_1), 3);
            found.add(new RankedLyricsValue(rank, decoded, isTtmlLyricsName(name)));
        }
        return ordered(found);
    }

    /** Rank of a Vorbis lyrics tag name; -1 when it is not a lyrics tag. */
    private static int vorbisLyricsRank(String name) {
        final String base = stripLanguageSuffix(name.trim());
        if (base.equalsIgnoreCase("TTMLLYRIC")) {
            return RANK_TTMLLYRIC;
        }
        if (base.equalsIgnoreCase("SYNCEDLYRICS")) {
            return RANK_SYNCEDLYRICS;
        }
        if (base.equalsIgnoreCase("LYRICS")) {
            return RANK_LYRICS;
        }
        if (base.equalsIgnoreCase("LYRIC")) {
            return RANK_LYRIC;
        }
        if (base.equalsIgnoreCase("UNSYNCEDLYRICS")) {
            return RANK_UNSYNCEDLYRICS;
        }
        return -1;
    }

    private static void walkM4a(byte[] d, int start, int end, int depth,
                                List<RankedLyricsValue> found) {
        if (depth > MAX_M4A_DEPTH) {
            return;
        }
        int pos = start;
        while (pos + 8 <= end) {
            final int size = readInt32BE(d, pos);
            if (size < 8 || pos + size > end) {
                break;
            }
            final int c0 = d[pos + 4] & 0xFF;
            final int c1 = d[pos + 5] & 0xFF;
            final int c2 = d[pos + 6] & 0xFF;
            final int c3 = d[pos + 7] & 0xFF;
            final String type = new String(d, pos + 4, 4, StandardCharsets.ISO_8859_1);
            final int rank = m4aLyricsRank(c0, c1, c2, c3);
            if (rank >= 0) {
                offerM4aLyrics(found, rank, readM4aData(d, pos + 8, pos + size), false);
            } else if (type.equals("----")) {
                final String mean = readM4aFreeformText(d, pos + 8, pos + size, "mean");
                final String name = readM4aFreeformText(d, pos + 8, pos + size, "name");
                if (ITUNES_MEAN.equals(mean) && isTtmlLyricsName(name)) {
                    offerM4aLyrics(found, RANK_TTMLLYRIC,
                            readM4aData(d, pos + 8, pos + size), true);
                }
            } else if (type.equals("meta") || type.equals("ilst") || type.equals("udta")
                    || type.equals("moov") || type.equals("trak") || type.equals("mdia")
                    || type.equals("minf") || type.equals("stbl")) {
                int child = pos + 8;
                if (type.equals("meta")) {
                    child = pos + 12;
                }
                walkM4a(d, child, pos + size, depth + 1, found);
            }
            pos += size;
        }
    }

    private static void offerM4aLyrics(List<RankedLyricsValue> found, int rank,
                                       @Nullable String value, boolean ttml) {
        if (value != null && !value.trim().isEmpty()) {
            found.add(new RankedLyricsValue(rank, value, ttml));
        }
    }

    /** UTF-8 text of a freeform {@code mean} or {@code name} child atom. */
    @Nullable
    private static String readM4aFreeformText(byte[] d, int start, int end, String type) {
        int p = start;
        while (p + 8 <= end) {
            final int size = readInt32BE(d, p);
            if (size < 8 || p + size > end) {
                break;
            }
            final String childType = new String(d, p + 4, 4, StandardCharsets.ISO_8859_1);
            if (childType.equals(type)) {
                final int contentStart = p + 12; // size + type + version/flags
                final int contentEnd = p + size;
                if (contentEnd <= contentStart) {
                    return null;
                }
                String text = new String(d, contentStart, contentEnd - contentStart,
                        StandardCharsets.UTF_8);
                return text.replace("\uFEFF", "").trim();
            }
            p += size;
        }
        return null;
    }

    /** Rank of an M4A lyrics atom; -1 when the four-character code is not lyrics. */
    private static int m4aLyricsRank(int c0, int c1, int c2, int c3) {
        if (c0 == 0xA9 && c1 == 'l' && c2 == 'y' && c3 == 'r') {
            return RANK_LYRICS;
        }
        if (c0 == 'l' && c1 == 'y' && c2 == 'r' && c3 == ' ') {
            return RANK_LYRIC;
        }
        if (c0 == 'L' && c1 == 'Y' && c2 == 'R' && c3 == ' ') {
            return RANK_UNSYNCEDLYRICS;
        }
        return -1;
    }

    @Nullable
    private static String readM4aData(byte[] d, int start, int end) {
        int p = start;
        while (p + 8 <= end) {
            final int size = readInt32BE(d, p);
            if (size < 8 || p + size > end) {
                break;
            }
            final int c0 = d[p + 4] & 0xFF;
            final int c1 = d[p + 5] & 0xFF;
            final int c2 = d[p + 6] & 0xFF;
            final int c3 = d[p + 7] & 0xFF;
            if (c0 == 'd' && c1 == 'a' && c2 == 't' && c3 == 'a') {
                final int contentStart = p + 16;
                final int contentEnd = p + size;
                if (contentEnd <= contentStart) {
                    return null;
                }
                byte[] content = copy(d, contentStart, contentEnd - contentStart);
                String s = decodeAutoDetect(content, 3);
                String trimmed = s.replace("\uFEFF", "").replace("\0", " ").trim();
                return trimmed.isEmpty() ? null : trimmed;
            }
            p += size;
        }
        return null;
    }

    @Nullable
    private static Lyrics fallbackViaMediaMetadataRetriever(Context context, Uri uri) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(context, uri);
            String raw = retriever.extractMetadata(METADATA_KEY_LYRICS);
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            return parse(raw);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not extract lyrics via MediaMetadataRetriever", ex);
            return null;
        }
    }

    @Nullable
    private static Lyrics parse(@Nullable String raw) {
        return parse(raw, false);
    }

    /** Priority order; stop at the first usable value so broken tags fall through. */
    @Nullable
    static Lyrics parseCandidates(List<LyricsValue> candidates) {
        for (LyricsValue candidate : candidates) {
            Lyrics parsed = parse(candidate.value(), candidate.ttml());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    @Nullable
    private static Lyrics parse(@Nullable String raw, boolean expectTtml) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        if (expectTtml || looksLikeTtml(raw)) {
            try {
                return TTMLParser.ttmlToLyrics(raw, PROVIDER_NAME, null);
            } catch (RuntimeException ex) {
                Logger.printDebug(() -> "Could not parse embedded TTML lyrics", ex);
                return null;
            }
        }

        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(raw);
        if (!result.lines.isEmpty()) {
            // LRC line timestamps synchronize whole lines; word-level
            // (karaoke) timestamps are not required for synchronized lyrics.
            return new Lyrics(result.lines, PROVIDER_NAME, true, null, null, null,
                    null, result.creditLines.isEmpty() ? null : result.creditLines,
                    raw, "lrc", null, null, null);
        }

        List<LyricsLine> plain = LRCParser.parsePlain(raw);
        if (!plain.isEmpty()) {
            return new Lyrics(plain, PROVIDER_NAME, false, null, null, null, null, raw, "lrc", null);
        }

        return null;
    }

    /**
     * Content-based: a document starting with a tag and containing a {@code tt} root.
     */
    private static boolean looksLikeTtml(String raw) {
        int start = 0;
        if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
            start = 1;
        }
        while (start < raw.length() && Character.isWhitespace(raw.charAt(start))) {
            start++;
        }
        return start < raw.length() && raw.charAt(start) == '<' && raw.indexOf("<tt", start) >= 0;
    }

    private static int syncsafe(int b0, int b1, int b2, int b3) {
        return ((b0 & 0x7F) << 21) | ((b1 & 0x7F) << 14) | ((b2 & 0x7F) << 7) | (b3 & 0x7F);
    }

    private static int readInt32BE(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static int readInt32LE(byte[] d, int off) {
        return ((d[off] & 0xFF)) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static int skipBomOrBidiMarker(byte[] d, int start, int end, int enc) {
        int pos = start;
        if (enc == 1 || enc == 2) {
            for (int i = pos; i + 1 < end; i++) {
                if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xFE) {
                    pos = i;
                    break;
                }
                if ((d[i] & 0xFF) == 0xFE && (d[i + 1] & 0xFF) == 0xFF) {
                    pos = i;
                    break;
                }
            }
            for (int i = pos; i + 2 < end; i++) {
                if ((d[i] & 0xFF) == 0xEF
                        && (d[i + 1] & 0xFF) == 0xBB
                        && (d[i + 2] & 0xFF) == 0xBF) {
                    pos = i;
                    break;
                }
            }
        }
        return pos;
    }

    private static int readU24BE(byte[] d, int off) {
        return ((d[off] & 0xFF) << 16) | ((d[off + 1] & 0xFF) << 8) | (d[off + 2] & 0xFF);
    }

    private static int termLength(int enc) {
        return (enc == 1 || enc == 2) ? 2 : 1;
    }

    private static int indexOfNullTerm(byte[] d, int start, int end, int enc) {
        final int term = termLength(enc);
        final int limit = end - term + 1;
        for (int i = start; i < limit; i++) {
            if (term == 1) {
                if ((d[i] & 0xFF) == 0) {
                    return i + 1;
                }
            } else {
                // UTF-16 terminators must be aligned to a code unit, otherwise the low byte
                // of an ASCII character followed by the terminator looks like a terminator.
                if (((i - start) & 1) == 0
                        && (d[i] & 0xFF) == 0 && (d[i + 1] & 0xFF) == 0) {
                    return i + 2;
                }
            }
        }
        return -1;
    }

    private static String decodeAutoDetect(byte[] b, int declaredEnc) {
        if (b == null || b.length == 0) {
            return "";
        }
        if (b.length >= 3) {
            if ((b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
                return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
            }
        }
        if (b.length >= 2) {
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
                return new String(b, StandardCharsets.UTF_16);
            }
            if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
                return new String(b, StandardCharsets.UTF_16BE);
            }
        }
        if (declaredEnc == 1 && b.length >= 2) {
            return new String(b, StandardCharsets.UTF_16LE);
        }
        if (declaredEnc == 2 && b.length >= 2) {
            return new String(b, StandardCharsets.UTF_16BE);
        }
        if (isValidUtf8(b)) {
            return new String(b, StandardCharsets.UTF_8);
        }
        if (isLikelyDoubleByte(b, c -> c >= 0x81 && c <= 0xFE,
                c -> c >= 0x40 && c <= 0xFE && c != 0x7F)) {
            try {
                return new String(b, "GBK");
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not decode GBK text", ex);
            }
        }
        if (isLikelyDoubleByte(b, c -> c >= 0x81 && c <= 0xFE,
                c -> (c >= 0x40 && c <= 0x7E) || (c >= 0xA1 && c <= 0xFE))) {
            try {
                return new String(b, "Big5");
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not decode Big5 text", ex);
            }
        }
        if (isLikelyDoubleByte(b, c -> (c >= 0x81 && c <= 0x9F) || (c >= 0xE0 && c <= 0xEF),
                c -> (c >= 0x40 && c <= 0x7E) || (c >= 0x80 && c <= 0xFC))) {
            try {
                return new String(b, "Shift_JIS");
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not decode Shift_JIS text", ex);
            }
        }
        if (isLikelyDoubleByte(b, c -> c >= 0x81 && c <= 0xFE,
                c -> c >= 0x41 && c <= 0xFE)) {
            try {
                return new String(b, "EUC-KR");
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not decode EUC-KR text", ex);
            }
        }
        if (isLikelyWindows1252(b)) {
            try {
                return new String(b, "Windows-1252");
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not decode Windows-1252 text", ex);
            }
        }
        return switch (declaredEnc) {
            case 1 -> new String(b, StandardCharsets.UTF_16);
            case 2 -> new String(b, StandardCharsets.UTF_16BE);
            case 3 -> new String(b, StandardCharsets.UTF_8);
            default -> new String(b, StandardCharsets.ISO_8859_1);
        };
    }

    private static boolean isValidUtf8(byte[] b) {
        int i = 0;
        while (i < b.length) {
            int b0 = b[i] & 0xFF;
            int expectedCont;
            if (b0 <= 0x7F) {
                i++;
                continue;
            } else if (b0 >= 0xC2 && b0 <= 0xDF) {
                expectedCont = 1;
            } else if (b0 >= 0xE0 && b0 <= 0xEF) {
                expectedCont = 2;
            } else if (b0 >= 0xF0 && b0 <= 0xF4) {
                expectedCont = 3;
            } else {
                return false; // Illegal start byte (0x80-0xBF, 0xC0-0xC1, 0xF5-0xFF)
            }
            i++;
            for (int j = 0; j < expectedCont; j++) {
                if (i >= b.length || (b[i] & 0xC0) != 0x80) {
                    return false;
                }
                i++;
            }
        }
        return true;
    }

    /**
     * Whether every non-ASCII byte belongs to a two-byte character of the encoding described by
     * its lead and trail byte ranges, and at least one does. A lead byte with nothing usable
     * after it means the text is not in this encoding, which is what tells the next one its turn.
     */
    private static boolean isLikelyDoubleByte(byte[] b, IntPredicate lead, IntPredicate trail) {
        int i = 0;
        int pairs = 0;
        while (i < b.length) {
            int b0 = b[i] & 0xFF;
            if (b0 <= 0x7F) {
                i++;
                continue;
            }
            if (lead.test(b0) && i + 1 < b.length) {
                int b1 = b[i + 1] & 0xFF;
                if (trail.test(b1)) {
                    pairs++;
                    i += 2;
                    continue;
                }
            }
            return false;
        }
        return pairs > 0;
    }

    private static boolean isLikelyWindows1252(byte[] b) {
        for (byte value : b) {
            int b0 = value & 0xFF;
            if (b0 >= 0x80 && b0 <= 0x9F) {
                return true;
            }
        }
        return false;
    }

    private static byte[] copy(byte[] src, int off, int len) {
        if (off < 0 || len <= 0 || off > src.length) {
            return new byte[0];
        }
        if (off + len > src.length) {
            len = src.length - off;
        }
        byte[] r = new byte[len];
        System.arraycopy(src, off, r, 0, len);
        return r;
    }

    /** Escapes SQLite LIKE wildcards so the pattern matches the literal string. */
    private static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
