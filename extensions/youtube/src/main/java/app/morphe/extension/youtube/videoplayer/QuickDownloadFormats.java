/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4539
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import androidx.annotation.Nullable;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;
import app.morphe.extension.youtube.patches.InAppDownloadsPatch;

/**
 * File sizes for the quick download menu, read from the video's streams.
 * <p>
 * Each quality is matched to the stream the downloader picks for it: up to 1080p H.264,
 * above that AV1, else VP9; the highest frame rate first, then SDR over HDR.
 * Video sizes include the AAC audio a downloader merges into an MP4 file.
 */
final class QuickDownloadFormats {

    /** The video qualities of the menu, highest first. */
    static final int[] HEIGHTS = {2160, 1440, 1080, 720};

    /** Size of a quality the video does not have. */
    static final long UNAVAILABLE = -1;

    private static final Pattern QUALITY_LABEL = Pattern.compile("^(\\d+)p(\\d+)?");
    /** The mime type is lower case: {@code codecs="av01.0.12m.10"}. */
    private static final Pattern AV1_TEN_BIT = Pattern.compile("av01\\.\\d+\\.\\d+[mh]\\.1[02]");
    private static final Pattern VP9_PROFILE2 = Pattern.compile("vp09\\.0[23]");
    private static final Pattern CLEN = Pattern.compile("[?&]clen=(\\d+)");

    /** Bitrate of an MP3 made from the audio, to estimate its size. */
    static final int MP3_KBPS = 192;

    /**
     * @param videos Bytes of each of {@link #HEIGHTS}, 0 if unknown, {@link #UNAVAILABLE} if missing.
     * @param opus   Bytes of the Opus audio, 0 if unknown.
     * @param aac    Bytes of the AAC (M4A) audio, 0 if unknown.
     * @param mp3    Estimated bytes of a {@link #MP3_KBPS} kbps MP3, 0 if unknown.
     */
    record Sizes(long[] videos, long opus, long aac, long mp3,
                 int[] fps, String[] codecs, int aacKbps,
                 String title, String author, long lengthSeconds) {
    }

    private record Candidate(int height, int fps, boolean hdr, int codecRank, int bitrate, long length) {
    }

    private QuickDownloadFormats() {
    }

    /**
     * Network request, call off the main thread.
     *
     * @return Null if the streams could not be read.
     */
    @Nullable
    static Sizes fetch(String videoId) {
        try {
            StreamingDataRequest.StreamData stream = StreamingDataRequest.fetchRequestForDownload(videoId,
                    InAppDownloadsPatch.DOWNLOAD_CLIENTS, InAppDownloadsPatch.DOWNLOAD_CLIENTS.get(0)).getStream();
            if (stream == null) {
                Logger.printDebug(() -> "No streams for the quick download sizes of: " + videoId);
                return null;
            }
            return parse(PlayerResponse.parseFrom(stream.streamingData()));
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read the quick download sizes of: " + videoId, ex);
            return null;
        }
    }

    private static Sizes parse(PlayerResponse response) {
        Candidate[] best = new Candidate[HEIGHTS.length];
        long opus = 0, aac = 0;
        int opusBitrate = -1, aacBitrate = -1, aacAverageBitrate = 0;
        boolean opusOriginal = false, aacOriginal = false;
        long durationMs = 0;

        for (Format format : response.getStreamingData().getAdaptiveFormatsList()) {
            String mimeType = format.getMimeType().toLowerCase(Locale.US);
            String url = format.getUrl();
            long length = format.getContentLength() > 0 ? format.getContentLength() : clen(url);

            if (mimeType.startsWith("video/")) {
                int[] quality = qualityOf(format);
                if (quality == null) continue;
                int index = indexOf(quality[0]);
                if (index < 0) continue;

                final int codecRank;
                if (mimeType.contains("avc1")) {
                    // H.264 is the choice up to 1080p, above that there is none.
                    codecRank = HEIGHTS[index] <= 1080 ? 0 : 3;
                } else if (mimeType.contains("av01")) {
                    codecRank = HEIGHTS[index] <= 1080 ? 1 : 0;
                } else if (mimeType.contains("vp9") || mimeType.contains("vp09")) {
                    codecRank = HEIGHTS[index] <= 1080 ? 2 : 1;
                } else {
                    continue;
                }
                boolean hdr = VP9_PROFILE2.matcher(mimeType).find() || AV1_TEN_BIT.matcher(mimeType).find();
                Candidate candidate = new Candidate(quality[0], quality[1], hdr, codecRank, format.getBitrate(), length);
                if (best[index] == null || isBetter(candidate, best[index])) {
                    best[index] = candidate;
                }
            } else if (mimeType.startsWith("audio/")) {
                // Multi-language videos list dubbed tracks as well. The original is marked in the url.
                boolean original = url.contains("acont%3Doriginal") || url.contains("acont=original");
                boolean dubbed = url.contains("acont%3Ddubbed") || url.contains("acont=dubbed");
                boolean drc = url.contains("drc%3D1") || url.contains("drc=1");
                if (dubbed || drc) continue;
                durationMs = Math.max(durationMs, format.getApproxDurationMs());

                if (mimeType.contains("opus")) {
                    if (opusBitrate < 0 || (original && !opusOriginal)
                            || (original == opusOriginal && format.getBitrate() > opusBitrate)) {
                        opus = length;
                        opusBitrate = format.getBitrate();
                        opusOriginal = original;
                    }
                } else if (mimeType.contains("mp4a")) {
                    if (aacBitrate < 0 || (original && !aacOriginal)
                            || (original == aacOriginal && format.getBitrate() > aacBitrate)) {
                        aac = length;
                        aacBitrate = format.getBitrate();
                        aacAverageBitrate = format.getAverageBitrate();
                        aacOriginal = original;
                    }
                }
            }
        }

        // An MP4 video gets AAC audio, a WebM one Opus.
        final long videoAudio = aac > 0 ? aac : opus;
        long[] videos = new long[HEIGHTS.length];
        for (int i = 0; i < HEIGHTS.length; i++) {
            Candidate candidate = best[i];
            if (candidate == null) {
                videos[i] = UNAVAILABLE;
            } else if (candidate.length() > 0 && videoAudio > 0) {
                videos[i] = candidate.length() + videoAudio;
            }
        }
        int[] fps = new int[HEIGHTS.length];
        String[] codecs = new String[HEIGHTS.length];
        for (int i = 0; i < HEIGHTS.length; i++) {
            Candidate candidate = best[i];
            if (candidate == null) continue;
            fps[i] = candidate.fps();
            codecs[i] = codecName(candidate);
        }
        long lengthSeconds = response.getVideoDetails().getLengthSeconds();
        if (durationMs <= 0) durationMs = lengthSeconds * 1000;
        long mp3 = durationMs > 0 ? durationMs * MP3_KBPS / 8 : 0;
        final int aacBits = aacAverageBitrate > 0 ? aacAverageBitrate : aacBitrate;
        int aacKbps = aacBits > 0 ? Math.round(aacBits / 1000f) : 0;
        return new Sizes(videos, opus, aac, mp3, fps, codecs, aacKbps,
                response.getVideoDetails().getTitle(), response.getVideoDetails().getOwnerChannelName(),
                lengthSeconds);
    }

