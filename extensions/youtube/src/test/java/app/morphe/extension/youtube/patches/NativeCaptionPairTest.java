/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4376
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import org.junit.Test;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.*;

public class NativeCaptionPairTest {
    private record Cue(int id, CharSequence text, StableCaptionBlocks.Frame frame)
            implements StableCaptionBlocks.Cue {
        public boolean patch_isCaptionVisible() { return true; }
        public int patch_getCaptionId() { return id; }
        public CharSequence patch_getOriginalCaptionVisible() { return text; }
        public CharSequence patch_getOriginalCaptionFull() { return text; }
        public StableCaptionBlocks.Frame patch_getBlockFrame() { return frame; }
        public Object patch_copyBlockFrame(StableCaptionBlocks.Frame f) {
            return new Cue(f.captionId(id), text, f);
        }
    }
    private StableCaptionBlocks.Timeline track(List<?> early, List<?> late) {
        return new StableCaptionBlocks.Timeline() {
            public List<?> patch_getOriginalCaptions(long t) { return t < 1000 ? early : late; }
            public StableCaptionBlocks.State patch_getBlockState() { return null; }
            public void patch_setBlockState(StableCaptionBlocks.State s) { throw new UnsupportedOperationException(); }
            public StableCaptionBlocks.Timeline patch_mergeWith(StableCaptionBlocks.Timeline t) { throw new UnsupportedOperationException(); }
            public List<Long> patch_getCaptionEventTimes() { return List.of(0L,1000L,2000L); }
        };
    }
    @Test public void preservesAllEffectLayersAndAssignsStableDistinctIds() {
        CharSequence style = new StringBuilder("styled");
        var first = track(List.of(new Cue(0,style,null),new Cue(1,style,null)),List.of(new Cue(1,style,null)));
        var second = track(List.of(new Cue(0,"second",null)),List.of(new Cue(0,"later",null)));
        var pair = new NativeCaptionPair(new UploadedCaptionPair(first,second),null);
        var state = new StableCaptionBlocks.State(false); state.nativePair = pair;
        var early = pair.at(0,state); var late = pair.at(1500,state);
        assertEquals(3,early.size());
        Cue a=(Cue)early.get(0), b=(Cue)early.get(1), c=(Cue)early.get(2);
        assertSame(style,a.text()); assertSame(style,b.text());
        assertNotEquals(a.id(),b.id()); assertNotEquals(a.id(),c.id()); assertNotEquals(b.id(),c.id());
        assertEquals(b.id(),((Cue)late.get(0)).id()); assertEquals(c.id(),((Cue)late.get(1)).id());
        assertFalse(a.frame().usesBlockLayout()); assertFalse(b.frame().usesBlockLayout());
        assertFalse(a.frame().secondaryNativeCaption()); assertTrue(c.frame().secondaryNativeCaption());
        assertEquals(0,((Cue)first.patch_getOriginalCaptions(0).get(0)).id());
        assertNull(((Cue)first.patch_getOriginalCaptions(0).get(0)).frame());
    }
    @Test public void nativeGapsDoNotInsertFallbackOrDropOtherLanguage() {
        var first=track(List.of(new Cue(99,"",null)),List.of());
        var second=track(List.of(new Cue(-1,"second",null)),List.of(new Cue(-1,"still second",null)));
        var pair=new NativeCaptionPair(new UploadedCaptionPair(first,second),null);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;
        assertEquals(2,pair.at(0,state).size()); assertEquals(1,pair.at(1500,state).size());
    }
    private CaptionSession fallback(boolean primary, boolean secondary) {
        return new CaptionSession(List.of(new StableCaptionBlockPolicy.Block(0,2000,"source",true)),
                "en","tr","de",(from,to,texts)->texts.stream().map(text->to+":"+text).toList(),
                Runnable::run,()->{},()->{},primary,secondary);
    }
    @Test public void missingSecondaryGetsOneDefaultStyledWindowWithoutFlatteningPrimary() {
        CharSequence nativeStyle=new StringBuilder("native primary");
        var first=track(List.of(new Cue(0,nativeStyle,null)),List.of(new Cue(0,nativeStyle,null)));
        var source=track(List.of(new Cue(0,"source",null)),List.of(new Cue(0,"source",null)));
        var pair=new NativeCaptionPair(new UploadedCaptionPair(first,null),source);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;state.translation=fallback(false,true);
        var cues=pair.at(100,state);assertEquals(2,cues.size());
        Cue nativeCue=(Cue)cues.get(0),translated=(Cue)cues.get(1);
        assertSame(nativeStyle,nativeCue.text());assertTrue(nativeCue.frame().nativeStyle);
        assertFalse(translated.frame().nativeStyle);assertTrue(translated.frame().secondaryNativeCaption());
        assertNotEquals(nativeCue.id(),translated.id());assertNotEquals(nativeCue.frame(),translated.frame());
        Objects.requireNonNull(state.translation).demand(100);var snapshot= Objects.requireNonNull(state.translation).snapshot(100,true);
        var text=pair.fallback(Objects.requireNonNull(snapshot));assertEquals("",text.primary());assertEquals("de:source",text.secondary());
    }
    @Test public void missingPrimaryUsesPrimaryFallbackAndPreservesNativeSecondary() {
        var second=track(List.of(new Cue(0,"native second",null)),List.of());
        var source=track(List.of(new Cue(0,"source",null)),List.of());
        var pair=new NativeCaptionPair(new UploadedCaptionPair(null,second),source);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;state.translation=fallback(true,false);
        var cues=pair.at(100,state);assertEquals(2,cues.size());
        Cue nativeCue=(Cue)cues.get(0),translated=(Cue)cues.get(1);
        assertTrue(nativeCue.frame().secondaryNativeCaption());assertFalse(translated.frame().secondaryNativeCaption());
        assertFalse(translated.frame().nativeStyle);assertNotEquals(nativeCue.id(),translated.id());
        Objects.requireNonNull(state.translation).demand(100);var text=pair.fallback(Objects.requireNonNull(Objects.requireNonNull(state.translation).snapshot(100, true)));
        assertEquals("tr:source",text.primary());assertEquals("",text.secondary());
    }
    @Test public void expiredFallbackOrSourceGapDoesNotRemoveNativeLanguage() {
        var first=track(List.of(new Cue(0,"native",null)),List.of(new Cue(0,"native",null)));
        var source=track(List.of(new Cue(0,"source",null)),List.of());
        var pair=new NativeCaptionPair(new UploadedCaptionPair(first,null),source);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;state.translation=fallback(false,true);
        assertEquals(1,pair.at(1500,state).size());
        Objects.requireNonNull(state.translation).invalidate();assertEquals(1,pair.at(100,state).size());
    }
    @Test public void persistentTemplateChangesItsFrameAtSourceBlockBoundariesButKeepsWindowId() {
        var first=track(List.of(new Cue(0,"native",null)),List.of(new Cue(0,"native",null)));
        var source=track(List.of(new Cue(0,"persistent template",null)),List.of(new Cue(0,"persistent template",null)));
        var pair=new NativeCaptionPair(new UploadedCaptionPair(first,null),source);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;
        state.translation=new CaptionSession(List.of(new StableCaptionBlockPolicy.Block(0,1000,"a",true),
                new StableCaptionBlockPolicy.Block(1000,2000,"b",true)),"en","tr","de",
                (from,to,texts)->texts,Runnable::run,()->{},()->{},false,true);
        Cue a=(Cue)pair.at(100,state).get(1), b=(Cue)pair.at(1500,state).get(1);
        assertEquals(a.id(),b.id());assertNotEquals(a.frame(),b.frame());
        assertEquals(0,a.frame().key);assertEquals(1000,b.frame().key);
    }
    @Test public void hiddenSourceWindowDoesNotBecomeAVisibleFallbackTemplate() {
        StableCaptionBlocks.Cue hidden=new StableCaptionBlocks.Cue() {
            public boolean patch_isCaptionVisible(){return false;}
            public int patch_getCaptionId(){return 2;}
            public CharSequence patch_getOriginalCaptionVisible(){return "hidden";}
            public CharSequence patch_getOriginalCaptionFull(){return "hidden";}
            public StableCaptionBlocks.Frame patch_getBlockFrame(){return null;}
            public Object patch_copyBlockFrame(StableCaptionBlocks.Frame frame){throw new AssertionError("hidden window resurrected");}
        };
        var first=track(List.of(new Cue(0,"native",null)),List.of());
        var source=track(List.of(hidden,new Cue(3,"visible",null)),List.of());
        var pair=new NativeCaptionPair(new UploadedCaptionPair(first,null),source);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;state.translation=fallback(false,true);
        var cues=pair.at(100,state);assertEquals(2,cues.size());assertEquals("visible",((Cue)cues.get(1)).text());
    }
}
