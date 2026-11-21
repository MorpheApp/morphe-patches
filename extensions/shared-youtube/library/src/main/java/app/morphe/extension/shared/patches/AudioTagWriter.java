/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5048
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * Writes an exported audio stream with the title, artist, album and cover of the song.
 * The streams saved by the app have none of these tags.
 * <p>
 * AAC streams get MP4 tags (moov/udta/meta/ilst), which the media scanner and music players read.
 * Opus streams are saved in WebM files, and players don't read covers of WebM files,
 * so the Opus packets are written to an Ogg Opus file with Vorbis comments instead.
 * The audio itself is not changed.
 */
final class AudioTagWriter {

    /**
     * @param data JPEG or PNG image.
     */
    record Artwork(byte[] data, String mimeType, int width, int height) {
    }

    record Tags(String title, @Nullable String artist, @Nullable String album, @Nullable Artwork artwork) {
    }

    interface ProgressListener {
        /**
         * @throws IOException If the export is canceled.
         */
        void onProgress() throws IOException;
    }

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private AudioTagWriter() {
    }

    // region MP4

    private static final int MP4_HEADER_SIZE = 8;
    private static final int MP4_LARGE_HEADER_SIZE = 16;
    /**
     * Data types of MP4 tag values.
     */
    private static final int MP4_DATA_TYPE_UTF8 = 1;
    private static final int MP4_DATA_TYPE_JPEG = 13;
    private static final int MP4_DATA_TYPE_PNG = 14;
    /**
     * The movie box of a saved stream is a few kilobytes.
     */
    private static final long MP4_MAX_MOVIE_BOX_SIZE = 1024 * 1024;

    private static final byte COPYRIGHT_SIGN = (byte) 0xA9;

    /**
     * Copies a saved MP4 audio stream and adds the tags to its movie box.
     * <p>
     * The saved streams are fragmented: the movie box comes before the media and
     * the samples are found relative to their fragments, so a larger movie box moves no offsets.
     * Other files are copied without tags.
     */
    static void writeMp4(MediaDataSource source, OutputStream output, Tags tags,
                         ProgressListener progress) throws IOException {
        final long size = source.getSize();
        byte[] header = new byte[MP4_LARGE_HEADER_SIZE];
        long offset = 0;

        while (offset + MP4_HEADER_SIZE <= size) {
            readFully(source, offset, header, MP4_HEADER_SIZE);
            long boxSize = readUInt32(header, 0);
            String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
            if (boxSize == 1) {
                readFully(source, offset, header, MP4_LARGE_HEADER_SIZE);
                boxSize = ByteBuffer.wrap(header, MP4_HEADER_SIZE, 8).getLong();
            } else if (boxSize == 0) {
                boxSize = size - offset;
            }
            if (boxSize < MP4_HEADER_SIZE || offset + boxSize > size) {
                break;
            }

            if (type.equals("moov")) {
                if (boxSize > MP4_MAX_MOVIE_BOX_SIZE) {
                    break;
                }
                byte[] movie = new byte[(int) boxSize];
                readFully(source, offset, movie, movie.length);
                byte[] taggedMovie = addMp4Tags(movie, tags);
                if (taggedMovie == null) {
                    break;
                }

                copy(source, 0, offset, output, progress);
                output.write(taggedMovie);
                copy(source, offset + boxSize, size, output, progress);
                return;
            }
            if (type.equals("moof") || type.equals("mdat")) {
                break;
            }
            offset += boxSize;
        }

        Logger.printInfo(() -> "MP4 stream is not fragmented, it is exported without tags");
        copy(source, 0, size, output, progress);
    }

