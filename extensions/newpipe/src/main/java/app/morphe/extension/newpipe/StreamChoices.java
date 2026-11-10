/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */
package app.morphe.extension.newpipe;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds downloadable choices from the extractor's progressive HTTP streams. */
public final class StreamChoices {
    private StreamChoices() {
    }

    /** The second URL is present when video and audio need to be combined. */
    public record Choice(String label, String primaryUrl, String secondaryUrl,
                         String mimeType, String extension) {
    }

    private static boolean usable(Stream stream) {
        return stream.isUrl() && stream.getContent() != null && !stream.getContent().isEmpty()
                && stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP;
    }

    public static List<Choice> from(StreamInfo info) {
        List<Choice> result = new ArrayList<>();
        for (VideoStream video : info.getVideoStreams()) {
            if (!usable(video) || video.getFormat() == null) continue;
            MediaFormat format = video.getFormat();
            result.add(new Choice(video.getResolution() + " " + format.name,
                    video.getContent(), null, format.mimeType, format.suffix));
        }
        for (VideoStream video : info.getVideoOnlyStreams()) {
            // Android's MP4 muxer supports the MPEG-4/AAC pair used by NewPipe.
            if (!usable(video) || video.getFormat() != MediaFormat.MPEG_4) continue;
            AudioStream audio = info.getAudioStreams().stream()
                    .filter(candidate -> usable(candidate) && candidate.getFormat() == MediaFormat.M4A)
                    .max(Comparator.comparingInt((AudioStream candidate) ->
                                    candidate.getAudioTrackType() == AudioTrackType.ORIGINAL ? 1 : 0)
                            .thenComparingInt(AudioStream::getAverageBitrate)).orElse(null);
            if (audio != null) {
                result.add(new Choice(video.getResolution() + " " + MediaFormat.MPEG_4.name,
                        video.getContent(), audio.getContent(), "video/mp4", "mp4"));
            }
        }
        for (AudioStream audio : info.getAudioStreams()) {
            if (!usable(audio) || audio.getFormat() == null) continue;
            MediaFormat format = audio.getFormat();
            String track = audio.getAudioTrackName();
            result.add(new Choice("Audio " + audio.getAverageBitrate() + " kbps " + format.name
                    + (track == null || track.isBlank() ? "" : " - " + track),
                    audio.getContent(), null, format.mimeType, format.suffix));
        }
        return result;
    }
}
