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
    @Test public void fastPrefetchCannotCycleThroughEvictedTranslations() {
        assertBoundedPrefetch(1500, 4);
    }
    @Test public void denseAuthoredCuesCannotCycleAtNormalSpeed() {
        assertBoundedPrefetch(300, 1);
    }
    private void assertBoundedPrefetch(int duration, float speed) {
        List<StableCaptionBlockPolicy.Block> cues = new ArrayList<>();
        for (int i = 0; i < 70; i++) cues.add(new StableCaptionBlockPolicy.Block(
                (long) i * duration, (long) (i + 1) * duration, "cue" + i, true));
        AtomicInteger calls = new AtomicInteger();
        var session = new CaptionSession(cues, "en", "de", null, (from, to, texts) -> {
            assertTrue("Prefetch must finish without revisiting evicted blocks", calls.incrementAndGet() < 30);
            return texts.stream().map(text -> "de:" + text).toList();
        }, Runnable::run, () -> {}, () -> {});
        session.demand(0, speed);
        assertTrue(session.isActive());
        assertEquals("de:cue0", session.snapshot(0).primary);
        session.demand(6L * duration, speed);
        assertEquals("de:cue69", session.snapshot(69L * duration).primary);
        assertTrue(calls.get() <= 15);
    }
    @Test public void nativeCueOutsidePlanningHintStillUsesSelectedLanguages() {
        String text = "on my phone okay so you might get this message as you can see we have a got";
        var source = List.of(new StableCaptionBlockPolicy.Block(116799, 122159, text, false));
        for (String secondary : new String[]{null, "fr"}) {
            var session = new CaptionSession(source, "en", "de", secondary,
                    (from, to, texts) -> texts.stream().map(value -> to + ":" + value).toList(),
                    Runnable::run, () -> {}, () -> fail("Valid native cue must be translated"));
            session.demand(116803);
            var cue = session.snapshot(116803);
            assertEquals("de:" + text, cue.primary);
            assertEquals(secondary == null ? "" : "fr:" + text, cue.secondary);
            assertTrue(cue.complete);
            assertEquals(116799, cue.start);
            assertEquals(122159, cue.end);
        }
    }

    @Test public void oversizedNativeCueIsNotSentToProvider() {
        String text = "\uD83D\uDE00".repeat(StableCaptionBlockPolicy.MAX_BLOCK_CHARS + 1);
        var session = new CaptionSession(List.of(new StableCaptionBlockPolicy.Block(0, 3000, text, false)),
                "en", "de", "fr", (from, to, texts) -> {
                    fail("Provider input limit must be enforced independently of layout hints");
                    return null;
                }, Runnable::run, () -> {}, () -> {});
        session.demand(0);
        assertFalse(session.snapshot(100).complete);
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
    @Test public void failedCueRetriesOnExplicitBackwardSeekButNotEveryFrame() {
        Jobs jobs = new Jobs(); AtomicInteger calls = new AtomicInteger(); boolean[] unavailable = {true};
        var session = new CaptionSession(SOURCE, "en", "de", null, (from, to, texts) -> {
            calls.incrementAndGet(); if (unavailable[0]) throw new IOException("temporary failure");
            return texts.stream().map(text -> "de:" + text).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(2000); jobs.run(); assertEquals(1, calls.get());
        session.demand(2100); jobs.run(); assertEquals(1, calls.get());
        unavailable[0] = false;
        session.demand(100); jobs.run(); assertEquals(2, calls.get());
        assertEquals("de:first source", session.snapshot(100).primary);
        session.demand(200); jobs.run(); assertEquals(2, calls.get());
    }
    @Test public void shortSourceIsStillTranslated() {
        Jobs jobs = new Jobs(); AtomicInteger requests = new AtomicInteger();
        var session = new CaptionSession(List.of(new StableCaptionBlockPolicy.Block(0, 1000, "short", true)), "en", "de", null,
                (f,t,texts) -> { requests.incrementAndGet(); return List.of("kurz"); }, jobs, () -> {}, () -> {});
        session.demand(0); jobs.run();
        assertEquals(1, requests.get()); assertEquals("kurz", session.snapshot(100).primary);
    }
    @Test public void alignedPartialResultsKeepSuccessfulSiblings() {
        Jobs jobs = new Jobs();
        var session = new CaptionSession(SOURCE, "en", "de", null,
                (f,t,texts) -> Arrays.asList("eins", null), jobs, () -> {}, () -> {});
        session.demand(0); jobs.run();
        assertEquals("eins", session.snapshot(100).primary);
        assertEquals("second source", session.snapshot(4100).primary);
    }
    @Test public void primaryPublishesBeforeSecondaryFinishes() {
        Jobs jobs = new Jobs(); CaptionSession[] holder = new CaptionSession[1]; AtomicInteger updates = new AtomicInteger();
        holder[0] = new CaptionSession(SOURCE, "en", "de", "tr", (f,t,texts) -> {
            if (t.equals("tr")) { assertTrue(updates.get() > 0); assertEquals("de:first source", holder[0].snapshot(100).primary); }
            return texts.stream().map(x -> t + ":" + x).toList();
        }, jobs, updates::incrementAndGet, () -> {});
        holder[0].demand(0); jobs.run();
        assertEquals("tr:first source", holder[0].snapshot(100).secondary);
    }

    @Test public void transientRetryIsBoundedAndKeepsSuccessfulLanguage() {
        Jobs jobs = new Jobs(); long[] now = {0}; AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        var session = new CaptionSession(SOURCE, "en", "de", "tr", (f,t,texts) -> {
            if (t.equals("de")) { first.incrementAndGet(); return texts.stream().map(x -> "de:" + x).toList(); }
            second.incrementAndGet(); throw new IOException("temporary");
        }, jobs, () -> {}, () -> {}, () -> now[0]);
        session.demand(0); jobs.run(); now[0] = 999; session.demand(100); jobs.run();
        assertEquals(1, second.get());
        now[0] = 1000; session.demand(200); jobs.run();
        now[0] = 3000; session.demand(300); jobs.run();
        now[0] = 60000; session.demand(400); jobs.run();
        assertEquals(1, first.get()); assertEquals(3, second.get());
        assertEquals("de:first source", session.snapshot(400).primary);
    }
    @Test public void retryHonorsProviderDelayAndRecoversWithoutSeek() {
        Jobs jobs = new Jobs(); long[] now = {0}; AtomicInteger calls = new AtomicInteger();
        var session = new CaptionSession(SOURCE, "en", "de", null, (f,t,texts) -> {
            if (calls.incrementAndGet() == 1) throw new CaptionBlockTranslator.Failure("limited", 60000, true);
            return texts.stream().map(x -> "de:" + x).toList();
        }, jobs, () -> {}, () -> {}, () -> now[0]);
        session.demand(0); jobs.run(); now[0] = 59999; session.demand(100); jobs.run(); assertEquals(1, calls.get());
        now[0] = 60000; session.demand(200); jobs.run();
        assertEquals(2, calls.get()); assertEquals("de:first source", session.snapshot(200).primary);
    }
    @Test public void fastPlaybackPreparesFurtherAheadAndPreservesSourceClock() {
        Jobs jobs = new Jobs(); List<Integer> sizes = new ArrayList<>();
        var session = new CaptionSession(SOURCE, "en", "de", null, (f,t,texts) -> {
            sizes.add(texts.size()); return texts.stream().map(x -> "de:" + x).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(0, 2); jobs.run(); assertEquals(List.of(3), sizes);
        var cue = session.snapshot(32000); assertEquals(31000, cue.start); assertEquals(34000, cue.end);
    }
    @Test public void lateTranslationGuardUsesWallTimeAtPlaybackSpeed() {
        Jobs jobs = new Jobs(); var session = new CaptionSession(SOURCE, "en", "de", null,
                (f,t,texts) -> texts.stream().map(x -> "de:" + x).toList(), jobs, () -> {}, () -> {});
        session.demand(0, 2); assertEquals("first source", session.snapshot(0).primary); jobs.run();
        assertEquals("first source", session.snapshot(1000).primary);
        assertEquals("de:first source", session.snapshot(1000, true).primary);
    }

    @Test public void terminalMissingResultDoesNotEraseCachedPrimarySibling() {
        Jobs jobs = new Jobs(); long[] now = {0}; AtomicInteger calls = new AtomicInteger();
        var session = new CaptionSession(SOURCE, "en", "de", "tr", (f,t,texts) -> {
            if (!t.equals("de")) throw new IOException("secondary unavailable");
            if (calls.incrementAndGet() == 1) return Arrays.asList("eins", null);
            throw new CaptionBlockTranslator.Failure("unsupported", 0, false);
        }, jobs, () -> {}, () -> {}, () -> now[0]);
        session.demand(0); jobs.run(); now[0] = 1000; session.demand(100); jobs.run();
        assertEquals("eins", session.snapshot(200).primary);
    }

}
