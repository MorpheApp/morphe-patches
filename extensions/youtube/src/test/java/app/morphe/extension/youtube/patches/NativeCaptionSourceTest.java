/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4376
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.stream.LongStream;
import static org.junit.Assert.*;

public class NativeCaptionSourceTest {
    record Cue(String visible, String full, boolean shown) implements StableCaptionBlocks.Cue {
        Cue(String visible, String full) { this(visible, full, true); }
        public boolean patch_isCaptionVisible() { return shown; }
        public int patch_getCaptionId() { return 0; }
        public CharSequence patch_getOriginalCaptionVisible() { return visible; }
        public CharSequence patch_getOriginalCaptionFull() { return full; }
        public StableCaptionBlocks.Frame patch_getBlockFrame() { return null; }
        public Object patch_copyBlockFrame(StableCaptionBlocks.Frame f) { throw new UnsupportedOperationException(); }
    }
    static StableCaptionBlocks.Timeline timeline(List<Long> times, LongFunction<List<?>> sample) {
        return new StableCaptionBlocks.Timeline() {
            public List<?> patch_getOriginalCaptions(long t) { return sample.apply(t); }
            public StableCaptionBlocks.State patch_getBlockState() { return null; }
            public void patch_setBlockState(StableCaptionBlocks.State s) { throw new UnsupportedOperationException(); }
            public StableCaptionBlocks.Timeline patch_mergeWith(StableCaptionBlocks.Timeline t) { throw new UnsupportedOperationException(); }
            public List<Long> patch_getCaptionEventTimes() { return times; }
        };
    }
    private StableCaptionBlockPolicy read(StableCaptionBlocks.Timeline timeline) {
        return StableCaptionBlockPolicy.fromNative(timeline, () -> true);
    }
    @Test public void translatesWholeNativePhraseInsteadOfWordRevealFragments() {
        var t=timeline(List.of(0L,100L,500L,2000L),time->List.of(new Cue(time<500?"Hello":"Hello world", "Hello world")));
        var p=read(t);assertNotNull(p);
        var blocks=p.sourceBlocks("en");assertEquals(1,blocks.size());
        assertEquals("Hello world",blocks.get(0).text);assertEquals(0,blocks.get(0).start);assertEquals(2000,blocks.get(0).end);
    }
    @Test public void keepsGapsAndDeduplicatesOnlySimultaneousNativeEffectText() {
        var t=timeline(List.of(0L,1000L,1100L,2100L),time->time==1000?List.of():List.of(new Cue("yes","yes"),new Cue("yes","yes")));
        var p=read(t);assertNotNull(p);
        var blocks=p.sourceBlocks("en");assertEquals(2,blocks.size());assertEquals("yes",blocks.get(0).text);
        assertNull(StableCaptionBlockPolicy.at(blocks,1050));assertEquals(1100,blocks.get(1).start);
    }
    @Test public void keepsConcurrentWindowsAndUnicodeWithoutRemovingJoiners() {
        String arabic="می\u200cروم";String emoji="👩\u200d💻";
        var t=timeline(List.of(10L,1500L),time->List.of(new Cue("","\u200b"+arabic+" "+emoji+"\u200b"),new Cue("","日本語")));
        var p=read(t);assertNotNull(p);
        assertEquals(arabic+" "+emoji+" 日本語",p.sourceBlocks("fa").get(0).text);
    }
    @Test public void emptyOrInvalidNativeEventsStayUnsupported() {
        assertNull(read(timeline(List.of(),t->List.of())));
        assertNull(read(timeline(List.of(1000L,0L),t->List.of(new Cue("x","x")))));
        assertNull(read(timeline(List.of(0L,1000L),t->List.of(new Cue("","\u200b")))));
    }
    @Test public void nativeSamplingHasABoundedWorkBudget() {
        List<Long> times=LongStream.range(0,20001).boxed().toList();
        var t=timeline(times,time->Collections.nCopies(100,new Cue("x","x")));
        assertNull(read(t));
    }
    @Test public void laterWholePhrasesAlsoCoalesceAcrossRevealEvents() {
        var t=timeline(List.of(0L,1000L,1200L,1500L,2500L),time->List.of(new Cue("word",time<1000?"first phrase":"second phrase")));
        var p=read(t);assertNotNull(p);var blocks=p.sourceBlocks("en");
        assertEquals(2,blocks.size());assertEquals("second phrase",blocks.get(1).text);
        assertEquals(1000,blocks.get(1).start);assertEquals(2500,blocks.get(1).end);
    }
    @Test public void neitherUploadedLanguageUsesTheExistingSessionForBothOutputs() {
        var policy=read(timeline(List.of(0L,2000L),t->List.of(new Cue("word","complete phrase"))));
        List<String> requested=new ArrayList<>();
        var session=new CaptionSession(policy.sourceBlocks("en"),"en","tr","de",(from,to,texts)->{
            requested.add(to);assertEquals(List.of("complete phrase"),texts);
            return texts.stream().map(text->to+":"+text).toList();
        },Runnable::run,()->{},()->{},true,true);
        session.demand(100);var frame=session.snapshot(100,true);
        assertEquals(List.of("tr","de"),requested);assertEquals("tr:complete phrase", Objects.requireNonNull(frame).primary);
        assertEquals("de:complete phrase",frame.secondary);assertTrue(frame.complete);
    }
    @Test public void staleSourceStopsSamplingWithoutPublishingAPartialPlan() {
        var samples=new AtomicInteger();
        var t=timeline(LongStream.range(0,130).boxed().toList(),time->{samples.incrementAndGet();return List.of(new Cue("word","phrase"));});
        assertNull(StableCaptionBlockPolicy.fromNative(t,()->samples.get()<64));
        assertEquals(64,samples.get());
    }
    @Test public void alreadyCancelledSourceIsNeverSampled() {
        var t=timeline(List.of(0L,2000L),time->{throw new AssertionError("stale source sampled");});
        assertNull(StableCaptionBlockPolicy.fromNative(t,()->false));
    }
    @Test public void oversizedNativeTextStaysBoundedBeforeConversion() {
        var t=timeline(List.of(0L,2000L),time->List.of(new Cue("x","x".repeat(4097))));
        assertNull(read(t));
    }
    @Test public void hiddenNativeWindowsAreNotTranslationSources() {
        var t=timeline(List.of(0L,2000L),time->List.of(new Cue("hidden","hidden",false),new Cue("visible","visible",true)));
        assertEquals("visible",read(t).sourceBlocks("en").get(0).text);
    }
}
