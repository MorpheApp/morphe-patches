/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.AudioTrack;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.CaptionTrack;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.patches.BaseInAppDownloadsPatch;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * The DASH streams of a video, as the download clients answer with them.
 * Each stream is a single file with an index, so any part of it can be fetched with a range request.
 */
final class CastStreams {

    static final class Stream {
        final int itag;
        final String url;
        /**
         * Such as video/mp4 or audio/webm.
         */
        final String mimeType;
        final String codecs;
        final int width;
        final int height;
        /**
         * Quality of the video, such as 1080 for 1080p. The short side of the video, so a portrait video
         * of 1080 by 1920 is 1080p, as YouTube names it.
         */
        final int quality;
        /**
         * 10-bit HDR video, which looks washed out on SDR screens and which not every device decodes.
         */
        final boolean hdr;
        final int fps;
        final int bitrate;
        final long contentLength;
        final long initEnd;
        final long indexStart;
        final long indexEnd;
        final int audioSampleRate;

        private Stream(Format format) {
            itag = format.getItag();
            url = format.getUrl();
            String mime = format.getMimeType();
            final int separator = mime.indexOf(';');
            mimeType = (separator < 0 ? mime : mime.substring(0, separator)).trim();
            Matcher matcher = CODECS_PATTERN.matcher(mime);
            codecs = matcher.find() ? matcher.group(1) : "";
            width = format.getWidth();
            height = format.getHeight();
            quality = quality(format.getQualityLabel(), width, height);
            hdr = BaseInAppDownloadsPatch.isHdrCodec(codecs);
            fps = format.getFps();
            bitrate = format.getBitrate();
            contentLength = format.getContentLength();
            initEnd = format.getInitRange().getEnd();
            indexStart = format.getIndexRange().getStart();
            indexEnd = format.getIndexRange().getEnd();
            audioSampleRate = (int) format.getAudioSampleRate();
        }

        boolean isVideo() {
            return mimeType.startsWith("video/");
        }

        /**
         * @param qualityLabel Such as "1080p60" or "2160p HDR". Wide videos are named by their width,
         *                     such as 2160p for 3840 by 1608.
         */
        private static int quality(String qualityLabel, int width, int height) {
            Matcher matcher = QUALITY_LABEL_PATTERN.matcher(qualityLabel);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            return width > 0 && height > 0 ? Math.min(width, height) : height;
        }

        boolean isMp4() {
            return mimeType.endsWith("/mp4");
        }

        boolean isH264() {
            return codecs.startsWith("avc1");
        }

        boolean isAac() {
            return codecs.startsWith("mp4a");
        }

        @Override
        public String toString() {
            return itag + " " + mimeType + " " + codecs + (isVideo() ? " " + quality + "p" + fps : "") + " " + bitrate;
        }
    }

    /**
     * The video has no streams that can be cast, such as a live stream.
     */
    static final class NoStreamsException extends Exception {
        NoStreamsException(String message) {
            super(message);
        }
    }

    private static final Pattern CODECS_PATTERN = Pattern.compile("codecs=\"([^\"]+)\"");
    private static final Pattern QUALITY_LABEL_PATTERN = Pattern.compile("^(\\d+)p");
    /**
     * Url parameter with the tags of a stream, such as "drc=1" for audio with dynamic range compression.
     */
    private static final String XTAGS_PARAMETER = "xtags";
    private static final String DRC_TAG = "drc=1";
    /**
     * The id of the audio track of the original language ends with this, such as "en.4".
     */
    private static final String ORIGINAL_AUDIO_TRACK_SUFFIX = ".4";
    /**
     * Highest H.264 stream YouTube offers, and what every Cast device decodes.
     */
    static final int MAX_H264_HEIGHT = 1080;

    final String videoId;
    final String title;
    final double durationSeconds;
    final String userAgent;
    /**
     * Streams with a url and index, video highest quality first, then audio highest bitrate first.
     */
    final List<Stream> streams;
    /**
     * Captions of the video, in the order of the app.
     */
    final List<Captions> captions;

    /**
     * A caption track.
     *
     * @param vssId Such as ".en", or "a.en" for captions made by speech recognition.
     */
    record Captions(String vssId, String languageCode, String url) {
    }

    private CastStreams(String videoId, String title, double durationSeconds, String userAgent, List<Stream> streams,
                        List<Captions> captions) {
        this.videoId = videoId;
        this.title = title;
        this.durationSeconds = durationSeconds;
        this.userAgent = userAgent;
        this.streams = streams;
        this.captions = captions;
    }

