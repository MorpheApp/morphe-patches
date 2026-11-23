/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

/** Translation/romanization only-mode state machine. */
final class OnlyModeState {

    public enum Mode {NONE, TRANS, ROMA, BOTH}

    enum SettingKey {TRANSLATE, TRANSLATE_ONLY, ROMANIZE, ROMANIZE_ONLY}

    enum Side {TRANSLATE, ROMANIZE}

    enum Event {
        ENTER_DUAL, EXIT_DUAL_KEEP, EXIT_DUAL_DROP,
        CLEAR_TRANSLATE, CLEAR_ROMANIZE, DUAL_FAILED, TRACK_CHANGED,
        HIDE_TRANSLATE, HIDE_ROMANIZE
    }

    record Context(Mode current, boolean translateOnlyHeld, boolean romanizeOnlyHeld,
                   boolean hasTranslate, boolean hasRomanize, boolean dualPill,
                   boolean romanizeFailed) {
    }

    record Transition(Mode mode, List<OnlyModeEffect> effects) {
        static Transition unchanged(Mode mode) {
            return new Transition(mode, List.of());
        }
    }

    record StashedTranslation(
            List<String> lines,
            boolean fromGoogle,
            boolean fromAI,
            @Nullable String aiModel,
            Lyrics sourceLyrics,
            String cacheSignature) {
    }

    record StashedRomanization(
            @Nullable List<LyricsLine> lines,
            boolean perWord,
            boolean fromGoogle,
            boolean fromAI,
            boolean fromEmbedded,
            @Nullable String aiModel,
            @Nullable String localDictionary,
            @Nullable String appliedRubyLanguage,
            @Nullable Lyrics rubyAppliedLyrics,
            Lyrics sourceLyrics,
            String cacheSignature) {
    }

    private static final OnlyModeEffect APPLY_MODE = new OnlyModeEffect.ApplyMode();
    private static final OnlyModeEffect UPDATE_DUAL_PILL = new OnlyModeEffect.UpdateDualPill();
    private static final OnlyModeEffect SHOW_LYRICS = new OnlyModeEffect.ShowLyrics(true);
    private static final OnlyModeEffect SHOW_LYRICS_HIDE_RUBY = new OnlyModeEffect.ShowLyrics(false);
    private static final OnlyModeEffect SHOW_LYRICS_KEEP_IF_DRAWN =
            new OnlyModeEffect.ShowLyricsKeepRubyIfDrawn();
    private static final OnlyModeEffect UPDATE_LABELS = new OnlyModeEffect.UpdateLabels();
    private static final OnlyModeEffect UPDATE_FOOTER = new OnlyModeEffect.UpdateFooter();
    private static final OnlyModeEffect REBUILD = new OnlyModeEffect.RebuildAfterOnlyEnded();

    private Mode requested = Mode.NONE;
    private Mode displayed = Mode.NONE;
    private Mode built = Mode.NONE;
    private boolean dualPillActive;
    private boolean translateInProgress;
    private boolean romanizeInProgress;
    private int translateRequestId;
    private int romanizeRequestId;

    Mode mode() {
        return requested;
    }

    Mode displayed() {
        return displayed;
    }

    void setDisplayed(Mode displayed) {
        this.displayed = displayed;
    }

    Mode built() {
        return built;
    }

    void setBuilt(Mode built) {
        this.built = built;
    }

    boolean dualPillActive() {
        return dualPillActive;
    }

    void setDualPillActive(boolean dualPillActive) {
        this.dualPillActive = dualPillActive;
    }

    boolean isTranslateInProgress() {
        return translateInProgress;
    }

    boolean isRomanizeInProgress() {
        return romanizeInProgress;
    }

    void beginTranslate() {
        translateInProgress = true;
    }

    void finishTranslate() {
        translateInProgress = false;
    }

    void beginRomanize() {
        romanizeInProgress = true;
    }

    void finishRomanize() {
        romanizeInProgress = false;
    }

    int nextTranslateRequestId() {
        return ++translateRequestId;
    }

    int nextRomanizeRequestId() {
        return ++romanizeRequestId;
    }

    boolean isCurrentTranslate(int requestId) {
        return requestId == translateRequestId;
    }

    boolean isCurrentRomanize(int requestId) {
        return requestId == romanizeRequestId;
    }

    void invalidateTranslate() {
        translateRequestId++;
        translateInProgress = false;
    }

    void invalidateRomanize() {
        romanizeRequestId++;
        romanizeInProgress = false;
    }

    void commit(Transition transition) {
        requested = transition.mode();
    }

    void commitMode(Mode mode) {
        requested = mode;
    }

    Mode resolveNarrow(boolean translate, boolean hasData) {
        if (hasData) {
            return translate ? Mode.TRANS : Mode.ROMA;
        }
        // No data: stay off while the request runs, but still let the panel rebuild.
        return Mode.NONE;
    }

    Mode resolveOnly(boolean translate, boolean otherOnlyHeld) {
        if (otherOnlyHeld) {
            return Mode.BOTH;
        }
        return translate ? Mode.TRANS : Mode.ROMA;
    }

