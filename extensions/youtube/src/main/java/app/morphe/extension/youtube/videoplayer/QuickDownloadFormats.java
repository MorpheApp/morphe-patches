/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4539
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import androidx.annotation.Nullable;

import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;
import app.morphe.extension.youtube.patches.InAppDownloadsPatch;

/**
 * Formats of a video for the quick download menu, read from the video's streams.
 * <p>
 * Each quality is matched to the stream the downloader picks for it: up to 1080p H.264,
 * above that AV1, else VP9; the highest frame rate first, then SDR over HDR.
 * Video sizes include the audio a downloader merges into the file.
 */
final class QuickDownloadFormats {

    /** The video qualities of the menu, highest first. */
    static final int[] HEIGHTS = {2160, 1440, 1080, 720};

    /** YouTube has no H.264 streams above this height. */
    static final int MAX_H264_HEIGHT = 1080;

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10_000;

    private static final Pattern QUALITY_LABEL_HEIGHT = Pattern.compile("^(\\d+)p");
    /** The mime type is lower case: {@code codecs="av01.0.12m.10"}. */
    private static final Pattern AV1_TEN_BIT = Pattern.compile("av01\\.\\d+\\.\\d+[mh]\\.1[02]");
    private static final Pattern VP9_PROFILE_2 = Pattern.compile("vp09\\.0[23]");
    private static final Pattern CONTENT_LENGTH_PARAMETER = Pattern.compile("[?&]clen=(\\d+)");

    enum Codec {
        H264("H.264"),
        AV1("AV1"),
        VP9("VP9");

        final String displayName;

        Codec(String displayName) {
            this.displayName = displayName;
        }

        @Nullable
        static Codec fromMimeType(String mimeType) {
            if (mimeType.contains("avc1")) return H264;
            if (mimeType.contains("av01")) return AV1;
            if (mimeType.contains("vp9") || mimeType.contains("vp09")) return VP9;
            return null;
        }

        /**
         * @return The order the downloader prefers the codec in for a quality, lowest first:
         *         H.264 up to {@link #MAX_H264_HEIGHT}, above that AV1 and then VP9.
         */
        int rank(int height) {
            if (height <= MAX_H264_HEIGHT) return ordinal();
            return this == H264 ? values().length : ordinal();
        }

        /**
         * @return If the downloader asks for this codec at the height.
         */
        boolean isWanted(int height) {
            return (height <= MAX_H264_HEIGHT) == (this == H264);
        }
    }

    /**
     * @param bytes Size including the audio, 0 if unknown.
     */
    record VideoQuality(long bytes, int fps, Codec codec) {
    }

    /**
     * @param videos   Each of {@link #HEIGHTS}, null if the video does not have it.
     * @param aacBytes Size of the AAC (M4A) audio, 0 if unknown.
     * @param aacKbps  Bitrate of the AAC audio, 0 if unknown.
     * @param mp3Bytes Estimated size of a {@link ExternalDownloaderPreference#QUICK_DOWNLOAD_AUDIO_KBPS}
     *                 kbps MP3, 0 if unknown.
     */
    record Formats(VideoQuality[] videos, long aacBytes, int aacKbps, long mp3Bytes,
                   String title, String author, long lengthSeconds) {
    }

    private record VideoStream(int fps, boolean hdr, Codec codec, int bitrate, long bytes) {

        /**
         * The order of the downloader: the codec it asks for, then frame rate, SDR,
         * the codec it falls back to, and the larger encode.
         */
        boolean isBetterThan(VideoStream other, int height) {
            final boolean wanted = codec.isWanted(height);
            if (wanted != other.codec.isWanted(height)) return wanted;
            if (fps != other.fps) return fps > other.fps;
            if (hdr != other.hdr) return !hdr;
            final int codecRank = codec.rank(height), otherCodecRank = other.codec.rank(height);
            if (codecRank != otherCodecRank) return codecRank < otherCodecRank;
            return bitrate > other.bitrate;
        }
    }

    /**
     * The audio stream of one codec the downloader picks: the original track, then the highest bitrate.
     */
    private static final class AudioStream {
        @Nullable
        Format format;
        long bytes;
        boolean original;

        void offer(Format candidate, long candidateBytes, boolean candidateOriginal) {
            if (format == null || (candidateOriginal && !original)
                    || (candidateOriginal == original && candidate.getBitrate() > format.getBitrate())) {
                format = candidate;
                bytes = candidateBytes;
                original = candidateOriginal;
            }
        }
    }

    private QuickDownloadFormats() {
    }

    /**
     * Network request, call off the main thread.
     *
     * @return Null if the streams could not be read.
     */
    @Nullable
    static Formats fetch(String videoId) {
        try {
            StreamingDataRequest.StreamData stream = StreamingDataRequest.fetchRequestForDownload(videoId,
                    InAppDownloadsPatch.DOWNLOAD_CLIENTS, InAppDownloadsPatch.DOWNLOAD_CLIENTS.get(0)).getStream();
            if (stream == null) {
                Logger.printDebug(() -> "No streams for the quick download formats of: " + videoId);
                return null;
            }
            return parse(PlayerResponse.parseFrom(stream.streamingData()));
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read the quick download formats of: " + videoId, ex);
            return null;
        }
    }

