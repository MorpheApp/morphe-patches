/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A single MP4 file with the video and audio of two YouTube DASH streams, for receivers that
 * only play a single file. It is never stored: its layout is worked out from the index of each stream,
 * and the fragments are fetched from YouTube when the receiver asks for them.
 * <p>
 * YouTube's MP4 DASH streams are fragmented: an init segment (ftyp, moov), an index (sidx)
 * and fragments of a moof and an mdat, whose data offsets are relative to their moof.
 * The file made here has:
 * <pre>
 * ftyp | moov: mvhd, video trak (id 1), audio trak (id 2), mvex (mehd, trex 1, trex 2)
 *      | sidx: one reference per video fragment, including the audio fragments after it
 *      | the fragments of both streams ordered by time, unchanged except for the
 *        sequence number in mfhd and the track id in tfhd
 * </pre>
 * The index lets the receiver seek without first reading every fragment header.
 */
final class VirtualMp4 implements CastStreamServer.Source {

    private static final int VIDEO_TRACK_ID = 1;
    private static final int AUDIO_TRACK_ID = 2;
    // Offsets in a fragment: moof header (8), mfhd (16: header 8, version/flags 4, sequence 4),
    // traf header (8), tfhd (header 8, version/flags 4, track id 4).
    private static final int MFHD_TYPE_OFFSET = 12;
    private static final int SEQUENCE_OFFSET = 20;
    private static final int TFHD_TYPE_OFFSET = 36;
    private static final int TRACK_ID_OFFSET = 44;
    private static final int FRAGMENT_HEADER_CHECK_BYTES = 48;

    private static final class Fragment {
        final boolean video;
        final String url;
        final long sourceOffset;
        final long size;
        final double time;
        final long duration;
        long offset;
        int sequence;

        Fragment(boolean video, String url, long sourceOffset, long size, double time, long duration) {
            this.video = video;
            this.url = url;
            this.sourceOffset = sourceOffset;
            this.size = size;
            this.time = time;
            this.duration = duration;
        }
    }

    private static final class Index {
        final byte[] head;
        final byte[] ftyp;
        final byte[] moov;
        final long timescale;
        final long earliestTime;
        final List<Fragment> fragments = new ArrayList<>();

        Index(CastStreams.Stream stream, String userAgent) throws IOException {
            head = CastStreamServer.readUpstream(stream.url, userAgent, 0, stream.indexEnd);
            ftyp = box(head, 0, head.length, "ftyp");
            moov = box(head, 0, head.length, "moov");
            final int sidxStart = boxOffset(head, 0, head.length, "sidx");
            ByteBuffer sidx = ByteBuffer.wrap(head, sidxStart, head.length - sidxStart);
            final int sidxSize = sidx.getInt();
            sidx.getInt(); // type
            final int version = sidx.get();
            sidx.position(sidx.position() + 3 + 4); // flags, reference id
            timescale = Integer.toUnsignedLong(sidx.getInt());
            final long firstOffset;
            if (version == 0) {
                earliestTime = Integer.toUnsignedLong(sidx.getInt());
                firstOffset = Integer.toUnsignedLong(sidx.getInt());
            } else {
                earliestTime = sidx.getLong();
                firstOffset = sidx.getLong();
            }
            sidx.getShort(); // reserved
            final int count = Short.toUnsignedInt(sidx.getShort());
            long offset = sidxStart + (long) sidxSize + firstOffset;
            long time = earliestTime;
            for (int i = 0; i < count; i++) {
                final int reference = sidx.getInt();
                final long duration = Integer.toUnsignedLong(sidx.getInt());
                sidx.getInt(); // stream access point
                if (reference < 0) {
                    throw new IOException("Index refers to another index");
                }
                fragments.add(new Fragment(stream.isVideo(), stream.url, offset, reference,
                        (double) time / timescale, duration));
                offset += reference;
                time += duration;
            }
            if (fragments.isEmpty()) {
                throw new IOException("Stream index is empty");
            }
        }
    }

    private final String userAgent;
    private final byte[] head;
    private final List<Fragment> fragments;
    private final long length;