    /**
     * @param movie The movie box, with a 32-bit size.
     * @return The movie box with the tags, or null if the stream is not fragmented.
     */
    @Nullable
    private static byte[] addMp4Tags(byte[] movie, Tags tags) throws IOException {
        ByteArrayOutputStream children = new ByteArrayOutputStream(movie.length);
        boolean fragmented = false;

        int offset = MP4_HEADER_SIZE;
        while (offset + MP4_HEADER_SIZE <= movie.length) {
            final int boxSize = (int) readUInt32(movie, offset);
            if (boxSize < MP4_HEADER_SIZE || offset + boxSize > movie.length) {
                return null;
            }
            String type = new String(movie, offset + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("mvex")) {
                fragmented = true;
            }
            // The existing user data only holds the name of the encoder, and is replaced.
            if (!type.equals("udta")) {
                children.write(movie, offset, boxSize);
            }
            offset += boxSize;
        }
        if (!fragmented) {
            return null;
        }

        ByteArrayOutputStream items = new ByteArrayOutputStream();
        writeMp4Text(items, "nam", tags.title());
        writeMp4Text(items, "ART", tags.artist());
        writeMp4Text(items, "alb", tags.album());
        Artwork artwork = tags.artwork();
        if (artwork != null) {
            final int dataType = artwork.mimeType().equals("image/png") ? MP4_DATA_TYPE_PNG : MP4_DATA_TYPE_JPEG;
            items.write(mp4Box("covr", mp4Data(dataType, artwork.data())));
        }

        // The handler tells players the metadata box holds iTunes style tags.
        ByteArrayOutputStream handler = new ByteArrayOutputStream();
        handler.write(new byte[8]); // Version, flags and pre defined.
        handler.write("mdirappl".getBytes(StandardCharsets.ISO_8859_1));
        handler.write(new byte[9]); // Reserved and an empty name.

        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        meta.write(new byte[4]); // Version and flags.
        meta.write(mp4Box("hdlr", handler.toByteArray()));
        meta.write(mp4Box("ilst", items.toByteArray()));

        children.write(mp4Box("udta", mp4Box("meta", meta.toByteArray())));
        return mp4Box("moov", children.toByteArray());
    }

