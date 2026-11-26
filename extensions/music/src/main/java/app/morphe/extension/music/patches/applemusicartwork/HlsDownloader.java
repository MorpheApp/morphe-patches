/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5082
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.applemusicartwork;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Downloads an HLS motion artwork into one local MP4 file (init segment and media segments),
 * as MediaPlayer is unreliable with the fragmented MP4 HLS streams of Apple Music.
 */
final class HlsDownloader {

    private static final long MAX_ARTWORK_BYTES = 48L * 1024 * 1024;

    /**
     * Taller variants are not preferred, as the player artwork is never that large.
     */
    private static final int MAX_PREFERRED_HEIGHT = 1080;

    private static final String STREAM_INFO_TAG = "#EXT-X-STREAM-INF";
    private static final String SEGMENT_TAG = "#EXTINF";
    private static final String MAP_TAG = "#EXT-X-MAP:";
    private static final String BYTE_RANGE_TAG = "#EXT-X-BYTERANGE:";

    private static final Pattern RESOLUTION_HEIGHT_PATTERN = Pattern.compile("RESOLUTION=\\d+x(\\d+)");
    private static final Pattern CODECS_ATTRIBUTE_PATTERN = attributePattern("CODECS");
    private static final Pattern URI_ATTRIBUTE_PATTERN = attributePattern("URI");
    private static final Pattern BYTE_RANGE_ATTRIBUTE_PATTERN = attributePattern("BYTERANGE");

    private static final class Variant {
        final String url;
        /**
         * Rank of the variant, higher is better: H.264 before other codecs, then the largest.
         */
        final int rank;

        Variant(String url, boolean h264, int height) {
            this.url = url;
            this.rank = (h264 ? MAX_PREFERRED_HEIGHT + 1 : 0) + (height > MAX_PREFERRED_HEIGHT ? 0 : height);
        }
    }

    /**
     * Byte range of a resource, as length[@offset].
     */
    private static final class ByteRange {
        final long length;
        final long offset;

        ByteRange(long length, long offset) {
            this.length = length;
            this.offset = offset;
        }

        String toHttpRange() {
            return offset + "-" + (offset + length - 1);
        }
    }

    private HlsDownloader() {
    }

    private static Pattern attributePattern(String name) {
        return Pattern.compile(name + "=(\"[^\"]*\"|[^,]*)");
    }

    /**
     * @return The media playlist urls of a playlist, best first.
     */
    static List<String> getVariantUrls(String playlistUrl) throws IOException {
        String playlist = AppleMusicCatalog.getString(playlistUrl);
        List<String> urls = new ArrayList<>();
        if (!playlist.contains(STREAM_INFO_TAG)) {
            // Already a media playlist.
            urls.add(playlistUrl);
            return urls;
        }

        List<Variant> variants = new ArrayList<>();
        String[] lines = playlist.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith(STREAM_INFO_TAG)) continue;

            Matcher heightMatcher = RESOLUTION_HEIGHT_PATTERN.matcher(line);
            final int height = heightMatcher.find() ? Integer.parseInt(heightMatcher.group(1)) : 0;
            String codecs = getAttribute(line, CODECS_ATTRIBUTE_PATTERN);
            final boolean h264 = codecs != null && codecs.contains("avc1");

            // The url is the next line that is not a tag.
            for (int j = i + 1; j < lines.length; j++) {
                String url = lines[j].trim();
                if (url.isEmpty() || url.startsWith("#")) continue;

                variants.add(new Variant(resolveUrl(playlistUrl, url), h264, height));
                break;
            }
        }
        if (variants.isEmpty()) {
            throw new IOException("No variants in playlist: " + playlistUrl);
        }

        variants.sort(Comparator.comparingInt((Variant variant) -> variant.rank).reversed());
        for (Variant variant : variants) {
            urls.add(variant.url);
        }
        return urls;
    }

    /**
     * Downloads all segments of a media playlist into one file.
     */
    static void download(String mediaPlaylistUrl, File file) throws IOException {
        String playlist = AppleMusicCatalog.getString(mediaPlaylistUrl);
        File partFile = new File(file.getPath() + ".part");

        try {
            try (OutputStream output = new FileOutputStream(partFile)) {
                if (!playlist.contains(SEGMENT_TAG)) {
                    // Not a playlist, but the video itself.
                    write(mediaPlaylistUrl, null, output, 0);
                } else {
                    writeSegments(mediaPlaylistUrl, playlist, output);
                }
            }

            if (!partFile.renameTo(file)) {
                throw new IOException("Could not rename: " + partFile);
            }
        } finally {
            Utils.deleteQuietly(partFile);
        }
    }

    private static void writeSegments(String playlistUrl, String playlist, OutputStream output) throws IOException {
        long totalBytes = 0;
        String previousSegmentUrl = null;
        long previousSegmentEnd = 0;
        ByteRange pendingRange = null;

        for (String rawLine : playlist.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith(MAP_TAG)) {
                // Initialization segment.
                String uri = getAttribute(line, URI_ATTRIBUTE_PATTERN);
                if (uri == null) throw new IOException("Map without uri: " + line);
                String range = getAttribute(line, BYTE_RANGE_ATTRIBUTE_PATTERN);
                totalBytes = write(resolveUrl(playlistUrl, uri),
                        range == null ? null : parseByteRange(range, 0), output, totalBytes);
            } else if (line.startsWith(BYTE_RANGE_TAG)) {
                pendingRange = parseByteRange(line.substring(BYTE_RANGE_TAG.length()), -1);
            } else if (!line.startsWith("#")) {
                String segmentUrl = resolveUrl(playlistUrl, line);
                ByteRange range = null;
                if (pendingRange != null) {
                    // Without an offset, the range follows the previous range of the same resource.
                    final long offset = pendingRange.offset >= 0
                            ? pendingRange.offset
                            : (segmentUrl.equals(previousSegmentUrl) ? previousSegmentEnd : 0);
                    range = new ByteRange(pendingRange.length, offset);
                    previousSegmentEnd = offset + pendingRange.length;
                    pendingRange = null;
                }
                previousSegmentUrl = segmentUrl;
                totalBytes = write(segmentUrl, range, output, totalBytes);
            }
        }

        if (totalBytes == 0) {
            throw new IOException("Empty playlist: " + playlistUrl);
        }
        Logger.printDebug(() -> "Downloaded HLS segments of: " + playlistUrl);
    }

    /**
     * @return The total number of bytes written.
     */
    private static long write(String url, @Nullable ByteRange range, OutputStream output, long totalBytes)
            throws IOException {
        HttpURLConnection connection = AppleMusicCatalog.connect(url, null,
                range == null ? null : range.toHttpRange());
        try (InputStream input = connection.getInputStream()) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) > 0) {
                totalBytes += read;
                if (totalBytes > MAX_ARTWORK_BYTES) {
                    throw new IOException("Artwork is too large: " + url);
                }
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
        return totalBytes;
    }

    /**
     * @param defaultOffset Offset if the range has none.
     */
    private static ByteRange parseByteRange(String text, long defaultOffset) {
        String[] parts = text.trim().replace("\"", "").split("@");
        return new ByteRange(Long.parseLong(parts[0]),
                parts.length > 1 ? Long.parseLong(parts[1]) : defaultOffset);
    }

    @Nullable
    private static String getAttribute(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1).replace("\"", "") : null;
    }

    private static String resolveUrl(String baseUrl, String url) {
        return URI.create(baseUrl).resolve(url).toString();
    }
}
