package app.morphe.extension.youtube.patches;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CaptionSessionTest {
    private static final List<StableCaptionBlockPolicy.Block> SOURCE = List.of(
            new StableCaptionBlockPolicy.Block(0, 3000, "first source", true),
            new StableCaptionBlockPolicy.Block(4000, 7000, "second source", true),
            new StableCaptionBlockPolicy.Block(31000, 34000, "far source", true));
    static final class Jobs implements Executor {
        final Queue<Runnable> queue = new ArrayDeque<>();
        public void execute(Runnable r) { queue.add(r); }
        void run() { while (!queue.isEmpty()) queue.remove().run(); }
    }
    @Test public void outputsUseOneSourceClockAndOneBoundedDemand() {
        Jobs jobs = new Jobs(); List<String> requests = new ArrayList<>();
        var session = new CaptionSession(SOURCE, "en", "de", "tr",
                (from,to,texts) -> { requests.add(to+":"+texts.size()); return texts.stream().map(t -> to+":"+t).toList(); }, jobs, () -> {}, () -> {});
        session.demand(0);session.demand(0);assertEquals(1,jobs.queue.size());jobs.run();
        assertEquals(List.of("de:2","tr:2"),requests);
        var cue=session.snapshot(100);
        assertEquals(0,cue.start);assertEquals(3000,cue.end);
        assertEquals("de:first source",cue.primary);assertEquals("tr:first source",cue.secondary);
        assertSame(cue,session.snapshot(200));assertNull(session.snapshot(3500));
        session.demand(100);jobs.run();assertEquals(2,requests.size());
    }
    @Test public void latePayloadDoesNotFlashWithTooLittleReadingTime() {
        Jobs jobs=new Jobs();var session=new CaptionSession(SOURCE,"en","de","tr",
                (f,t,texts)->texts.stream().map(x->t+":"+x).toList(),jobs,()->{},()->{});
        session.demand(2000);assertEquals("first source",session.snapshot(2000).primary);jobs.run();
        assertEquals("first source",session.snapshot(2100).primary);
        assertEquals("de:second source",session.snapshot(4100).primary);
        assertEquals("de:first source",session.snapshot(500).primary);
    }
    @Test public void cancellationStopsSecondTargetAndLateDelivery() {
        Jobs jobs=new Jobs();AtomicInteger calls=new AtomicInteger(),updates=new AtomicInteger();CaptionSession[] holder=new CaptionSession[1];
        holder[0]=new CaptionSession(SOURCE,"en","de","tr",(f,t,texts)->{
            calls.incrementAndGet();holder[0].invalidate();return texts;},jobs,updates::incrementAndGet,()->fail("Cancellation is not failure"));
        holder[0].demand(0);jobs.run();assertEquals(1,calls.get());assertEquals(0,updates.get());assertNull(holder[0].snapshot(0));
    }
    @Test public void sameLanguageNeedsNoProviderAndDuplicateOutputIsNotRequested() {
        Jobs jobs=new Jobs();var session=new CaptionSession(SOURCE,"en","en","en",
                (f,t,texts)->{fail("No network for same-language output");return null;},jobs,()->{},()->{});
        session.demand(0);jobs.run();var cue=session.snapshot(100);assertEquals("first source",cue.primary);assertEquals("",cue.secondary);
    }
    @Test public void failurePreservesAvailablePrimaryAndDoesNotRetryEachFrame() {
        Jobs jobs=new Jobs();AtomicInteger failures=new AtomicInteger(),calls=new AtomicInteger();
        var session=new CaptionSession(SOURCE,"en","de","tr",(f,t,texts)->{calls.incrementAndGet();if(t.equals("tr"))throw new IOException("unavailable");return texts.stream().map(x->"de:"+x).toList();},jobs,()->{},failures::incrementAndGet);
        session.demand(0);jobs.run();var cue=session.snapshot(100);assertEquals("de:first source",cue.primary);assertEquals("",cue.secondary);
        session.demand(100);jobs.run();assertEquals(2,calls.get());assertEquals(1,failures.get());
    }
    @Test public void partialProviderResultCannotShiftCaptions() {
        Jobs jobs=new Jobs();var session=new CaptionSession(SOURCE,"en","de",null,
                (f,t,texts)->List.of("wrong count"),jobs,()->{},()->{});
        session.demand(0);jobs.run();assertEquals("first source",session.snapshot(100).primary);
        assertEquals("second source",session.snapshot(4100).primary);
    }
    @Test public void primaryFailureDoesNotSuppressAvailableSecondary() {
        Jobs jobs = new Jobs();
        var session = new CaptionSession(SOURCE, "en", "de", "tr", (from, to, texts) -> {
            if (to.equals("de")) throw new IOException("unavailable");
            return texts.stream().map(text -> "tr:" + text).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(0); jobs.run();
        var cue = session.snapshot(100);
        assertEquals("first source", cue.primary);
        assertEquals("tr:first source", cue.secondary);
    }
    @Test public void pausedCaptionCanAcceptLateTranslationWithoutAFlash() {
        Jobs jobs = new Jobs();
        var session = new CaptionSession(SOURCE, "en", "de", null,
                (from, to, texts) -> texts.stream().map(text -> "de:" + text).toList(), jobs, () -> {}, () -> {});
        session.demand(2900);
        assertEquals("first source", session.snapshot(2900, true).primary);
        jobs.run();
        assertEquals("de:first source", session.snapshot(2900, true).primary);
    }
    @Test public void originalAndTranslatedOutputsShareTheClockAcrossSeekAndClear() {
        Jobs jobs = new Jobs(); List<String> targets = new ArrayList<>();
        var session = new CaptionSession(SOURCE, "en", "en", "de", (from, to, texts) -> {
            targets.add(to); return texts.stream().map(text -> "de:" + text).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(0); jobs.run();
        var cue = session.snapshot(100);
        assertEquals(List.of("de"), targets);
        assertEquals("first source", cue.primary); assertEquals("de:first source", cue.secondary);
        assertEquals(0, cue.start); assertEquals(3000, cue.end);
        assertNull(session.snapshot(3500));
        assertEquals("second source", session.snapshot(4100).primary);
        assertEquals("first source", session.snapshot(100).primary);
    }
    @Test public void singleTranslatedOutputUsesTheSameSessionWithoutASecondRequest() {
        Jobs jobs = new Jobs(); AtomicInteger requests = new AtomicInteger();
        var session = new CaptionSession(SOURCE, "en", "ja", null, (from, to, texts) -> {
            assertEquals("ja", to); requests.incrementAndGet();
            return texts.stream().map(text -> "訳:" + text).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(0); jobs.run(); var cue = session.snapshot(4100);
        assertEquals(1, requests.get()); assertTrue(cue.complete);
        assertEquals(4000, cue.start); assertEquals(7000, cue.end);
        assertEquals("訳:second source", cue.primary); assertEquals("", cue.secondary);
    }
}