    /**
     * @param name The name of the tag, which starts with the copyright sign.
     */
    private static void writeMp4Text(ByteArrayOutputStream items, String name, @Nullable String text)
            throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        byte[] type = new byte[4];
        type[0] = COPYRIGHT_SIGN;
        System.arraycopy(name.getBytes(StandardCharsets.ISO_8859_1), 0, type, 1, 3);
        items.write(mp4Box(type, mp4Data(MP4_DATA_TYPE_UTF8, text.getBytes(StandardCharsets.UTF_8))));
    }

    private static byte[] mp4Data(int dataType, byte[] value) {
        ByteBuffer data = ByteBuffer.allocate(8 + value.length);
        data.putInt(dataType);
        data.putInt(0); // Locale.
        data.put(value);
        return mp4Box("data", data.array());
    }

    private static byte[] mp4Box(String type, byte[] content) {
        return mp4Box(type.getBytes(StandardCharsets.ISO_8859_1), content);
    }

    private static byte[] mp4Box(byte[] type, byte[] content) {
        ByteBuffer box = ByteBuffer.allocate(MP4_HEADER_SIZE + content.length);
        box.putInt(MP4_HEADER_SIZE + content.length);
        box.put(type);
        box.put(content);
        return box.array();
    }

    private static long readUInt32(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).getInt() & 0xFFFFFFFFL;
    }

    private static void readFully(MediaDataSource source, long position, byte[] buffer, int length)
            throws IOException {
        int read = 0;
        while (read < length) {
            final int count = source.readAt(position + read, buffer, read, length - read);
            if (count <= 0) {
                throw new IOException("Stream ended at: " + (position + read));
            }
            read += count;
        }
    }

    private static void copy(MediaDataSource source, long start, long end, OutputStream output,
                             ProgressListener progress) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long position = start;
        while (position < end) {
            final int read = source.readAt(position, buffer, 0, (int) Math.min(buffer.length, end - position));
            if (read <= 0) {
                throw new IOException("Stream ended at: " + position);
            }
            output.write(buffer, 0, read);
            position += read;
            progress.onProgress();
        }
    }

    // endregion

    // region Ogg Opus

    private static final String OPUS_HEAD = "OpusHead";
    private static final String OPUS_TAGS = "OpusTags";
    /**
     * Byte offset of the pre-skip in the Opus identification header.
     */
    private static final int OPUS_HEAD_PRE_SKIP_OFFSET = 10;
    private static final String VENDOR = "Morphe";
    /**
     * Front cover, in the picture block of the Vorbis comment.
     */
    private static final int PICTURE_TYPE_FRONT_COVER = 3;
    private static final int PICTURE_COLOR_DEPTH = 24;

    /**
     * Writes the Opus packets of a saved WebM stream to an Ogg Opus file.
     *
     * @param extractor The extractor of the stream, with its Opus track selected.
     */
    static void writeOggOpus(MediaExtractor extractor, MediaFormat format, int serial, OutputStream output,
                             Tags tags, ProgressListener progress) throws IOException {
        ByteBuffer identification = format.getByteBuffer("csd-0");
        if (identification == null) {
            throw new IOException("Opus stream has no identification header");
        }
        byte[] head = new byte[identification.remaining()];
        identification.duplicate().get(head);
        if (head.length <= OPUS_HEAD_PRE_SKIP_OFFSET + 1
                || !new String(head, 0, OPUS_HEAD.length(), StandardCharsets.ISO_8859_1).equals(OPUS_HEAD)) {
            throw new IOException("Opus stream has an unknown identification header");
        }

        OggStream ogg = new OggStream(output, serial);
        // The headers are each on their own pages, and the granule position of their pages is 0.
        ogg.addPacket(head, head.length, 0);
        ogg.flushPage(false);
        byte[] comments = createOpusTags(tags);
        ogg.addPacket(comments, comments.length, 0);
        ogg.flushPage(false);

        // The granule position counts the decoded samples, including the samples of the pre-skip.
        ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
        byte[] packet = new byte[COPY_BUFFER_SIZE];
        long samples = 0;
        int size;
        while ((size = extractor.readSampleData(buffer, 0)) >= 0) {
            if (size > packet.length) {
                packet = new byte[size];
            }
            buffer.get(packet, 0, size);
            buffer.clear();
            samples += getOpusPacketSamples(packet, size);
            ogg.addPacket(packet, size, samples);
            extractor.advance();
            progress.onProgress();
        }
        ogg.flushPage(true);
    }

    private static byte[] createOpusTags(Tags tags) throws IOException {
        List<String> comments = new ArrayList<>();
        comments.add("TITLE=" + tags.title());
        if (tags.artist() != null && !tags.artist().isEmpty()) {
            comments.add("ARTIST=" + tags.artist());
        }
        if (tags.album() != null && !tags.album().isEmpty()) {
            comments.add("ALBUM=" + tags.album());
        }
        Artwork artwork = tags.artwork();
        if (artwork != null) {
            comments.add("METADATA_BLOCK_PICTURE=" + Base64.encodeToString(createPictureBlock(artwork), Base64.NO_WRAP));
        }

        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(OPUS_TAGS.getBytes(StandardCharsets.ISO_8859_1));
        writeLittleEndianString(packet, VENDOR);
        writeLittleEndianInt(packet, comments.size());
        for (String comment : comments) {
            writeLittleEndianString(packet, comment);
        }
        return packet.toByteArray();
    }

    /**
     * The picture block of FLAC, which Vorbis comments use for covers. Its numbers are big endian.
     */
    private static byte[] createPictureBlock(Artwork artwork) {
        byte[] mimeType = artwork.mimeType().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer block = ByteBuffer.allocate(32 + mimeType.length + artwork.data().length);
        block.putInt(PICTURE_TYPE_FRONT_COVER);
        block.putInt(mimeType.length);
        block.put(mimeType);
        block.putInt(0); // Description.
        block.putInt(artwork.width());
        block.putInt(artwork.height());
        block.putInt(PICTURE_COLOR_DEPTH);
        block.putInt(0); // Colors of indexed images.
        block.putInt(artwork.data().length);
        block.put(artwork.data());
        return block.array();
    }

    private static void writeLittleEndianString(ByteArrayOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writeLittleEndianInt(output, bytes.length);
        output.write(bytes);
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >> 8);
        output.write(value >> 16);
        output.write(value >> 24);
    }

    /**
     * @return The samples at 48 kHz in an Opus packet, from its table of contents byte.
     */
    static int getOpusPacketSamples(byte[] packet, int size) {
        if (size == 0) {
            return 0;
        }
        final int config = (packet[0] & 0xFF) >> 3;
        final int frameSamples;
        if (config < 12) {
            // SILK: 10, 20, 40 or 60 ms.
            frameSamples = new int[]{480, 960, 1920, 2880}[config & 3];
        } else if (config < 16) {
            // Hybrid: 10 or 20 ms.
            frameSamples = (config & 1) == 0 ? 480 : 960;
        } else {
            // CELT: 2.5, 5, 10 or 20 ms.
            frameSamples = 120 << (config & 3);
        }

        final int frames = switch (packet[0] & 3) {
            case 0 -> 1;
            case 1, 2 -> 2;
            default -> size > 1 ? packet[1] & 0x3F : 0;
        };
        return frameSamples * frames;
    }

    /**
     * Writes packets to the pages of an Ogg stream.
     */
    private static final class OggStream {
        private static final int MAX_SEGMENTS = 255;
        private static final int MAX_SEGMENT_SIZE = 255;
        private static final int HEADER_SIZE = 27;
        private static final int CRC_OFFSET = 22;
        private static final int FLAG_CONTINUED = 1;
        private static final int FLAG_FIRST_PAGE = 2;
        private static final int FLAG_LAST_PAGE = 4;
        /**
         * Granule position of a page on which no packet ends.
         */
        private static final long NO_GRANULE = -1;
        private static final int[] CRC_TABLE = new int[256];

        static {
            for (int i = 0; i < 256; i++) {
                int crc = i << 24;
                for (int bit = 0; bit < 8; bit++) {
                    crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
                }
                CRC_TABLE[i] = crc;
            }
        }

        private final OutputStream output;
        private final int serial;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final byte[] segments = new byte[MAX_SEGMENTS];
        private int segmentCount;
        private int sequence;
        private long granule = NO_GRANULE;
        private boolean firstPage = true;
        /**
         * If the page starts with the rest of a packet of the previous page.
         */
        private boolean continued;
        /**
         * If the last segment of the page is not the end of its packet.
         */
        private boolean packetOpen;

        OggStream(OutputStream output, int serial) {
            this.output = output;
            this.serial = serial;
        }

        /**
         * @param granulePosition The granule position at the end of the packet.
         */
        void addPacket(byte[] packet, int size, long granulePosition) throws IOException {
            int position = 0;
            while (true) {
                if (segmentCount == MAX_SEGMENTS) {
                    flushPage(false);
                }
                // A segment shorter than the maximum ends the packet, so a packet whose size
                // is a multiple of the maximum ends with an empty segment.
                final int segmentSize = Math.min(MAX_SEGMENT_SIZE, size - position);
                segments[segmentCount++] = (byte) segmentSize;
                body.write(packet, position, segmentSize);
                position += segmentSize;
                packetOpen = segmentSize == MAX_SEGMENT_SIZE;
                if (!packetOpen) {
                    break;
                }
            }
            granule = granulePosition;
        }

        void flushPage(boolean lastPage) throws IOException {
            if (segmentCount == 0 && !lastPage) {
                return;
            }
            int flags = continued ? FLAG_CONTINUED : 0;
            if (firstPage) {
                flags |= FLAG_FIRST_PAGE;
            }
            if (lastPage) {
                flags |= FLAG_LAST_PAGE;
            }

            ByteBuffer page = ByteBuffer.allocate(HEADER_SIZE + segmentCount + body.size())
                    .order(ByteOrder.LITTLE_ENDIAN);
            page.put("OggS".getBytes(StandardCharsets.ISO_8859_1));
            page.put((byte) 0); // Version.
            page.put((byte) flags);
            page.putLong(granule);
            page.putInt(serial);
            page.putInt(sequence++);
            page.putInt(0); // Checksum, set below.
            page.put((byte) segmentCount);
            page.put(segments, 0, segmentCount);
            page.put(body.toByteArray());

            byte[] bytes = page.array();
            int crc = 0;
            for (byte b : bytes) {
                crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b) & 0xFF];
            }
            ByteBuffer.wrap(bytes, CRC_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc);
            output.write(bytes);

            firstPage = false;
            continued = packetOpen;
            segmentCount = 0;
            body.reset();
            granule = NO_GRANULE;
        }
    }

    // endregion
}