    /**
     * Fetches the streams like the in-app downloads do. Blocks the calling thread.
     */
    static CastStreams fetch(String videoId) throws IOException, NoStreamsException {
        StreamingDataRequest.StreamData data = StreamingDataRequest.fetchRequestForDownload(
                videoId,
                BaseInAppDownloadsPatch.DOWNLOAD_CLIENTS,
                BaseInAppDownloadsPatch.DOWNLOAD_CLIENTS.get(0)
        ).getStream();
        if (data == null) {
            throw new NoStreamsException("No client answered with streams");
        }

        PlayerResponse response = PlayerResponse.parseFrom(data.streamingData());
        List<Format> formats = response.getStreamingData().getAdaptiveFormatsList();
        final String audioTrackId = audioTrackId(formats);
        List<Stream> streams = new ArrayList<>();
        Set<Integer> itags = new HashSet<>();
        double duration = response.getVideoDetails().getLengthSeconds();
        for (Format format : formats) {
            // Live streams have no urls or indexes, only manifests.
            if (format.getUrl().isEmpty() || !format.hasIndexRange() || format.getContentLength() <= 0
                    || isDrcAudio(format)
                    || (format.getMimeType().startsWith("audio/") && !format.getAudioTrack().getId().equals(audioTrackId))
                    || !itags.add(format.getItag())) {
                continue;
            }
            if (format.getApproxDurationMs() > 0) {
                duration = Math.max(duration, format.getApproxDurationMs() / 1000.0);
            }
            streams.add(new Stream(format));
        }
        if (streams.stream().noneMatch(Stream::isVideo) || streams.stream().allMatch(Stream::isVideo)) {
            throw new NoStreamsException("No video and audio streams with an index");
        }
        streams.sort(Comparator.comparing((Stream stream) -> !stream.isVideo())
                .thenComparing(Comparator.comparingInt((Stream stream) -> stream.quality).reversed())
                .thenComparing(Comparator.comparingInt((Stream stream) -> stream.bitrate).reversed()));

        String title = response.getVideoDetails().getTitle();
        List<Captions> captions = new ArrayList<>();
        for (CaptionTrack track : response.getCaptions().getPlayerCaptionsTracklistRenderer().getCaptionTracksList()) {
            if (!track.getBaseUrl().isEmpty()) {
                captions.add(new Captions(track.getVssId(), track.getLanguageCode(), track.getBaseUrl()));
            }
        }
        return new CastStreams(videoId, title.isEmpty() ? videoId : title, duration,
                data.client().getUserAgent(), Collections.unmodifiableList(streams),
                Collections.unmodifiableList(captions));
    }