    Transition dispatch(Event event, Context ctx) {
        List<OnlyModeEffect> fx = new ArrayList<>();
        switch (event) {
            case ENTER_DUAL -> {
                save(fx, SettingKey.TRANSLATE_ONLY, true);
                save(fx, SettingKey.TRANSLATE, false);
                save(fx, SettingKey.ROMANIZE_ONLY, true);
                save(fx, SettingKey.ROMANIZE, false);
                fx.add(APPLY_MODE);
                fx.add(UPDATE_DUAL_PILL);
                return new Transition(Mode.BOTH, fx);
            }
            case EXIT_DUAL_KEEP, EXIT_DUAL_DROP -> {
                if (ctx.current() != Mode.BOTH) {
                    return Transition.unchanged(ctx.current());
                }
                final boolean keep = event == Event.EXIT_DUAL_KEEP;
                save(fx, SettingKey.TRANSLATE_ONLY, false);
                save(fx, SettingKey.ROMANIZE_ONLY, false);
                save(fx, SettingKey.TRANSLATE, keep);
                save(fx, SettingKey.ROMANIZE, keep);
                if (!keep) {
                    fx.add(new OnlyModeEffect.CancelNormal(Side.TRANSLATE));
                    fx.add(new OnlyModeEffect.CancelNormal(Side.ROMANIZE));
                }
                fx.add(APPLY_MODE);
                fx.add(UPDATE_DUAL_PILL);
                fx.add(keep ? SHOW_LYRICS : SHOW_LYRICS_HIDE_RUBY);
                fx.add(UPDATE_LABELS);
                fx.add(UPDATE_FOOTER);
                return new Transition(Mode.NONE, fx);
            }
            case CLEAR_TRANSLATE -> {
                save(fx, SettingKey.TRANSLATE_ONLY, false);
                fx.add(new OnlyModeEffect.CancelNormal(Side.TRANSLATE));
                final Mode next;
                if (ctx.current() == Mode.TRANS || ctx.current() == Mode.BOTH) {
                    next = ctx.romanizeOnlyHeld() ? Mode.ROMA : Mode.NONE;
                    fx.add(APPLY_MODE);
                } else {
                    next = ctx.current();
                }
                fx.add(UPDATE_DUAL_PILL);
                fx.add(UPDATE_FOOTER);
                return new Transition(next, fx);
            }
            case CLEAR_ROMANIZE -> {
                save(fx, SettingKey.ROMANIZE_ONLY, false);
                fx.add(new OnlyModeEffect.CancelNormal(Side.ROMANIZE));
                final Mode next;
                if (ctx.current() == Mode.ROMA || ctx.current() == Mode.BOTH) {
                    next = ctx.translateOnlyHeld() ? Mode.TRANS : Mode.NONE;
                    fx.add(APPLY_MODE);
                } else {
                    next = ctx.current();
                }
                fx.add(UPDATE_DUAL_PILL);
                fx.add(UPDATE_FOOTER);
                return new Transition(next, fx);
            }
            case DUAL_FAILED -> {
                final boolean dualClaimed = ctx.current() == Mode.BOTH
                        || (ctx.translateOnlyHeld() && ctx.romanizeOnlyHeld());
                if (!dualClaimed) {
                    return dispatch(ctx.romanizeFailed()
                            ? Event.CLEAR_ROMANIZE : Event.CLEAR_TRANSLATE, ctx);
                }
                save(fx, SettingKey.TRANSLATE_ONLY, false);
                save(fx, SettingKey.ROMANIZE_ONLY, false);
                fx.add(new OnlyModeEffect.CancelNormal(ctx.romanizeFailed()
                        ? Side.ROMANIZE : Side.TRANSLATE));
                final boolean bothArrived = ctx.hasTranslate() && ctx.hasRomanize();
                if (bothArrived) {
                    save(fx, SettingKey.TRANSLATE_ONLY, true);
                    save(fx, SettingKey.ROMANIZE_ONLY, true);
                }
                fx.add(APPLY_MODE);
                fx.add(UPDATE_DUAL_PILL);
                fx.add(UPDATE_FOOTER);
                fx.add(SHOW_LYRICS_KEEP_IF_DRAWN);
                fx.add(UPDATE_LABELS);
                return new Transition(bothArrived ? Mode.BOTH : Mode.NONE, fx);
            }
            case TRACK_CHANGED -> {
                // Settings survive a track switch; DUAL_FAILED recovers a dual claim.
                return new Transition(Mode.NONE, fx);
            }
            case HIDE_TRANSLATE, HIDE_ROMANIZE -> {
                // Capture both release flags before either mode changes.
                final boolean translate = event == Event.HIDE_TRANSLATE;
                if (ctx.current() != Mode.NONE) {
                    final Transition cleared = dispatch(translate
                            ? Event.CLEAR_TRANSLATE : Event.CLEAR_ROMANIZE, ctx);
                    return new Transition(cleared.mode(), with(cleared.effects(), REBUILD));
                }
                save(fx, translate ? SettingKey.TRANSLATE_ONLY : SettingKey.ROMANIZE_ONLY, false);
                fx.add(new OnlyModeEffect.CancelNormal(translate ? Side.TRANSLATE : Side.ROMANIZE));
                fx.add(translate ? new OnlyModeEffect.UpdateTranslateLabel()
                        : new OnlyModeEffect.UpdateRomanizeLabel());
                return new Transition(Mode.NONE, fx);
            }
        }
        return Transition.unchanged(ctx.current());
    }

    private static void save(List<OnlyModeEffect> effects, SettingKey key, boolean value) {
        effects.add(new OnlyModeEffect.Save(key, value));
    }

    private static List<OnlyModeEffect> with(List<OnlyModeEffect> effects, OnlyModeEffect extra) {
        final List<OnlyModeEffect> copy = new ArrayList<>(effects);
        copy.add(extra);
        return copy;
    }
}