    /**
     * The order of the downloader: the codec of the quality (H.264 up to 1080p, AV1 or VP9 above),
     * then frame rate, SDR, AV1 before VP9, and the larger encode.
     */
    private static boolean isBetter(Candidate a, Candidate b) {
        boolean aWanted = isWantedCodec(a), bWanted = isWantedCodec(b);
        if (aWanted != bWanted) return aWanted;
        if (a.fps() != b.fps()) return a.fps() > b.fps();
        if (a.hdr() != b.hdr()) return !a.hdr();
        if (a.codecRank() != b.codecRank()) return a.codecRank() < b.codecRank();
        return a.bitrate() > b.bitrate();
    }

    private static String codecName(Candidate candidate) {
        final boolean aboveFullHd = candidate.height() > 1080;
        return switch (candidate.codecRank()) {
            case 0 -> aboveFullHd ? "AV1" : "H.264";
            case 1 -> aboveFullHd ? "VP9" : "AV1";
            case 2 -> "VP9";
            default -> "H.264";
        };
    }

    private static boolean isWantedCodec(Candidate candidate) {
        return candidate.height() <= 1080 ? candidate.codecRank() == 0 : candidate.codecRank() <= 1;
    }

    private static int indexOf(int height) {
        for (int i = 0; i < HEIGHTS.length; i++) {
            if (HEIGHTS[i] == height) return i;
        }
        return -1;
    }

    /**
     * The quality as YouTube labels it ({@code 1080p60}): {@code {height, fps}}.
     * Without a label the shorter side of the picture stands in (vertical videos count by their width).
     */
    @Nullable
    private static int[] qualityOf(Format format) {
        int height = 0;
        int fps = 0;
        Matcher matcher = QUALITY_LABEL.matcher(format.getQualityLabel());
        if (matcher.find()) {
            height = Integer.parseInt(matcher.group(1));
            if (matcher.group(2) != null) fps = Integer.parseInt(matcher.group(2));
        }
        if (height <= 0) {
            int width = format.getWidth() > 0 ? format.getWidth() : Integer.MAX_VALUE;
            height = Math.min(width, format.getHeight());
            if (height <= 0 || height == Integer.MAX_VALUE) return null;
        }
        if (fps <= 0) fps = format.getFps() > 0 ? format.getFps() : 30;
        return new int[]{height, fps};
    }

    /** YouTube's own JPG thumbnails, largest first. A missing size answers 404. */
    static final String[] THUMBNAIL_NAMES = {"maxresdefault", "sddefault", "hqdefault"};

    /**
     * Network request, call off the main thread.
     *
     * @return Bytes of the largest JPG thumbnail of the video, 0 if unknown.
     */
    static long fetchThumbnailSize(String videoId) {
        for (String name : THUMBNAIL_NAMES) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + videoId + "/" + name + ".jpg")
                        .openConnection();
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    return Math.max(0, connection.getContentLengthLong());
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not read the thumbnail size of: " + videoId, ex);
                return 0;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return 0;
    }

    private static long clen(String url) {
        Matcher matcher = CLEN.matcher(url);
        if (!matcher.find()) return 0;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
