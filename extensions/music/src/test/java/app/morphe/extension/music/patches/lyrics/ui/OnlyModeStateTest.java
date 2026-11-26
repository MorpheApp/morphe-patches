/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.ApplyMode;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.CancelNormal;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.RebuildAfterOnlyEnded;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.Save;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.ShowLyrics;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.ShowLyricsKeepRubyIfDrawn;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.UpdateDualPill;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.UpdateFooter;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.UpdateLabels;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.UpdateRomanizeLabel;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeEffect.UpdateTranslateLabel;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeState.Event;
import app.morphe.extension.music.patches.lyrics.ui.OnlyModeState.Mode;

public class OnlyModeStateTest {

    private static OnlyModeState.Context ctx(Mode mode) {
        return new OnlyModeState.Context(mode, false, false, false, false, false, false);
    }

    private static OnlyModeState.Context ctx(Mode mode, boolean translateOnly, boolean romanizeOnly,
                                             boolean hasTranslate, boolean hasRomanize,
                                             boolean dualPill, boolean romanizeFailed) {
        return new OnlyModeState.Context(mode, translateOnly, romanizeOnly, hasTranslate,
                hasRomanize, dualPill, romanizeFailed);
    }

    private static String sig(List<OnlyModeEffect> effects) {
        StringBuilder sb = new StringBuilder();
        for (OnlyModeEffect e : effects) {
            if (sb.length() > 0) sb.append(',');
            if (e instanceof Save s) {
                sb.append("save:").append(s.key()).append('=').append(s.value());
            } else if (e instanceof ApplyMode) {
                sb.append("apply");
            } else if (e instanceof UpdateDualPill) {
                sb.append("pill");
            } else if (e instanceof ShowLyrics s) {
                sb.append("show:").append(s.keepRuby() ? "keep" : "hideRuby");
            } else if (e instanceof ShowLyricsKeepRubyIfDrawn) {
                sb.append("show:ifRubyDrawn");
            } else if (e instanceof UpdateLabels) {
                sb.append("labels");
            } else if (e instanceof UpdateTranslateLabel) {
                sb.append("transLabel");
            } else if (e instanceof UpdateRomanizeLabel) {
                sb.append("romaLabel");
            } else if (e instanceof UpdateFooter) {
                sb.append("footer");
            } else if (e instanceof CancelNormal c) {
                sb.append("cancel:").append(c.side());
            } else if (e instanceof RebuildAfterOnlyEnded) {
                sb.append("rebuild");
            } else {
                sb.append("?");
            }
        }
        return sb.toString();
    }