    private static Formats parse(PlayerResponse response) {
        VideoStream[] bestVideos = new VideoStream[HEIGHTS.length];
        AudioStream opus = new AudioStream();
        AudioStream aac = new AudioStream();
        long durationMs = 0;

        for (Format format : response.getStreamingData().getAdaptiveFormatsList()) {
            String mimeType = format.getMimeType().toLowerCase(Locale.US);
            String url = format.getUrl();
            final long bytes = format.getContentLength() > 0 ? format.getContentLength() : contentLengthOf(url);

            if (mimeType.startsWith("video/")) {
                Matcher matcher = QUALITY_LABEL_HEIGHT.matcher(format.getQualityLabel());
                if (!matcher.find()) continue;
                final int index = indexOfHeight(Integer.parseInt(matcher.group(1)));
                if (index < 0) continue;
                Codec codec = Codec.fromMimeType(mimeType);
                if (codec == null) continue;

                final boolean hdr = VP9_PROFILE_2.matcher(mimeType).find() || AV1_TEN_BIT.matcher(mimeType).find();
                VideoStream stream = new VideoStream(format.getFps(), hdr, codec, format.getBitrate(), bytes);
                if (bestVideos[index] == null || stream.isBetterThan(bestVideos[index], HEIGHTS[index])) {
                    bestVideos[index] = stream;
                }
            } else if (mimeType.startsWith("audio/")) {
                // Multi-language videos list dubbed tracks as well. The original is marked in the url.
                if (urlHasParameter(url, "acont", "dubbed") || urlHasParameter(url, "drc", "1")) continue;
                final boolean original = urlHasParameter(url, "acont", "original");
                durationMs = Math.max(durationMs, format.getApproxDurationMs());

                if (mimeType.contains("opus")) {
                    opus.offer(format, bytes, original);
                } else if (mimeType.contains("mp4a")) {
                    aac.offer(format, bytes, original);
                }
            }
        }

        // An MP4 video gets AAC audio, a WebM one Opus.
        final long videoAudioBytes = aac.bytes > 0 ? aac.bytes : opus.bytes;
        VideoQuality[] videos = new VideoQuality[HEIGHTS.length];
        for (int i = 0; i < HEIGHTS.length; i++) {
            VideoStream stream = bestVideos[i];
            if (stream == null) continue;
            final long bytes = stream.bytes() > 0 && videoAudioBytes > 0 ? stream.bytes() + videoAudioBytes : 0;
            videos[i] = new VideoQuality(bytes, stream.fps(), stream.codec());
        }

        VideoDetails details = response.getVideoDetails();
        final long lengthSeconds = details.getLengthSeconds();
        if (durationMs <= 0) durationMs = lengthSeconds * 1000;
        final long mp3Bytes = durationMs * ExternalDownloaderPreference.QUICK_DOWNLOAD_AUDIO_KBPS / 8;

        int aacKbps = 0;
        if (aac.format != null) {
            final int bitrate = aac.format.getAverageBitrate() > 0
                    ? aac.format.getAverageBitrate()
                    : aac.format.getBitrate();
            aacKbps = Math.round(bitrate / 1000f);
        }

        return new Formats(videos, aac.bytes, aacKbps, mp3Bytes,
                details.getTitle(), details.getOwnerChannelName(), lengthSeconds);
    }

    private static int indexOfHeight(int height) {
        for (int i = 0; i < HEIGHTS.length; i++) {
            if (HEIGHTS[i] == height) return i;
        }
        return -1;
    }

    private static boolean urlHasParameter(String url, String name, String value) {
        // Some values are inside the url encoded xtags parameter.
        return url.contains(name + "=" + value) || url.contains(name + "%3D" + value);
    }

    private static long contentLengthOf(String url) {
        Matcher matcher = CONTENT_LENGTH_PARAMETER.matcher(url);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    /** YouTube's own JPG thumbnails, largest first. A missing size answers 404. */
    private static final String[] THUMBNAIL_NAMES = {"maxresdefault", "sddefault", "hqdefault"};

    /**
     * Network request, call off the main thread.
     *
     * @return Bytes of the largest JPG thumbnail of the video, 0 if unknown.
     */
    static long fetchThumbnailSize(String videoId) {
        for (String name : THUMBNAIL_NAMES) {
            HttpURLConnection connection = null;
            try {
                connection = Requester.openConnection("https://i.ytimg.com/vi/" + videoId + "/" + name + ".jpg");
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
                if (connection.getResponseCode() == Requester.HTTP_STATUS_CODE_SUCCESS) {
                    return Math.max(0, connection.getContentLengthLong());
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not read the thumbnail size of: " + videoId, ex);
                return 0;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return 0;
    }
}
