/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.provider.MediaStore;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


import app.morphe.extension.music.patches.lyrics.LrcParser;
import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.shared.Utils;

public final class LocalLyricsFetcher {

    private LocalLyricsFetcher() {
    }

    /**
     * Value of {@code MediaMetadataRetriever.METADATA_KEY_LYRICS}. Kept as a literal because the
     * music module's compile SDK does not expose the constant symbol. The key is only honoured by
     * the framework on API 30+, so it is used only as a best-effort fallback after direct parsing.
     */
    private static final int METADATA_KEY_LYRICS = 0x104;

    private static final int MAX_TAG_BYTES = 16 * 1024 * 1024;

    private static final int M4A_TAIL_BYTES = 4 * 1024 * 1024;

    @Nullable
    public static Lyrics fetch(@Nullable Uri mediaUri) {
        if (mediaUri == null) {
            return null;
        }

        final Context context = Utils.getContext();
        if (context == null) {
            return null;
        }

        final byte[] header = readBytes(context, mediaUri, 10);
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
            final String raw = extractLyrics(data);
            final Lyrics parsed = parse(raw);
            if (parsed != null) {
                return parsed;
            }
            if (isLikelyM4a(data)) {
                final byte[] tail = readBytesTail(context, mediaUri, M4A_TAIL_BYTES);
                if (tail != null) {
                    final String tailRaw = extractLyrics(tail);
                    final Lyrics tailParsed = parse(tailRaw);
                    if (tailParsed != null) {
                        return tailParsed;
                    }
                }
            }
        } else {
        }

