/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Static container parsing tests for embedded lyrics. A file may carry several lyrics tags in
 * any order, so the reader must pick by tag priority rather than by storage order.
 */
public class LocalLyricsFetcherStaticTest {

    @Test
    public void vorbisPrefersLyricsOverUnsyncedLyrics() {
        byte[] data = flacWithComments(
                "UNSYNCEDLYRICS=plain text",
                "LYRICS=[00:01.00]synced line");
        assertEquals("[00:01.00]synced line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void vorbisPrefersSyncedLyrics() {
        byte[] data = flacWithComments(
                "LYRICS=[00:01.00]line",
                "SYNCEDLYRICS=[00:02.00]synced line");
        assertEquals("[00:02.00]synced line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void vorbisFallsBackWhenPreferredIsEmpty() {
        byte[] data = flacWithComments(
                "LYRICS=",
                "UNSYNCEDLYRICS=plain text");
        assertEquals("plain text", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void vorbisSkipsEmptyHigherPriorityTag() {
        byte[] data = flacWithComments(
                "SYNCEDLYRICS=",
                "LYRICS=[00:01.00]line");
        assertEquals("[00:01.00]line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void vorbisAcceptsSingularLyricTag() {
        byte[] data = flacWithComments(
                "UNSYNCEDLYRICS=plain text",
                "LYRIC=singular line");
        assertEquals("singular line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void vorbisTagNamesAreCaseInsensitive() {
        byte[] data = flacWithComments("lyrics=[00:01.00]lower case");
        assertEquals("[00:01.00]lower case", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void vorbisFindsCommentBlockAfterOtherBlocks() {
        byte[] data = flacWithCommentAfterPadding(
                "UNSYNCEDLYRICS=plain text",
                "LYRICS=[00:01.00]synced line");
        assertEquals("[00:01.00]synced line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void oggPrefersLyricsOverUnsyncedLyrics() {
        byte[] data = oggWithComments(
                "UNSYNCEDLYRICS=plain text",
                "LYRICS=[00:01.00]synced line");
        assertEquals("[00:01.00]synced line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void oggPrefersSyncedLyrics() {
        byte[] data = oggWithComments(
                "LYRICS=[00:01.00]line",
                "SYNCEDLYRICS=[00:02.00]synced line");
        assertEquals("[00:02.00]synced line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void oggSkipsEmptyHigherPriorityTag() {
        byte[] data = oggWithComments(
                "LYRICS=",
                "UNSYNCEDLYRICS=plain text");
        assertEquals("plain text", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void oggReadsCommentPacketAcrossPages() {
        StringBuilder lyrics = new StringBuilder("[00:01.00]");
        while (lyrics.length() < 400) {
            lyrics.append("line ");
        }
        final String expected = lyrics.toString().trim();
        byte[] data = oggCommentAcrossPages(
                "UNSYNCEDLYRICS=plain text",
                "LYRICS=" + expected);
        assertEquals(expected, LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3PrefersSyltOverUslt() {
        byte[] data = id3Tag(
                id3Frame("USLT", uslt("plain text")),
                id3Frame("SYLT", sylt("synced line", 1000)));
        String lyrics = LocalLyricsFetcher.extractLyrics(data);
        assertNotNull(lyrics);
        assertTrue(lyrics.contains("synced line"));
        assertFalse(lyrics.contains("plain text"));
    }

    @Test
    public void id3PrefersTxxxLyricsOverUslt() {
        byte[] data = id3Tag(
                id3Frame("USLT", uslt("plain text")),
                id3Frame("TXXX", txxx("LYRICS", "[00:01.00]tag line")));
        assertEquals("[00:01.00]tag line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3ReadsTxxxUnsyncedLyrics() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxx("UNSYNCEDLYRICS", "plain tag text")));
        assertEquals("plain tag text", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3IgnoresLyricistTxxx() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxx("LYRICIST", "蕭邑靜")));
        assertNull(LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3IgnoresLyricistWhenOnlyUsltExists() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxx("LYRICIST", "蕭邑靜")),
                id3Frame("USLT", uslt("plain text")));
        assertEquals("plain text", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3AcceptsLanguageSuffixedLyrics() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxx("LYRICS-zho", "[00:01.00]tag line")));
        assertEquals("[00:01.00]tag line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3ReadsUtf16LyricsDescription() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxxUtf16("LYRICS", "[00:01.00]tag line")));
        assertEquals("[00:01.00]tag line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3IgnoresUtf16LyricistDescription() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxxUtf16("LYRICIST", "蕭邑靜")));
        assertNull(LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3RejectsLyricistLikeNames() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxx("SYNCEDLYRICIST", "蕭邑靜")),
                id3Frame("COMM", comm("LYRICIST", "蕭邑靜")));
        assertNull(LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3PrefersTxxxSyncedLyrics() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxx("LYRICS", "[00:01.00]line")),
                id3Frame("TXXX", txxx("SYNCEDLYRICS", "[00:02.00]synced line")));
        assertEquals("[00:02.00]synced line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3ReadsCommLyrics() {
        byte[] data = id3Tag(
                id3Frame("COMM", comm("LYRICS", "[00:01.00]comment line")));
        assertEquals("[00:01.00]comment line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3SkipsEmptyHigherPriorityTxxx() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxx("LYRICS", "")),
                id3Frame("USLT", uslt("plain text")));
        assertEquals("plain text", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3AcceptsSingularLyricTag() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxx("LYRIC", "singular line")));
        assertEquals("singular line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3TagNamesAreCaseInsensitive() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxx("lyrics", "lower case line")));
        assertEquals("lower case line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3PrefersSyltOverTxxxLyrics() {
        byte[] data = id3Tag(
                id3Frame("TXXX", txxx("LYRICS", "[00:01.00]tag line")),
                id3Frame("SYLT", sylt("synced line", 1000)));
        String lyrics = LocalLyricsFetcher.extractLyrics(data);
        assertNotNull(lyrics);
        assertTrue(lyrics.contains("synced line"));
        assertFalse(lyrics.contains("tag line"));
    }

    @Test
    public void id3v23ReadsFramesWithPlainSizes() {
        StringBuilder text = new StringBuilder();
        while (text.length() < 200) {
            text.append("line ");
        }
        final String expected = text.toString().trim();
        byte[] data = id3v23Tag(
                id3FrameV23("TXXX", txxx("LYRICS", expected)));
        assertEquals(expected, LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3v22ReadsThreeCharFrameIds() {
        byte[] data = id3v22Tag(
                id3v22Frame("ULT", uslt("plain text")));
        assertEquals("plain text", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3SkipsExtendedHeader() {
        byte[] frame = id3Frame("TXXX", txxx("LYRICS", "[00:01.00]tag line"));
        byte[] extendedHeader = new byte[]{0, 0, 0, 6, 1, 0};
        byte[] data = id3TagWithFlags((byte) 0x40, extendedHeader, frame);
        assertEquals("[00:01.00]tag line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void id3DoesNotReadFramesPastTagEnd() {
        byte[] inside = id3Frame("TXXX", txxx("LYRICS", "inside"));
        // A 12-byte extended header is long enough to make an incorrect tag end
        // overlap the frame that follows the tag.
        byte[] extendedHeader = new byte[]{0, 0, 0, 12, 1, 0, 0, 0, 0, 0, 0, 0};
        byte[] tag = id3TagWithFlags((byte) 0x40, extendedHeader, inside);
        byte[] afterTag = id3Frame("TXXX", txxx("SYNCEDLYRICS", "outside"));
        assertEquals("inside", LocalLyricsFetcher.extractLyrics(concat(tag, afterTag)));
    }

    @Test
    public void m4aPrefersCopyrightLyrOverLowercase() {
        byte[] data = m4a(
                m4aItem(new byte[]{'l', 'y', 'r', ' '}, "lower line"),
                m4aItem(new byte[]{(byte) 0xA9, 'l', 'y', 'r'}, "copyright line"));
        assertEquals("copyright line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void m4aPrefersLowercaseLyrOverUppercase() {
        byte[] data = m4a(
                m4aItem(new byte[]{'L', 'Y', 'R', ' '}, "uppercase line"),
                m4aItem(new byte[]{'l', 'y', 'r', ' '}, "lower line"));
        assertEquals("lower line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void m4aFallsBackWhenCopyrightLyrIsEmpty() {
        byte[] data = m4a(
                m4aItem(new byte[]{(byte) 0xA9, 'l', 'y', 'r'}, ""),
                m4aItem(new byte[]{'l', 'y', 'r', ' '}, "lower line"));
        assertEquals("lower line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void m4aReadsUppercaseLyr() {
        byte[] data = m4a(
                m4aItem(new byte[]{'L', 'Y', 'R', ' '}, "uppercase line"));
        assertEquals("uppercase line", LocalLyricsFetcher.extractLyrics(data));
    }

    @Test
    public void emptyDataReturnsNull() {
        assertNull(LocalLyricsFetcher.extractLyrics(new byte[0]));
    }

    @Test
    public void unknownContainerReturnsNull() {
        assertNull(LocalLyricsFetcher.extractLyrics(
                "not a media file".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] flacWithComments(String... comments) {
        byte[] body = vorbisCommentBody(comments);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('f');
        out.write('L');
        out.write('a');
        out.write('C');
        out.write(0x84); // last metadata block, type 4 (Vorbis comment)
        writeU24BE(out, body.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static byte[] flacWithCommentAfterPadding(String... comments) {
        byte[] body = vorbisCommentBody(comments);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('f');
        out.write('L');
        out.write('a');
        out.write('C');
        // STREAMINFO (type 0, not last).
        out.write(0x00);
        writeU24BE(out, 34);
        out.write(new byte[34], 0, 34);
        // PADDING (type 1, not last).
        out.write(0x01);
        writeU24BE(out, 8);
        out.write(new byte[8], 0, 8);
        // VORBIS_COMMENT (type 4, last).
        out.write(0x84);
        writeU24BE(out, body.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static byte[] vorbisCommentBody(String... comments) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeIntLE(body, 0); // empty vendor string
        writeIntLE(body, comments.length);
        for (String comment : comments) {
            byte[] bytes = comment.getBytes(StandardCharsets.UTF_8);
            writeIntLE(body, bytes.length);
            body.write(bytes, 0, bytes.length);
        }
        return body.toByteArray();
    }

    private static byte[] oggWithComments(String... comments) {
        return oggPage(0, vorbisCommentPacket(comments), true);
    }

    private static byte[] oggCommentAcrossPages(String... comments) {
        byte[] packet = vorbisCommentPacket(comments);
        if (packet.length <= 255) {
            throw new IllegalArgumentException("packet must span more than one page");
        }
        byte[] first = Arrays.copyOfRange(packet, 0, 255);
        byte[] second = Arrays.copyOfRange(packet, 255, packet.length);
        return concat(oggPage(0, first, false), oggPage(1, second, true));
    }

    private static byte[] vorbisCommentPacket(String... comments) {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(3);
        byte[] vorbis = "vorbis".getBytes(StandardCharsets.ISO_8859_1);
        packet.write(vorbis, 0, vorbis.length);
        byte[] body = vorbisCommentBody(comments);
        packet.write(body, 0, body.length);
        return packet.toByteArray();
    }

    /**
     * Builds one Ogg page. When {@code packetEnds} is false the payload must end on a 255
     * boundary; the last lacing value stays 255 to mark the packet as continued.
     */
    private static byte[] oggPage(int sequence, byte[] payload, boolean packetEnds) {
        List<Integer> lacing = new ArrayList<>();
        int remaining = payload.length;
        while (remaining >= 255) {
            lacing.add(255);
            remaining -= 255;
        }
        if (packetEnds) {
            lacing.add(remaining);
        } else if (remaining != 0) {
            throw new IllegalArgumentException("continued packet must end on a 255 boundary");
        }

        byte[] out = new byte[27 + lacing.size() + payload.length];
        out[0] = 'O';
        out[1] = 'g';
        out[2] = 'g';
        out[3] = 'S';
        out[18] = (byte) (sequence & 0xFF);
        out[19] = (byte) ((sequence >>> 8) & 0xFF);
        out[20] = (byte) ((sequence >>> 16) & 0xFF);
        out[21] = (byte) ((sequence >>> 24) & 0xFF);
        out[26] = (byte) lacing.size();
        int off = 27;
        for (int value : lacing) {
            out[off++] = (byte) value;
        }
        System.arraycopy(payload, 0, out, off, payload.length);
        return out;
    }

    private static byte[] id3Tag(byte[]... frames) {
        return id3Tag(4, (byte) 0, new byte[0], frames);
    }

    private static byte[] id3v23Tag(byte[]... frames) {
        return id3Tag(3, (byte) 0, new byte[0], frames);
    }

    private static byte[] id3v22Tag(byte[]... frames) {
        return id3Tag(2, (byte) 0, new byte[0], frames);
    }

    private static byte[] id3TagWithFlags(byte flags, byte[] extendedHeader, byte[]... frames) {
        return id3Tag(4, flags, extendedHeader, frames);
    }

    private static byte[] id3Tag(int major, byte flags, byte[] extendedHeader, byte[]... frames) {
        final int framesLength = extendedHeader.length + totalLength(frames);
        byte[] out = new byte[10 + framesLength];
        out[0] = 'I';
        out[1] = 'D';
        out[2] = '3';
        out[3] = (byte) major;
        out[4] = 0; // revision
        out[5] = flags;
        writeSyncsafe(out, 6, framesLength);
        System.arraycopy(extendedHeader, 0, out, 10, extendedHeader.length);
        int off = 10 + extendedHeader.length;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 0, out, off, frame.length);
            off += frame.length;
        }
        return out;
    }

    private static byte[] id3Frame(String id, byte[] body) {
        byte[] out = new byte[10 + body.length];
        byte[] idBytes = id.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(idBytes, 0, out, 0, 4);
        writeSyncsafe(out, 4, body.length);
        System.arraycopy(body, 0, out, 10, body.length);
        return out;
    }

    private static byte[] id3FrameV23(String id, byte[] body) {
        byte[] out = new byte[10 + body.length];
        byte[] idBytes = id.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(idBytes, 0, out, 0, 4);
        writeIntBE(out, 4, body.length); // plain big-endian, not syncsafe
        System.arraycopy(body, 0, out, 10, body.length);
        return out;
    }

    private static byte[] id3v22Frame(String id, byte[] body) {
        byte[] out = new byte[6 + body.length];
        byte[] idBytes = id.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(idBytes, 0, out, 0, 3);
        out[3] = (byte) ((body.length >>> 16) & 0xFF);
        out[4] = (byte) ((body.length >>> 8) & 0xFF);
        out[5] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, out, 6, body.length);
        return out;
    }

    private static byte[] txxx(String description, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(3); // UTF-8
        byte[] desc = description.getBytes(StandardCharsets.UTF_8);
        out.write(desc, 0, desc.length);
        out.write(0);
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        out.write(text, 0, text.length);
        return out.toByteArray();
    }

    private static byte[] txxxUtf16(String description, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1); // UTF-16 with BOM
        writeUtf16Le(out, description);
        out.write(0);
        out.write(0); // descriptor terminator
        writeUtf16Le(out, value);
        return out.toByteArray();
    }

    private static void writeUtf16Le(ByteArrayOutputStream out, String text) {
        out.write(0xFF);
        out.write(0xFE);
        for (char c : text.toCharArray()) {
            out.write(c & 0xFF);
            out.write((c >>> 8) & 0xFF);
        }
    }

    private static byte[] uslt(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(3); // UTF-8
        out.write('e');
        out.write('n');
        out.write('g');
        out.write(0); // empty descriptor
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }

    private static byte[] comm(String description, String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(3); // UTF-8
        out.write('e');
        out.write('n');
        out.write('g');
        byte[] desc = description.getBytes(StandardCharsets.UTF_8);
        out.write(desc, 0, desc.length);
        out.write(0); // descriptor terminator
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }

    private static byte[] sylt(String text, int timestampMs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(3); // UTF-8
        out.write('e');
        out.write('n');
        out.write('g');
        out.write(2); // timestamp format: milliseconds
        out.write(1); // content type: lyrics
        out.write(0); // empty descriptor
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        out.write(0); // segment terminator
        writeIntBE(out, timestampMs);
        return out.toByteArray();
    }

    private static byte[] m4a(byte[]... items) {
        byte[] ilst = box("ilst".getBytes(StandardCharsets.ISO_8859_1), concat(items));
        byte[] meta = box("meta".getBytes(StandardCharsets.ISO_8859_1), new byte[4], ilst);
        byte[] udta = box("udta".getBytes(StandardCharsets.ISO_8859_1), meta);
        byte[] moov = box("moov".getBytes(StandardCharsets.ISO_8859_1), udta);
        byte[] ftyp = box("ftyp".getBytes(StandardCharsets.ISO_8859_1));
        return concat(ftyp, moov);
    }

    private static byte[] m4aItem(byte[] type, String text) {
        byte[] data = box("data".getBytes(StandardCharsets.ISO_8859_1),
                new byte[8], text.getBytes(StandardCharsets.UTF_8));
        return box(type, data);
    }

    private static byte[] box(byte[] type, byte[]... payloads) {
        int size = 8;
        for (byte[] payload : payloads) {
            size += payload.length;
        }
        byte[] out = new byte[size];
        writeIntBE(out, 0, size);
        System.arraycopy(type, 0, out, 4, 4);
        int off = 8;
        for (byte[] payload : payloads) {
            System.arraycopy(payload, 0, out, off, payload.length);
            off += payload.length;
        }
        return out;
    }

    private static byte[] concat(byte[]... arrays) {
        byte[] out = new byte[totalLength(arrays)];
        int off = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, out, off, array.length);
            off += array.length;
        }
        return out;
    }

    private static int totalLength(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        return length;
    }

    private static void writeU24BE(ByteArrayOutputStream out, int value) {
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeSyncsafe(byte[] out, int off, int value) {
        out[off] = (byte) ((value >>> 21) & 0x7F);
        out[off + 1] = (byte) ((value >>> 14) & 0x7F);
        out[off + 2] = (byte) ((value >>> 7) & 0x7F);
        out[off + 3] = (byte) (value & 0x7F);
    }

    private static void writeIntBE(byte[] out, int off, int value) {
        out[off] = (byte) ((value >>> 24) & 0xFF);
        out[off + 1] = (byte) ((value >>> 16) & 0xFF);
        out[off + 2] = (byte) ((value >>> 8) & 0xFF);
        out[off + 3] = (byte) (value & 0xFF);
    }

    private static void writeIntBE(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