    VirtualMp4(CastStreams.Stream video, CastStreams.Stream audio, String userAgent, double durationSeconds)
            throws IOException {
        this.userAgent = userAgent;
        Index videoIndex = new Index(video, userAgent);
        Index audioIndex = new Index(audio, userAgent);
        checkFragmentLayout(videoIndex.fragments.get(0));
        checkFragmentLayout(audioIndex.fragments.get(0));

        List<Fragment> all = new ArrayList<>(videoIndex.fragments);
        all.addAll(audioIndex.fragments);
        // At equal times the video fragment comes first, so the index starts at a video fragment.
        all.sort(Comparator.comparingDouble((Fragment fragment) -> fragment.time)
                .thenComparing(fragment -> !fragment.video));
        fragments = all;

        byte[] moov = moov(videoIndex.moov, audioIndex.moov, durationSeconds);
        final int sidxSize = sidxSize(videoIndex);
        final long mediaStart = (long) videoIndex.ftyp.length + moov.length + sidxSize;
        long offset = mediaStart;
        int sequence = 1;
        for (Fragment fragment : fragments) {
            fragment.offset = offset;
            fragment.sequence = sequence++;
            offset += fragment.size;
        }
        length = offset;

        ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
        headBytes.write(videoIndex.ftyp);
        headBytes.write(moov);
        headBytes.write(sidx(videoIndex, mediaStart));
        head = headBytes.toByteArray();
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public String contentType() {
        return "video/mp4";
    }

    @Override
    public void write(OutputStream output, long start, long count) throws IOException {
        long position = start;
        final long end = start + count;
        if (position < head.length) {
            final int headEnd = (int) Math.min(head.length, end);
            output.write(head, (int) position, headEnd - (int) position);
            position = headEnd;
        }
        int index = findFragment(position);
        while (position < end && index < fragments.size()) {
            Fragment fragment = fragments.get(index++);
            final long inFragment = position - fragment.offset;
            final long bytes = Math.min(fragment.size - inFragment, end - position);
            final long sourceStart = fragment.sourceOffset;
            CastStreamServer.copyUpstream(fragment.url, userAgent, sourceStart + inFragment, bytes, output,
                    (buffer, length, sourcePosition) -> {
                        patchInt(buffer, length, sourcePosition - sourceStart, SEQUENCE_OFFSET, fragment.sequence);
                        patchInt(buffer, length, sourcePosition - sourceStart, TRACK_ID_OFFSET,
                                fragment.video ? VIDEO_TRACK_ID : AUDIO_TRACK_ID);
                    });
            position += bytes;
        }
    }

    /**
     * @return Index of the fragment that contains the position.
     */
    private int findFragment(long position) {
        int low = 0;
        int high = fragments.size() - 1;
        while (low < high) {
            final int middle = (low + high + 1) >>> 1;
            if (fragments.get(middle).offset <= position) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /**
     * Writes a big endian int at an offset of the fragment, for the part of it that is in the buffer.
     *
     * @param bufferStart Offset of {@code buffer[0]} in the fragment.
     */
    private static void patchInt(byte[] buffer, int length, long bufferStart, int offset, int value) {
        for (int i = 0; i < 4; i++) {
            final long at = offset + i - bufferStart;
            if (at >= 0 && at < length) {
                buffer[(int) at] = (byte) (value >>> (24 - 8 * i));
            }
        }
    }

    private void checkFragmentLayout(Fragment fragment) throws IOException {
        byte[] start = CastStreamServer.readUpstream(fragment.url, userAgent, fragment.sourceOffset,
                fragment.sourceOffset + FRAGMENT_HEADER_CHECK_BYTES - 1);
        if (!type(start, 4).equals("moof") || !type(start, MFHD_TYPE_OFFSET).equals("mfhd")
                || !type(start, TFHD_TYPE_OFFSET).equals("tfhd")) {
            throw new IOException("Unexpected fragment layout");
        }
    }

    private static byte[] moov(byte[] videoMoov, byte[] audioMoov, double durationSeconds) throws IOException {
        byte[] mvhd = box(videoMoov, 8, videoMoov.length, "mvhd");
        ByteBuffer header = ByteBuffer.wrap(mvhd);
        final boolean version1 = mvhd[8] == 1;
        final long timescale = Integer.toUnsignedLong(header.getInt(version1 ? 28 : 20));
        final long duration = Math.round(durationSeconds * timescale);
        if (version1) {
            header.putLong(32, duration);
        } else {
            header.putInt(24, (int) duration);
        }
        header.putInt(mvhd.length - 4, AUDIO_TRACK_ID + 1); // next track id

        byte[] mehd = ByteBuffer.allocate(20).putInt(20).put(ascii("mehd")).putInt(1 << 24).putLong(duration).array();
        // Only the trex boxes of the streams, which must be children of the mvex of the file.
        byte[] mvex = container("mvex", mehd, trex(videoMoov, VIDEO_TRACK_ID), trex(audioMoov, AUDIO_TRACK_ID));
        return container("moov", mvhd,
                trak(videoMoov, VIDEO_TRACK_ID),
                trak(audioMoov, AUDIO_TRACK_ID),
                mvex);
    }

    private static int sidxSize(Index index) {
        return 32 + 12 * index.fragments.size();
    }

    /**
     * Index of the video fragments. A reference spans from a video fragment to the next one,
     * including the audio fragments in between.
     * <p>
     * Tested on Cast devices: with this index the LG webOS receiver and the Chromecast with Google TV
     * start at once and seek anywhere. With an index for each track, the Chromecast and the Nest Hub
     * read every fragment header before playing. The Nest Hub stalls after a far seek with this index,
     * but it plays DASH, which is tried first.
     *
     * @param end Offset where this index ends in the file.
     */
    private byte[] sidx(Index index, long end) {
        List<Fragment> track = new ArrayList<>();
        for (Fragment fragment : fragments) {
            if (fragment.video) {
                track.add(fragment);
            }
        }
        final int size = sidxSize(index);
        ByteBuffer sidx = ByteBuffer.allocate(size)
                .putInt(size).put(ascii("sidx")).putInt(0)
                .putInt(VIDEO_TRACK_ID)
                .putInt((int) index.timescale)
                .putInt((int) index.earliestTime)
                .putInt((int) (track.get(0).offset - end))
                .putShort((short) 0)
                .putShort((short) track.size());
        for (int i = 0; i < track.size(); i++) {
            final long next = i + 1 < track.size() ? track.get(i + 1).offset : length;
            sidx.putInt((int) (next - track.get(i).offset))
                    .putInt((int) track.get(i).duration)
                    .putInt(0x90000000); // starts with a stream access point of type 1
        }
        return sidx.array();
    }

    /**
     * Copy of the trex box of a stream, with the track id set.
     */
    private static byte[] trex(byte[] moov, int trackId) throws IOException {
        byte[] trex = box(moov, 8, moov.length, "trex");
        // trex: size, type, version and flags, then the track id.
        ByteBuffer.wrap(trex).putInt(12, trackId);
        return trex;
    }

    /**
     * Copy of the trak box of a stream, with the track id set in its tkhd box.
     */
    private static byte[] trak(byte[] moov, int trackId) throws IOException {
        byte[] trak = box(moov, 8, moov.length, "trak");
        final int tkhd = boxOffset(trak, 8, trak.length, "tkhd");
        // tkhd: version/flags, creation and modification time (4 or 8 bytes each), then the track id.
        final int idOffset = tkhd + 12 + (trak[tkhd + 8] == 1 ? 16 : 8);
        ByteBuffer.wrap(trak).putInt(idOffset, trackId);
        return trak;
    }

    private static byte[] container(String type, byte[]... children) {
        int size = 8;
        for (byte[] child : children) {
            size += child.length;
        }
        ByteBuffer box = ByteBuffer.allocate(size).putInt(size).put(ascii(type));
        for (byte[] child : children) {
            box.put(child);
        }
        return box.array();
    }

    private static byte[] box(byte[] data, int start, int end, String type) throws IOException {
        final int offset = boxOffset(data, start, end, type);
        return Arrays.copyOfRange(data, offset, offset + ByteBuffer.wrap(data).getInt(offset));
    }

    /**
     * @return Offset of the first box of the type among the boxes in the range.
     * A box of a container type is searched recursively.
     */
    private static int boxOffset(byte[] data, int start, int end, String type) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int offset = start;
        while (offset + 8 <= end) {
            final int size = buffer.getInt(offset);
            if (size < 8 || offset + size > end) {
                break;
            }
            String found = type(data, offset + 4);
            if (found.equals(type)) {
                return offset;
            }
            if (found.equals("mvex") || found.equals("trak")) {
                try {
                    return boxOffset(data, offset + 8, offset + size, type);
                } catch (IOException ex) {
                    // Not in this container.
                }
            }
            offset += size;
        }
        throw new IOException("No " + type + " box");
    }

    private static String type(byte[] data, int offset) {
        return new String(data, offset, 4, StandardCharsets.US_ASCII);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