        final Lyrics fallback = fallbackViaMediaMetadataRetriever(context, mediaUri);
        if (fallback != null) {
        } else {
        }
        return fallback;
    }

    @Nullable
    public static Uri resolveMediaStoreUri(String title, String artist, int durationSeconds) {
        return resolveMediaStoreUri(title, artist, durationSeconds, null, null);
    }

    @Nullable
    public static Uri resolveMediaStoreUri(String title, String artist, int durationSeconds,
                                           @Nullable String rawTitle, @Nullable String rawArtist) {
        final String primaryTitle = (title != null && !title.isBlank()) ? title : rawTitle;
        final String primaryArtist = (artist != null && !artist.isBlank()) ? artist : rawArtist;
        if (primaryTitle == null || primaryTitle.isBlank()) {
            return null;
        }
        final Context context = Utils.getContext();
        if (context == null) {
            return null;
        }
        final ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return null;
        }

        final Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        final String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
        };

        // Most specific first; title-only variants widen the net when the artist was normalised.
        final List<String[]> candidates = new ArrayList<>(4);
        candidates.add(new String[]{ title, artist });
        if (rawTitle != null && !rawTitle.equals(title)) {
            candidates.add(new String[]{ rawTitle, rawArtist });
        }
        candidates.add(new String[]{ title, null });
        if (rawTitle != null && !rawTitle.equals(title)) {
            candidates.add(new String[]{ rawTitle, null });
        }

        final String wantArtist = normalize(primaryArtist);

        Uri best = null;
        long bestScore = Long.MAX_VALUE;
        try {
            for (String[] pair : candidates) {
                final String t = pair[0];
                final String a = pair[1];
                if (t == null || t.isBlank()) {
                    continue;
                }
                final String selection = MediaStore.Audio.Media.TITLE + " LIKE ? ESCAPE '\\'";
                Cursor cursor = resolver.query(base, projection, selection,
                        new String[]{ likeEscape(t) }, null);
                if (cursor != null && cursor.getCount() == 0) {
                    cursor.close();
                    cursor = resolver.query(base, projection, selection,
                            new String[]{ "%" + likeEscape(t) + "%" }, null);
                }
                if (cursor == null) {
                    continue;
                }
                try {
                    final int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                    final int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    final int durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                    if (idCol < 0) {
                        continue;
                    }
                    while (cursor.moveToNext()) {
                        final long id = cursor.getLong(idCol);
                        final String storedArtist = artistCol >= 0 ? cursor.getString(artistCol) : null;
                        final long durMs = (durCol >= 0) ? cursor.getLong(durCol) : 0;

                        long score = 0;
                        final String normStored = normalize(storedArtist);
                        if (wantArtist != null && !wantArtist.isEmpty()) {
                            if (normStored == null || normStored.isEmpty()) {
                                score += 1_000_000L;
                            } else if (normStored.contains(wantArtist) || wantArtist.contains(normStored)) {
                                score += 0;
                            } else {
                                score += 500_000L;
                            }
                        }
                        if (durationSeconds > 0 && durMs > 0) {
                            score += Math.abs(durMs - (long) durationSeconds * 1000L);
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
            return null;
        }
        return best;
    }

    @Nullable
    private static byte[] readBytes(Context context, Uri uri, int limit) {
        final ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return null;
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 1 << 16));
            final byte[] buf = new byte[1 << 16];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (total + n > limit) {
                    final int remaining = limit - total;
                    if (remaining > 0) {
                        out.write(buf, 0, remaining);
                        total += remaining;
                    }
                    break;
                }
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        } catch (IOException | SecurityException | NullPointerException ex) {
            return null;
        }
    }

    @Nullable
    private static byte[] readBytesTail(Context context, Uri uri, int tailBytes) {
        final ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return null;
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            final byte[] ring = new byte[tailBytes];
            int ringPos = 0;
            int ringLen = 0;
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                int remaining = n;
                int bufOff = 0;
                while (remaining > 0) {
                    final int space = tailBytes - ringLen;
                    if (space >= remaining) {
                        System.arraycopy(buf, bufOff, ring, ringPos, remaining);
                        ringPos = (ringPos + remaining) % tailBytes;
                        ringLen += remaining;
                        bufOff += remaining;
                        remaining = 0;
                    } else {
                        System.arraycopy(buf, bufOff, ring, ringPos, space);
                        ringPos = (ringPos + space) % tailBytes;
                        ringLen = tailBytes;
                        bufOff += space;
                        remaining -= space;
                    }
                }
            }
            if (ringLen == 0) {
                return null;
            }
            if (ringLen < tailBytes) {
                final byte[] result = new byte[ringLen];
                System.arraycopy(ring, 0, result, 0, ringLen);
                return result;
            }
            final byte[] result = new byte[tailBytes];
            System.arraycopy(ring, ringPos, result, 0, tailBytes - ringPos);
            System.arraycopy(ring, 0, result, tailBytes - ringPos, ringPos);
            return result;
        } catch (IOException | SecurityException | NullPointerException ex) {
            return null;
        }
    }

    private static boolean isLikelyM4a(byte[] d) {
        return d.length >= 8
                && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p';
    }

    @Nullable
    private static String extractLyrics(byte[] d) {
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            final String s = parseId3v2(d);
            if (s != null) {
                return s;
            }
        }
        if (d.length >= 4 && d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C') {
            final String s = parseFlac(d);
            if (s != null) {
                return s;
            }
        }
        if (d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') {
            final String s = parseOgg(d);
            if (s != null) {
                return s;
            }
        }
        if (d.length >= 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            final String s = parseM4a(d);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    @Nullable
    private static String parseId3v2(byte[] d) {
        if (d.length < 10) {
            return null;
        }
        final int major = d[3] & 0xFF;
        final int tagSize = syncsafe(d[6], d[7], d[8], d[9]);
        int pos = 10;
        if ((major == 3 || major == 4) && (d[5] & 0x40) != 0) {
            final int extSize = readInt32BE(d, 10);
            pos = 10 + extSize;
        }
        final int end = Math.min(pos + tagSize, d.length);
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
        while (pos + headerLen <= end) {
            final String id = new String(d, pos, idLen, StandardCharsets.ISO_8859_1);
            final int size = (headerLen == 6)
                    ? (((d[pos + 3] & 0xFF) << 16) | ((d[pos + 4] & 0xFF) << 8) | (d[pos + 5] & 0xFF))
                    : (syncsafeSize ? syncsafe(d[pos + 4], d[pos + 5], d[pos + 6], d[pos + 7])
                                    : readInt32BE(d, pos + 4));
            final int bodyOff = pos + headerLen;
            if (size < 0 || bodyOff + size > d.length) {
                break;
            }
            if (id.equals("USLT") || id.equals("ULT")) {
                final String s = parseTextFrame(d, bodyOff, size);
                if (s != null && !s.isBlank()) {
                    return s;
                }
            } else if (id.equals("SYLT") || id.equals("SLT")) {
                final String s = parseSylt(d, bodyOff, size);
                if (s != null && !s.isBlank()) {
                    return s;
                }
            } else if (id.equals("TXXX")) {
                final String desc = parseTxxxDescription(d, bodyOff, size);
                if (desc != null && desc.length() >= 5
                        && (desc.regionMatches(true, 0, "LYRICS", 0, 6)
                         || desc.regionMatches(true, 0, "LYRIC", 0, 5))) {
                    final String s = parseTxxxValue(d, bodyOff, size);
                    if (s != null && !s.isBlank()) {
                        return s;
                    }
                }
            } else if (id.equals("COMM")) {
                final String desc = parseCommDescription(d, bodyOff, size);
                if (desc != null && desc.length() >= 5
                        && (desc.regionMatches(true, 0, "LYRICS", 0, 6)
                         || desc.regionMatches(true, 0, "LYRIC", 0, 5))) {
                    final String s = parseCommBody(d, bodyOff, size);
                    if (s != null && !s.isBlank()) {
                        return s;
                    }
                }
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
        return null;
    }

    @Nullable
    private static String parseTxxxDescription(byte[] d, int off, int len) {
        if (len <= 1) return null;
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;
        final int descEnd = indexOfNullTerm(d, off + 1, frameEnd, enc);
        if (descEnd < 0 || descEnd >= frameEnd) return null;
        return decodeAutoDetect(copy(d, off + 1, descEnd - off - 1), enc).trim();
    }

    @Nullable
    private static String parseTxxxValue(byte[] d, int off, int len) {
        if (len <= 1) return null;
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
        if (len <= 4) return null;
        final int enc = d[off] & 0xFF;
        final int frameEnd = off + len;
        final int descStart = off + 1 + 3;
        final int descEnd = indexOfNullTerm(d, descStart, frameEnd, enc);
        if (descEnd < 0 || descEnd >= frameEnd) return null;
        return decodeAutoDetect(copy(d, descStart, descEnd - descStart), enc).trim();
    }

    @Nullable
    private static String parseCommBody(byte[] d, int off, int len) {
        if (len <= 4) return null;
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
        if (len <= 0) {
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
        if (len <= 0) {
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
        final StringBuilder sb = new StringBuilder();
        int q = textStart;
        while (q < frameEnd) {
            final int tEnd = indexOfNullTerm(d, q, frameEnd, enc);
            final int segEnd = (tEnd < 0) ? frameEnd : tEnd;
            final int segLen = segEnd - q;
            if (segLen <= 0) {
                break;
            }
            final String seg = decodeAutoDetect(copy(d, q, segLen), enc).trim();
            long tsMs = -1;
            if (tEnd >= 0 && tEnd + tsBytes <= frameEnd) {
                tsMs = (tsBytes == 4)
                        ? readInt32BE(d, tEnd) & 0xFFFFFFFFL
                        : ((d[tEnd] & 0xFF) << 8) | (d[tEnd + 1] & 0xFF);
            }
            if (!seg.isEmpty()) {
                if (tsMs >= 0) {
                    final long min = tsMs / 60_000;
                    final long sec = (tsMs / 1000) % 60;
                    final long cs = (tsMs % 1000) / 10;
                    sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]", min, sec, cs));
                }
                sb.append(seg).append('\n');
            }
            if (tEnd < 0) {
                break;
            }
            q = tEnd + term + tsBytes;
        }
        final String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    @Nullable
    private static String parseFlac(byte[] d) {
        if (d.length < 4 || !(d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C')) {
            return null;
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
                final String s = parseVorbisComment(d, body, size);
                if (s != null) {
                    return s;
                }
            }
            pos = body + size;
            if (last) {
                break;
            }
        }
        return null;
    }

    @Nullable
    private static String parseVorbisComment(byte[] d, int start, int size) {
        final int end = start + size;
        int p = start;
        if (p + 4 > end) {
            return null;
        }
        final int vendorLen = readInt32LE(d, p);
        p += 4 + vendorLen;
        if (p + 4 > end) {
            return null;
        }
        final int count = readInt32LE(d, p);
        p += 4;
        for (int i = 0; i < count && p + 4 <= end; i++) {
            final int len = readInt32LE(d, p);
            p += 4;
            if (p + len > end) {
                break;
            }
            final String comment = new String(d, p, len, StandardCharsets.UTF_8);
            p += len;
            final String key = comment.contains("=") ? comment.substring(0, comment.indexOf('=')) : comment;
            if (comment.regionMatches(true, 0, "LYRICS=", 0, 7)) {
                return comment.substring(7);
            } else if (comment.regionMatches(true, 0, "LYRIC=", 0, 6)) {
                return comment.substring(6);
            } else if (comment.regionMatches(true, 0, "SYNCEDLYRICS=", 0, 13)) {
                return comment.substring(13);
            } else if (comment.regionMatches(true, 0, "UNSYNCEDLYRICS=", 0, 15)) {
                return comment.substring(15);
            }
        }
        return null;
    }

    @Nullable
    private static String parseOgg(byte[] d) {
        int pos = 0;
        final StringBuilder packet = new StringBuilder();
        int segmentsLeft = 0;
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
                    return null;
                }
                packet.append(new String(d, dataPos, segLen, StandardCharsets.ISO_8859_1));
                dataPos += segLen;
            }
            if (numSegments > 0 && (d[pos + 27 + numSegments - 1] & 0xFF) == 255) {
                segmentsLeft++;
            } else {
                if (segmentsLeft > 0) {
                    segmentsLeft--;
                } else {
                    final String payload = packet.toString();
                    final int marker = payload.indexOf("\003vorbis");
                    if (marker >= 0) {
                        final String s = parseVorbisCommentFromPacket(payload, marker + 7);
                        if (s != null) {
                            return s;
                        }
                    }
                    packet.setLength(0);
                }
            }
            pos = dataPos;
        }
        return null;
    }

    @Nullable
    private static String parseVorbisCommentFromPacket(String payload, int off) {
        final int len = payload.length();
        if (off + 4 > len) {
            return null;
        }
        int p = off;
        final int vendorLen = ((payload.charAt(p) & 0xFF))
                | ((payload.charAt(p + 1) & 0xFF) << 8)
                | ((payload.charAt(p + 2) & 0xFF) << 16)
                | ((payload.charAt(p + 3) & 0xFF) << 24);
        p += 4 + vendorLen;
        if (p + 4 > len) {
            return null;
        }
        final int count = ((payload.charAt(p) & 0xFF))
                | ((payload.charAt(p + 1) & 0xFF) << 8)
                | ((payload.charAt(p + 2) & 0xFF) << 16)
                | ((payload.charAt(p + 3) & 0xFF) << 24);
        p += 4;
        for (int i = 0; i < count && p + 4 <= len; i++) {
            final int entryLen = ((payload.charAt(p) & 0xFF))
                    | ((payload.charAt(p + 1) & 0xFF) << 8)
                    | ((payload.charAt(p + 2) & 0xFF) << 16)
                    | ((payload.charAt(p + 3) & 0xFF) << 24);
            p += 4;
            if (p + entryLen > len) {
                break;
            }
            final String comment = payload.substring(p, p + entryLen);
            p += entryLen;
            final String key = comment.contains("=") ? comment.substring(0, comment.indexOf('=')) : comment;
            if (comment.regionMatches(true, 0, "LYRICS=", 0, 7)) {
                return decodeAutoDetect(comment.substring(7).getBytes(StandardCharsets.ISO_8859_1), 3);
            } else if (comment.regionMatches(true, 0, "LYRIC=", 0, 6)) {
                return decodeAutoDetect(comment.substring(6).getBytes(StandardCharsets.ISO_8859_1), 3);
            } else if (comment.regionMatches(true, 0, "SYNCEDLYRICS=", 0, 13)) {
                return decodeAutoDetect(comment.substring(13).getBytes(StandardCharsets.ISO_8859_1), 3);
            } else if (comment.regionMatches(true, 0, "UNSYNCEDLYRICS=", 0, 15)) {
                return decodeAutoDetect(comment.substring(15).getBytes(StandardCharsets.ISO_8859_1), 3);
            }
        }
        return null;
    }

    @Nullable
    private static String parseM4a(byte[] d) {
        return walkM4a(d, 0, d.length);
    }

    @Nullable
    private static String walkM4a(byte[] d, int start, int end) {
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
            final boolean isLyrics = (c0 == 0xA9 && c1 == 'l' && c2 == 'y' && c3 == 'r')
                    || (c0 == 'l' && c1 == 'y' && c2 == 'r' && c3 == ' ')
                    || (c0 == 'L' && c1 == 'Y' && c2 == 'R' && c3 == ' ');
            if (isLyrics) {
                final String s = readM4aData(d, pos + 8, pos + size);
                if (s != null && !s.isBlank()) {
                    return s;
                }
            } else {
                final String type = new String(d, pos + 4, 4, StandardCharsets.ISO_8859_1);
                if (type.equals("meta") || type.equals("ilst") || type.equals("udta")
                        || type.equals("moov") || type.equals("trak") || type.equals("mdia")
                        || type.equals("minf") || type.equals("stbl")) {
                    int child = pos + 8;
                    if (type.equals("meta")) {
                        child = pos + 12;
                    }
                    final String s = walkM4a(d, child, pos + size);
                    if (s != null) {
                        return s;
                    }
                }
            }
            pos += size;
        }
        return null;
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
                final byte[] content = copy(d, contentStart, contentEnd - contentStart);
                final int dataType = readInt32BE(d, p + 12);
                final String s = decodeAutoDetect(content, 3);
                final String trimmed = s.replace("\uFEFF", "").replace("\0", " ").trim();
                return trimmed.isEmpty() ? null : trimmed;
            }
            p += size;
        }
        return null;
    }

    @Nullable
    private static Lyrics fallbackViaMediaMetadataRetriever(Context context, Uri uri) {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            final String raw = retriever.extractMetadata(METADATA_KEY_LYRICS);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return parse(raw);
        } catch (Exception ex) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    @Nullable
    private static Lyrics parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        final LrcParser.LrcParseResult result = LrcParser.parseSyncedWithCreditLines(raw);
        if (!result.lines.isEmpty()) {
            boolean synced = false;
            for (LyricsLine line : result.lines) {
                if (line.hasWords()) {
                    synced = true;
                    break;
                }
            }
            return new Lyrics(result.lines, "Local", synced, null, null, null,
                    result.creditLines.isEmpty() ? null : result.creditLines,
                    raw, "lrc", null);
        }

        final List<LyricsLine> plain = LrcParser.parsePlain(raw);
        if (plain != null && !plain.isEmpty()) {
            return new Lyrics(plain, "Local", false, null, null, null, null, raw, "lrc", null);
        }

        return null;
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
                if ((d[i] & 0xFF) == 0 && (d[i + 1] & 0xFF) == 0) {
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
        if (isLikelyGbk(b)) {
            try {
                return new String(b, "GBK");
            } catch (Exception ignored) { }
        }
        if (isLikelyBig5(b)) {
            try {
                return new String(b, "Big5");
            } catch (Exception ignored) { }
        }
        if (isLikelyShiftJis(b)) {
            try {
                return new String(b, "Shift_JIS");
            } catch (Exception ignored) { }
        }
        if (isLikelyEucKr(b)) {
            try {
                return new String(b, "EUC-KR");
            } catch (Exception ignored) { }
        }
        if (isLikelyWindows1252(b)) {
            try {
                return new String(b, "Windows-1252");
            } catch (Exception ignored) { }
        }
        switch (declaredEnc) {
            case 1:
                return new String(b, StandardCharsets.UTF_16);
            case 2:
                return new String(b, StandardCharsets.UTF_16BE);
            case 3:
                return new String(b, StandardCharsets.UTF_8);
            default:
                return new String(b, StandardCharsets.ISO_8859_1);
        }
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

    private static boolean isLikelyGbk(byte[] b) {
        int i = 0;
        int pairs = 0;
        while (i < b.length) {
            int b0 = b[i] & 0xFF;
            if (b0 <= 0x7F) {
                i++;
                continue;
            }
            if (b0 >= 0x81 && b0 <= 0xFE && i + 1 < b.length) {
                int b1 = b[i + 1] & 0xFF;
                if (b1 >= 0x40 && b1 <= 0xFE && b1 != 0x7F) {
                    pairs++;
                    i += 2;
                    continue;
                }
            }
            return false;
        }
        return pairs > 0;
    }

    private static boolean isLikelyBig5(byte[] b) {
        int i = 0;
        int pairs = 0;
        while (i < b.length) {
            int b0 = b[i] & 0xFF;
            if (b0 <= 0x7F) {
                i++;
                continue;
            }
            if (b0 >= 0x81 && b0 <= 0xFE && i + 1 < b.length) {
                int b1 = b[i + 1] & 0xFF;
                if ((b1 >= 0x40 && b1 <= 0x7E) || (b1 >= 0xA1 && b1 <= 0xFE)) {
                    pairs++;
                    i += 2;
                    continue;
                }
            }
            return false;
        }
        return pairs > 0;
    }

    private static boolean isLikelyShiftJis(byte[] b) {
        int i = 0;
        int pairs = 0;
        while (i < b.length) {
            int b0 = b[i] & 0xFF;
            if (b0 <= 0x7F) {
                i++;
                continue;
            }
            if (((b0 >= 0x81 && b0 <= 0x9F) || (b0 >= 0xE0 && b0 <= 0xEF)) && i + 1 < b.length) {
                int b1 = b[i + 1] & 0xFF;
                if ((b1 >= 0x40 && b1 <= 0x7E) || (b1 >= 0x80 && b1 <= 0xFC)) {
                    pairs++;
                    i += 2;
                    continue;
                }
            }
            return false;
        }
        return pairs > 0;
    }

    private static boolean isLikelyEucKr(byte[] b) {
        int i = 0;
        int pairs = 0;
        while (i < b.length) {
            int b0 = b[i] & 0xFF;
            if (b0 <= 0x7F) {
                i++;
                continue;
            }
            if (b0 >= 0x81 && b0 <= 0xFE && i + 1 < b.length) {
                int b1 = b[i + 1] & 0xFF;
                if (b1 >= 0x41 && b1 <= 0xFE) {
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
        final byte[] r = new byte[len];
        System.arraycopy(src, off, r, 0, len);
        return r;
    }

    private static String normalize(@Nullable String s) {
        if (s == null) {
            return "";
        }
        return CharactersConverter.normalize(s);
    }

    /** Escapes SQLite LIKE wildcards so the pattern matches the literal string. */
    private static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
