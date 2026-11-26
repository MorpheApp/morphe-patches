/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5277
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.youtube.innertube.ElementTexts.ContentImage;
import app.morphe.extension.youtube.innertube.ElementTexts.ElementModel;
import app.morphe.extension.youtube.innertube.ElementTexts.LockupViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.ThumbnailBadge;
import app.morphe.extension.youtube.innertube.ElementTexts.ThumbnailBadgeViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.ThumbnailBottomOverlayViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.ThumbnailOverlay;
import app.morphe.extension.youtube.innertube.ElementTexts.ThumbnailViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.VideoData;
import app.morphe.extension.youtube.innertube.ElementTexts.VideoThumbnail;
import app.morphe.extension.youtube.innertube.ElementTexts.VideoWithContextData;
import app.morphe.extension.youtube.innertube.ElementTexts.VideoWithContextModel;
import app.morphe.extension.youtube.innertube.ElementTexts.ViewModelHolder;
import app.morphe.extension.youtube.innertube.ElementTexts.YoutubeModel;

public class VideoDurationFilterTest {

    private static void assertSeconds(long expected, String text) {
        assertEquals(text, expected, VideoDurationFilter.parseDurationSeconds(text));
    }

    @Test
    public void parsesDurations() {
        assertSeconds(9, "0:09");
        assertSeconds(754, "12:34");
        assertSeconds(2741, "45:41");
        assertSeconds(10429, "2:53:49");
        assertSeconds(3723, "1:2:3");
        assertSeconds(2741, " 45:41 ");
    }

    @Test
    public void parsesDurationsWithPeriods() {
        assertSeconds(2669, "44.29");
        assertSeconds(5469, "1.31.09");
    }

    @Test
    public void parsesNonLatinDigits() {
        assertSeconds(2741, "٤٥:٤١");
    }

    @Test
    public void ignoresTextsThatAreNotDurations() {
        assertSeconds(0, "");
        assertSeconds(0, "LIVE");
        assertSeconds(0, "Scheduled");
        assertSeconds(0, "45");
        assertSeconds(0, "4K");
        assertSeconds(0, "1.2K");
        assertSeconds(0, "249 episodes");
        assertSeconds(0, "45 minutes, 41 seconds");
        assertSeconds(0, ":");
        assertSeconds(0, "1:");
        assertSeconds(0, "1:23:45:67");
    }

    @Test
    public void readsDurationOfClassicLayout() {
        assertEquals(2741, VideoDurationFilter.getDurationSeconds(ElementModel.newBuilder()
                .setVideoWithContextModel(VideoWithContextModel.newBuilder()
                        .setData(VideoWithContextData.newBuilder().setVideoData(VideoData.newBuilder()
                                .setThumbnail(VideoThumbnail.newBuilder().setTimestampText("45:41")))))
                .build()));
    }

    @Test
    public void readsDurationOfCardLayout() {
        ThumbnailBottomOverlayViewModel.Builder overlay = ThumbnailBottomOverlayViewModel.newBuilder();
        for (String text : new String[]{"4K", "45:41"}) {
            overlay.addBadges(ThumbnailBadge.newBuilder().setThumbnailBadgeViewModel(
                    ThumbnailBadgeViewModel.newBuilder().setText(text)));
        }
        LockupViewModel.Builder lockup = LockupViewModel.newBuilder().setContentImage(ContentImage.newBuilder()
                .setThumbnailViewModel(ThumbnailViewModel.newBuilder().addOverlays(
                        ThumbnailOverlay.newBuilder().setThumbnailBottomOverlayViewModel(overlay))));

        assertEquals(2741, VideoDurationFilter.getDurationSeconds(ElementModel.newBuilder()
                .setYoutubeModel(YoutubeModel.newBuilder().setViewModel(
                        ViewModelHolder.newBuilder().setLockupViewModel(lockup)))
                .build()));
    }

    @Test
    public void ignoresModelsWithoutDuration() {
        assertEquals(0, VideoDurationFilter.getDurationSeconds(ElementModel.getDefaultInstance()));
    }

    @Test
    public void comparesWithRange() {
        assertTrue(VideoDurationFilter.isOutsideRange(599, 10, 0));
        assertFalse(VideoDurationFilter.isOutsideRange(600, 10, 0));
        assertFalse(VideoDurationFilter.isOutsideRange(600, 0, 10));
        assertTrue(VideoDurationFilter.isOutsideRange(601, 0, 10));
        assertFalse(VideoDurationFilter.isOutsideRange(600, 10, 10));
        assertFalse(VideoDurationFilter.isOutsideRange(0, 10, 0));
        assertFalse(VideoDurationFilter.isOutsideRange(600, 0, 0));
        assertFalse(VideoDurationFilter.isOutsideRange(600, 0, Long.MAX_VALUE));
        assertTrue(VideoDurationFilter.isOutsideRange(600, Long.MAX_VALUE, 0));
    }
}