    @Test
    public void enterDualWritesAllFourSettingsBeforeApplying() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.ENTER_DUAL, ctx(Mode.NONE));
        assertEquals(Mode.BOTH, t.mode());
        assertEquals("save:TRANSLATE_ONLY=true,save:TRANSLATE=false,"
                        + "save:ROMANIZE_ONLY=true,save:ROMANIZE=false,apply,pill",
                sig(t.effects()));
    }

    @Test
    public void exitDualKeepRestoresBothNormalSettings() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.EXIT_DUAL_KEEP, ctx(Mode.BOTH));
        assertEquals(Mode.NONE, t.mode());
        assertEquals("save:TRANSLATE_ONLY=false,save:ROMANIZE_ONLY=false,"
                        + "save:TRANSLATE=true,save:ROMANIZE=true,apply,pill,"
                        + "show:keep,labels,footer",
                sig(t.effects()));
    }

    @Test
    public void exitDualDropCancelsBothRequests() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.EXIT_DUAL_DROP, ctx(Mode.BOTH));
        assertEquals(Mode.NONE, t.mode());
        assertEquals("save:TRANSLATE_ONLY=false,save:ROMANIZE_ONLY=false,"
                        + "save:TRANSLATE=false,save:ROMANIZE=false,"
                        + "cancel:TRANSLATE,cancel:ROMANIZE,apply,pill,"
                        + "show:hideRuby,labels,footer",
                sig(t.effects()));
    }

    @Test
    public void exitDualIsANoOpUnlessInDualMode() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.EXIT_DUAL_KEEP, ctx(Mode.TRANS));
        assertEquals(Mode.TRANS, t.mode());
        assertTrue(t.effects().isEmpty());
    }

    @Test
    public void clearingTranslateFromDualFallsBackToRomanize() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.CLEAR_TRANSLATE,
                ctx(Mode.BOTH, true, true, false, false, false, false));
        assertEquals(Mode.ROMA, t.mode());
        assertEquals("save:TRANSLATE_ONLY=false,cancel:TRANSLATE,apply,pill,footer",
                sig(t.effects()));
    }

    @Test
    public void clearingRomanizeFromSingleModeGoesToNone() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.CLEAR_ROMANIZE,
                ctx(Mode.ROMA, false, true, false, false, false, false));
        assertEquals(Mode.NONE, t.mode());
        assertEquals("save:ROMANIZE_ONLY=false,cancel:ROMANIZE,apply,pill,footer",
                sig(t.effects()));
    }

    @Test
    public void dualFailureKeepsBothWhenBothDataArrived() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.DUAL_FAILED,
                ctx(Mode.BOTH, true, true, true, true, true, true));
        assertEquals(Mode.BOTH, t.mode());
        assertEquals("save:TRANSLATE_ONLY=false,save:ROMANIZE_ONLY=false,cancel:ROMANIZE,"
                        + "save:TRANSLATE_ONLY=true,save:ROMANIZE_ONLY=true,"
                        + "apply,pill,footer,show:ifRubyDrawn,labels",
                sig(t.effects()));
    }

    @Test
    public void dualFailureClearsToNoneWhenOneSideNeverArrived() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.DUAL_FAILED,
                ctx(Mode.BOTH, true, true, true, false, true, false));
        assertEquals(Mode.NONE, t.mode());
        assertEquals("save:TRANSLATE_ONLY=false,save:ROMANIZE_ONLY=false,cancel:TRANSLATE,"
                        + "apply,pill,footer,show:ifRubyDrawn,labels",
                sig(t.effects()));
    }

    @Test
    public void dualFailureWithOnlyOneFlagClaimedUsesTheSingleClear() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.DUAL_FAILED,
                ctx(Mode.TRANS, true, false, false, false, false, false));
        assertEquals(Mode.NONE, t.mode());
        assertEquals("save:TRANSLATE_ONLY=false,cancel:TRANSLATE,apply,pill,footer",
                sig(t.effects()));
    }

    @Test
    public void trackChangeOnlyResetsTheMode() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.TRACK_CHANGED, ctx(Mode.BOTH));
        assertEquals(Mode.NONE, t.mode());
        assertTrue("Settings must not be touched on a track change", t.effects().isEmpty());
    }

    @Test
    public void hidingButtonsFromSingleModeClearsAndRebuilds() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.HIDE_TRANSLATE,
                ctx(Mode.TRANS, true, false, false, false, false, false));
        assertEquals(Mode.NONE, t.mode());
        assertEquals("save:TRANSLATE_ONLY=false,cancel:TRANSLATE,apply,pill,footer,rebuild",
                sig(t.effects()));
    }

    @Test
    public void hidingButtonsInNormalModeOnlyCancelsTheNormalRequest() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.HIDE_ROMANIZE, ctx(Mode.NONE));
        assertEquals(Mode.NONE, t.mode());
        assertEquals("save:ROMANIZE_ONLY=false,cancel:ROMANIZE,romaLabel", sig(t.effects()));
    }

    @Test
    public void resolveHelpersKeepTheFourWayDecisionsInOnePlace() {
        OnlyModeState state = new OnlyModeState();
        assertEquals(Mode.TRANS, state.resolveNarrow(true, true));
        assertEquals(Mode.ROMA, state.resolveNarrow(false, true));
        assertEquals(Mode.NONE, state.resolveNarrow(true, false));
        assertEquals(Mode.BOTH, state.resolveOnly(true, true));
        assertEquals(Mode.TRANS, state.resolveOnly(true, false));
        assertEquals(Mode.BOTH, state.resolveOnly(false, true));
        assertEquals(Mode.ROMA, state.resolveOnly(false, false));
    }

    @Test
    public void commitAndRequestGenerationTrackTheDecidedState() {
        OnlyModeState state = new OnlyModeState();
        OnlyModeState.Transition t = state.dispatch(Event.ENTER_DUAL, ctx(Mode.NONE));
        state.commit(t);
        assertEquals(Mode.BOTH, state.mode());

        int id = state.nextTranslateRequestId();
        assertTrue(state.isCurrentTranslate(id));
        state.invalidateTranslate();
        assertFalse(state.isCurrentTranslate(id));
        assertFalse(state.isTranslateInProgress());
        state.beginTranslate();
        assertTrue(state.isTranslateInProgress());
        state.finishTranslate();
        assertFalse(state.isTranslateInProgress());
    }
}
