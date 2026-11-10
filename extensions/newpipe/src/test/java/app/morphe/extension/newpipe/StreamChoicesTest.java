package app.morphe.extension.newpipe;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;

import static org.junit.Assert.*;

public class StreamChoicesTest {
    private static VideoStream video(String url, boolean videoOnly, MediaFormat format,
                                     DeliveryMethod delivery) {
        return new VideoStream.Builder().setId(url).setContent(url, true).setMediaFormat(format)
                .setDeliveryMethod(delivery).setResolution("1080p").setIsVideoOnly(videoOnly).build();
    }

    private static AudioStream audio(String url, MediaFormat format, int bitrate) {
        return new AudioStream.Builder().setId(url).setContent(url, true).setMediaFormat(format)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP).setAverageBitrate(bitrate).build();
    }

    private static StreamInfo info(List<VideoStream> combined, List<VideoStream> videoOnly,
                                   List<AudioStream> audio) {
        StreamInfo info = new StreamInfo(0, "id", "https://example.test", StreamType.VIDEO_STREAM,
                "name", "id", 0);
        info.setVideoStreams(combined);
        info.setVideoOnlyStreams(videoOnly);
        info.setAudioStreams(audio);
        return info;
    }

    @Test
    public void pairsMp4WithHighestBitrateM4aAndKeepsAudioChoices() {
        StreamInfo info = info(List.of(), List.of(video("https://video", true, MediaFormat.MPEG_4,
                DeliveryMethod.PROGRESSIVE_HTTP)), List.of(audio("https://low", MediaFormat.M4A, 64),
                audio("https://high", MediaFormat.M4A, 128)));
        List<StreamChoices.Choice> choices = StreamChoices.from(info);
        assertEquals(3, choices.size());
        assertEquals("https://video", choices.get(0).primaryUrl());
        assertEquals("https://high", choices.get(0).secondaryUrl());
        assertNull(choices.get(1).secondaryUrl());
    }

    @Test
    public void skipsAdaptiveVideoWithoutCompatibleAudioAndNonProgressiveStreams() {
        StreamInfo info = info(List.of(video("https://hls", false, MediaFormat.MPEG_4,
                        DeliveryMethod.HLS)),
                List.of(video("https://video", true, MediaFormat.MPEG_4, DeliveryMethod.PROGRESSIVE_HTTP)),
                List.of(audio("https://opus", MediaFormat.WEBMA_OPUS, 128)));
        List<StreamChoices.Choice> choices = StreamChoices.from(info);
        assertEquals(1, choices.size());
        assertEquals("https://opus", choices.get(0).primaryUrl());
    }

    @Test
    public void keepsCombinedVideoAsSingleFile() {
        StreamInfo info = info(List.of(video("https://combined", false, MediaFormat.MPEG_4,
                DeliveryMethod.PROGRESSIVE_HTTP)), List.of(), List.of());
        StreamChoices.Choice choice = StreamChoices.from(info).get(0);
        assertEquals("https://combined", choice.primaryUrl());
        assertNull(choice.secondaryUrl());
        assertEquals("mp4", choice.extension());
    }

    @Test
    public void prefersOriginalAudioOverHigherBitrateDub() {
        AudioStream original = new AudioStream.Builder().setId("original")
                .setContent("https://original", true).setMediaFormat(MediaFormat.M4A)
                .setAudioTrackType(AudioTrackType.ORIGINAL).setAverageBitrate(64).build();
        AudioStream dubbed = new AudioStream.Builder().setId("dubbed")
                .setContent("https://dubbed", true).setMediaFormat(MediaFormat.M4A)
                .setAudioTrackType(AudioTrackType.DUBBED).setAverageBitrate(128).build();
        StreamInfo info = info(List.of(), List.of(video("https://video", true, MediaFormat.MPEG_4,
                DeliveryMethod.PROGRESSIVE_HTTP)), List.of(dubbed, original));
        assertEquals("https://original", StreamChoices.from(info).get(0).secondaryUrl());
    }
}
