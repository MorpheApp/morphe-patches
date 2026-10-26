package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import java.util.List;
import org.junit.Test;

public class ArtistLineCycleTest {
    private static final List<String> FRAMES = List.of("artist", "title", "variant");

    @Test public void minimumHoldsAnEarlyLyricChangeUntilItExpires() {
        ArtistLineCycle cycle = new ArtistLineCycle();
        assertEquals("artist", cycle.update(FRAMES, 1, 0, 1500, 3000));
        assertEquals("artist", cycle.update(FRAMES, 2, 500, 1500, 3000));
        assertEquals("artist", cycle.update(FRAMES, 2, 1499, 1500, 3000));
        assertEquals("title", cycle.update(FRAMES, 2, 1500, 1500, 3000));
    }

    @Test public void maximumAdvancesWithoutAnyLyricChange() {
        ArtistLineCycle cycle = new ArtistLineCycle();
        assertEquals("artist", cycle.update(FRAMES, "same lyric", 0, 1500, 3000));
        assertEquals("artist", cycle.update(FRAMES, "same lyric", 2999, 1500, 3000));
        assertEquals("title", cycle.update(FRAMES, "same lyric", 3000, 1500, 3000));
        assertEquals("variant", cycle.update(FRAMES, "same lyric", 6000, 1500, 3000));
    }

    @Test public void pollingFromBothSurfacesDoesNotDoubleAdvance() {
        ArtistLineCycle cycle = new ArtistLineCycle();
        cycle.update(FRAMES, 1, 0, 0, 3000);
        assertEquals("title", cycle.update(FRAMES, 2, 1500, 0, 3000));
        assertEquals("title", cycle.update(FRAMES, 2, 1500, 0, 3000));
        assertEquals("title", cycle.current(FRAMES));
    }

    @Test public void distinctTimedLinesAreNotCollapsedByTheirText() {
        ArtistLineCycle cycle = new ArtistLineCycle();
        LyricsLine first = new LyricsLine(0, "same lyric");
        LyricsLine second = new LyricsLine(2000, "same lyric");
        cycle.update(FRAMES, first, 0, 1500, 3000);
        assertEquals("title", cycle.update(FRAMES, second, 2000, 1500, 3000));
    }

    @Test public void singleFrameIgnoresAnimationTiming() {
        ArtistLineCycle cycle = new ArtistLineCycle();
        assertEquals("artist", cycle.update(List.of("artist"), 1, 0, 1500, 3000));
        assertEquals("artist", cycle.update(List.of("artist"), 2, 9000, 1500, 3000));
    }

    @Test public void editingFramesAndChangingTracksResetTheCycle() {
        ArtistLineCycle cycle = new ArtistLineCycle();
        cycle.update(FRAMES, 1, 0, 1500, 3000);
        cycle.update(FRAMES, 2, 2000, 1500, 3000);
        assertEquals("new", cycle.update(List.of("new", "other"), 2, 2500, 1500, 3000));
        cycle.reset();
        assertEquals("artist", cycle.update(FRAMES, 1, 3000, 1500, 3000));
    }
}
