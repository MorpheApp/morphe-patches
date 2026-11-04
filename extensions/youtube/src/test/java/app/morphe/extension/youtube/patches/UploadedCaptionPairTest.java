/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4376
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.util.List;
import org.junit.Test;

public class UploadedCaptionPairTest {
    private record NativeCue(String text) implements StableCaptionBlocks.Cue {
        public CharSequence patch_getOriginalCaptionVisible() {
            return text;
        }
        public StableCaptionBlocks.Frame patch_getBlockFrame() {
            return null;
        }
        public Object patch_copyBlockFrame(StableCaptionBlocks.Frame frame) {
            throw new UnsupportedOperationException();
        }
    }
    private StableCaptionBlocks.Timeline timeline(List<StableCaptionBlockPolicy.Block> blocks) {
        return new StableCaptionBlocks.Timeline() {
            public List<?> patch_getOriginalCaptions(long time) {
                var block = StableCaptionBlockPolicy.at(blocks, time);
                return List.of(new NativeCue(block == null ? "" : block.text));
            }
            public StableCaptionBlocks.State patch_getBlockState() {
                return null;
            }
            public StableCaptionBlocks.Timeline patch_mergeWith(StableCaptionBlocks.Timeline other) {
                throw new UnsupportedOperationException();
            }
            public List<Long> patch_getCaptionEventTimes() {
                var events = new java.util.TreeSet<Long>();
                for (var block : blocks) {
                    events.add(block.start);
                    events.add(block.end);
                }
                return List.copyOf(events);
            }
        };
    }
    private List<StableCaptionBlockPolicy.Block> read(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/uploaded/" + name + ".srv3")) {
            assertNotNull(in);
            var policy = StableCaptionBlockPolicy.parseManual(CaptionXml.read(in.readAllBytes()));
            assertNotNull(policy);
            return policy.sourceBlocks("en");
        }
    }
    private UploadedCaptionPair pair() throws Exception {
        var tr = read("first");
        var de = read("second");
        assertEquals(5, tr.size());
        assertEquals(4, de.size());
        return new UploadedCaptionPair(timeline(tr), timeline(de));
    }
    @Test
    public void usesUploadedTextInsteadOfTranslatedFallback() throws Exception {
        var f = pair().at(100, "machine Turkish", "machine German", 0);
        assertEquals("First original", f.primary());
        assertEquals("Second original", f.secondary());
    }
    @Test
    public void secondSurvivesFirstTrackGap() throws Exception {
        var f = pair().at(9430, "machine Turkish", "machine German", 0);
        assertEquals("", f.primary());
        assertTrue(f.secondary().startsWith("Second before gap"));
        assertEquals(5099, f.secondKey());
    }
    @Test
    public void eachLanguageChangesAtItsOwnBoundary() throws Exception {
        var p = pair();
        var before = p.at(9728, "", "", 0);
        var gap = p.at(9729, "", "", 0);
        var after = p.at(9730, "", "", 0);
        assertEquals(before.primary(), after.primary());
        assertEquals(before.firstKey(), after.firstKey());
        assertEquals("", gap.secondary());
        assertTrue(after.secondary().startsWith("Second after gap"));
        assertEquals(9730, after.secondKey());
    }
    @Test
    public void doesNotTranslateARealGapInAnExistingTrack() throws Exception {
        var f = pair().at(15501, "fake question", "invented German", 0);
        assertEquals("First question", f.primary());
        assertEquals("", f.secondary());
    }
    @Test
    public void missingTrackUsesOnlyThatSlotsFallback() throws Exception {
        var f = new UploadedCaptionPair(timeline(read("first")), null).at(100, "fake", "fallback German", 42);
        assertTrue(f.primary().startsWith("First original"));
        assertEquals("fallback German", f.secondary());
        assertEquals(42, f.secondKey());
        f = new UploadedCaptionPair(null, timeline(read("second"))).at(100, "fallback Turkish", "fake", 42);
        assertEquals("fallback Turkish", f.primary());
        assertTrue(f.secondary().startsWith("Second original"));
    }
    @Test
    public void seekBackDoesNotRetainAnotherCuesText() throws Exception {
        var p = pair();
        p.at(1_000_000, "", "", 0);
        assertTrue(p.at(5125, "", "", 0).primary().startsWith("First before gap"));
        assertEquals("", p.at(0, "fake", "fake", 0).primary());
    }
}