    /**
     * @return If the stream is audio with dynamic range compression, which is quieter. The other audio stream
     *         of the same quality is cast instead.
     */
    private static boolean isDrcAudio(Format format) {
        if (!format.getMimeType().startsWith("audio/")) {
            return false;
        }
        @Nullable String xtags = Uri.parse(format.getUrl()).getQueryParameter(XTAGS_PARAMETER);
        if (xtags == null) {
            return false;
        }
        for (String tag : xtags.split(":")) {
            if (tag.equals(DRC_TAG)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param vssId Captions the app shows, or empty for none.
     * @return Index of the captions in {@link #captions}, or -1 for none. Captions of the same language are used
     * if the exact captions are not there.
     */
    int captionsIndex(String vssId) {
        if (vssId.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < captions.size(); i++) {
            if (captions.get(i).vssId().equals(vssId)) {
                return i;
            }
        }
        // Such as "a.en" for "en".
        String language = vssId.substring(vssId.indexOf('.') + 1);
        for (int i = 0; i < captions.size(); i++) {
            if (captions.get(i).languageCode().equals(language)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The audio track to cast, of videos with more than one. Every stream of a track has the same id, and
     * streams of other tracks can have the same itag.
     *
     * @return The original audio if original audio is forced, else the default audio of the app, else the original
     * audio. Empty if the video has one audio track.
     */
    private static String audioTrackId(List<Format> formats) {
        String originalId = "";
        String defaultId = "";
        for (Format format : formats) {
            AudioTrack track = format.getAudioTrack();
            if (track.getId().endsWith(ORIGINAL_AUDIO_TRACK_SUFFIX)) {
                originalId = track.getId();
            }
            if (track.getAudioIsDefault()) {
                defaultId = track.getId();
            }
        }

        final String id = SharedYouTubeSettings.FORCE_ORIGINAL_AUDIO.get() || defaultId.isEmpty()
                ? originalId
                : defaultId;
        if (!id.isEmpty()) {
            Logger.printDebug(() -> "Audio track: " + id);
            return id;
        }
        // No track is marked: cast the first track.
        for (Format format : formats) {
            if (format.getMimeType().startsWith("audio/")) {
                return format.getAudioTrack().getId();
            }
        }
        return "";
    }

    @Nullable
    Stream get(int itag) {
        for (Stream stream : streams) {
            if (stream.itag == itag) {
                return stream;
            }
        }
        return null;
    }

    /**
     * Highest H.264 video in MP4 up to the height, for receivers that only play a single file.
     */
    @Nullable
    Stream progressiveVideo(int maxHeight) {
        final int limit = Math.min(maxHeight, MAX_H264_HEIGHT);
        for (Stream stream : streams) {
            if (stream.isVideo() && stream.isMp4() && stream.isH264() && stream.quality <= limit) {
                return stream;
            }
        }
        return null;
    }

    @Nullable
    Stream progressiveAudio() {
        for (Stream stream : streams) {
            if (!stream.isVideo() && stream.isMp4() && stream.isAac()) {
                return stream;
            }
        }
        return null;
    }

    /**
     * MPEG-DASH manifest of the streams up to the height and with the codecs. The receiver switches between
     * qualities by bandwidth. Stream urls are relative to the manifest.
     * <p>
     * The video streams are of a single codec family (see {@link #videoGroup}): with both H.264 and VP9 in the
     * manifest, the LG webOS receiver plays the lowest H.264 quality and never switches up.
     */
    String dashManifest(int maxHeight, CastCodecs codecs) {
        StringBuilder mpd = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\"")
                .append(" type=\"static\" minBufferTime=\"PT2S\" mediaPresentationDuration=\"")
                .append(String.format(Locale.US, "PT%.3fS", durationSeconds)).append("\">\n<Period>\n");
        int adaptationSet = 0;
        @Nullable String videoGroup = videoGroup(maxHeight, codecs);
        // One adaptation set per media type, container and codec family, as players do not switch between them.
        for (String type : new String[]{"video", "audio"}) {
            List<String> groups = new ArrayList<>();
            for (Stream stream : streams) {
                if (stream.mimeType.startsWith(type) && included(stream, maxHeight, codecs)) {
                    String group = group(stream);
                    if (!groups.contains(group) && (!stream.isVideo() || group.equals(videoGroup))) {
                        groups.add(group);
                    }
                }
            }
            for (String group : groups) {
                String mimeType = group.substring(0, group.indexOf(' '));
                mpd.append("<AdaptationSet id=\"").append(adaptationSet++).append("\" contentType=\"").append(type)
                        .append("\" mimeType=\"").append(mimeType).append("\" subsegmentAlignment=\"true\">\n");
                for (Stream stream : streams) {
                    if (!group.equals(group(stream)) || !included(stream, maxHeight, codecs)) {
                        continue;
                    }
                    mpd.append(" <Representation id=\"").append(stream.itag).append("\" codecs=\"").append(stream.codecs)
                            .append("\" bandwidth=\"").append(stream.bitrate).append('"');
                    if (stream.isVideo()) {
                        mpd.append(" width=\"").append(stream.width).append("\" height=\"").append(stream.height).append('"');
                        if (stream.fps > 0) {
                            mpd.append(" frameRate=\"").append(stream.fps).append('"');
                        }
                    } else if (stream.audioSampleRate > 0) {
                        mpd.append(" audioSamplingRate=\"").append(stream.audioSampleRate).append('"');
                    }
                    mpd.append(">\n  <BaseURL>").append(CastStreamServer.streamPath(stream.itag)).append("</BaseURL>\n")
                            .append("  <SegmentBase indexRange=\"").append(stream.indexStart).append('-').append(stream.indexEnd)
                            .append("\"><Initialization range=\"0-").append(stream.initEnd).append("\"/></SegmentBase>\n")
                            .append(" </Representation>\n");
                }
                mpd.append("</AdaptationSet>\n");
            }
        }
        return mpd.append("</Period>\n</MPD>\n").toString();
    }

    /**
     * The video container and codec family of the manifest: the one with the highest quality up to the height,
     * and on equal height the codec most devices decode (H.264, then VP9, then AV1).
     */
    @Nullable
    private String videoGroup(int maxHeight, CastCodecs codecs) {
        Stream best = null;
        for (Stream stream : streams) {
            if (stream.isVideo() && included(stream, maxHeight, codecs) && (best == null
                    || stream.quality > best.quality
                    || (stream.quality == best.quality && codecRank(stream.codecs) < codecRank(best.codecs)))) {
                best = stream;
            }
        }
        return best == null ? null : group(best);
    }

    private static int codecRank(String codecs) {
        if (codecs.startsWith("avc1")) {
            return 0;
        }
        if (codecs.startsWith("vp9") || codecs.startsWith("vp09")) {
            return 1;
        }
        return 2;
    }

    private static String group(Stream stream) {
        return stream.mimeType + " " + codecFamily(stream.codecs);
    }

    private static boolean included(Stream stream, int maxHeight, CastCodecs codecs) {
        return !stream.isVideo() || (stream.quality <= maxHeight && !stream.hdr && codecs.allows(stream.codecs));
    }

    private static String codecFamily(String codecs) {
        final int dot = codecs.indexOf('.');
        return dot < 0 ? codecs : codecs.substring(0, dot);
    }
}
