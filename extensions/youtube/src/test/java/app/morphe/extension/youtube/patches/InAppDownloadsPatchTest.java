/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.youtube.innertube.Offline.OfflineAudioTrack;
import app.morphe.extension.youtube.innertube.Offline.OfflineStreamFormat;
import app.morphe.extension.youtube.innertube.Offline.OfflineStreamingData;

public class InAppDownloadsPatchTest {

    private static OfflineStreamFormat format(String mimeType) {
        return OfflineStreamFormat.newBuilder().setMimeType(mimeType).build();
    }

    @Test
    public void keepsEverySupportedAudioTrackSavable() {
        OfflineStreamingData original = OfflineStreamingData.newBuilder()
                .addAdaptiveFormats(format("video/mp4; codecs=\\\"avc1.640028\\\""))
                .addAdaptiveFormats(format("audio/mp4; codecs=\\\"mp4a.40.2\\\""))
                .addAdaptiveFormats(format("audio/mp4; codecs=\\\"mp4a.40.2\\\""))
                .addAdaptiveFormats(format("audio/webm; codecs=\\\"opus\\\""))
                .addAdaptiveFormats(format(""))
                .build();

        OfflineStreamingData result = InAppDownloadsPatch.savableStreamingData(original);

        assertEquals(3, InAppDownloadsPatch.countSavableFormats(result));
        assertTrue(result.getAdaptiveFormats(0).getOfflineEligible());
        assertTrue(result.getAdaptiveFormats(1).getOfflineEligible());
        assertTrue(result.getAdaptiveFormats(2).getOfflineEligible());
        assertFalse(result.getAdaptiveFormats(3).getOfflineEligible());
        assertFalse(result.getAdaptiveFormats(4).getOfflineEligible());
    }

    @Test
    public void keepsOnlyTheExplicitlySelectedAudioTrackWhenMetadataMatches() {
        OfflineStreamFormat video = format("video/mp4; codecs=\\\"avc1.640028\\\"");
        OfflineStreamFormat english = format("audio/mp4")
                .toBuilder()
                .setAudioTrack(OfflineAudioTrack.newBuilder().setId("en-us.10"))
                .build();
        OfflineStreamFormat arabic = format("audio/mp4")
                .toBuilder()
                .setAudioTrack(OfflineAudioTrack.newBuilder().setId("ar.4"))
                .build();

        OfflineStreamingData original = OfflineStreamingData.newBuilder()
                .addAdaptiveFormats(video)
                .addAdaptiveFormats(english)
                .addAdaptiveFormats(arabic)
                .build();

        OfflineStreamingData result = InAppDownloadsPatch.savableStreamingData(original, "ar.4");

        assertTrue(result.getAdaptiveFormats(0).getOfflineEligible());
        assertFalse(result.getAdaptiveFormats(1).getOfflineEligible());
        assertTrue(result.getAdaptiveFormats(2).getOfflineEligible());
    }

    @Test
    public void fallsBackWhenSelectedAudioTrackIsNotPresent() {
        OfflineStreamingData original = OfflineStreamingData.newBuilder()
                .addAdaptiveFormats(format("audio/mp4").toBuilder()
                        .setAudioTrack(OfflineAudioTrack.newBuilder().setId("en-us.10"))
                        .build())
                .build();

        OfflineStreamingData result = InAppDownloadsPatch.savableStreamingData(original, "ar.4");

        assertTrue(result.getAdaptiveFormats(0).getOfflineEligible());
    }

    @Test
    public void preservesXtagsAudioTrackMetadata() {
        OfflineStreamFormat audioTrack = format("audio/mp4")
                .toBuilder()
                .setXtags("lang=ar")
                .setAudioTrack(OfflineAudioTrack.newBuilder()
                        .setId("ar.4")
                        .setDisplayName("Arabic"))
                .build();
        OfflineStreamingData original = OfflineStreamingData.newBuilder()
                .addAdaptiveFormats(audioTrack)
                .build();

        OfflineStreamingData result = InAppDownloadsPatch.savableStreamingData(original);

        assertEquals("lang=ar", result.getAdaptiveFormats(0).getXtags());
        assertEquals("ar.4", result.getAdaptiveFormats(0).getAudioTrack().getId());
        assertTrue(result.getAdaptiveFormats(0).getOfflineEligible());
    }

    @Test
    public void doesNotMutateTheOriginalStreamingData() {
        OfflineStreamingData original = OfflineStreamingData.newBuilder()
                .addAdaptiveFormats(format("audio/mp4"))
                .build();

        OfflineStreamingData result = InAppDownloadsPatch.savableStreamingData(original);

        assertFalse(original.getAdaptiveFormats(0).getOfflineEligible());
        assertTrue(result.getAdaptiveFormats(0).getOfflineEligible());
    }
}
