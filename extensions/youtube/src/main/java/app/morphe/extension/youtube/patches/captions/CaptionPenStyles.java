/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.captions;

import java.util.ArrayDeque;
import app.morphe.extension.youtube.patches.captions.CaptionTranslationList.CaptionTrack;

/**
 * Caption styles belong to the document being parsed, not the most recent URL
 * requested by the player. Native parser hooks enter and exit on that parser's
 * own thread, including cached captions and concurrent requests.
 */
public final class CaptionPenStyles {
    private CaptionPenStyles() { }

    public interface CaptionCarrier {
        CaptionTrack universalCaptions_styleTrack();
    }

    private static final int DEFAULT_PEN = Integer.MIN_VALUE;
    private static final class Scope {
        final Object request;
        final boolean translated;

        Scope(Object request, boolean translated) {
            this.request = request;
            this.translated = translated;
        }
    }

    private static final ThreadLocal<ArrayDeque<Scope>> SCOPES = new ThreadLocal<>();

    /** Called at a synchronous caption parser invocation, never at request dispatch. */
    public static void beginCaption(Object request) {
        boolean translated = false;
        if (request instanceof CaptionCarrier) {
            try {
                CaptionTrack track = ((CaptionCarrier) request).universalCaptions_styleTrack();
                if (track != null) {
                    String code = track.universalCaptions_getLanguageCode();
                    String vss = track.universalCaptions_getVssId();
                    TimedTextUrl url = TimedTextUrl.parse(track.universalCaptions_getCaptionUrl());
                    translated = CaptionTranslationList.validLanguageCode(code)
                            && vss != null && vss.startsWith("t") && vss.endsWith("." + code)
                            && url != null && code.equals(url.field("tlang"))
                            && url.targetValueOffset(code) >= 0;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // An unknown native track must never alter the original's creator styles.
            }
        }
        ArrayDeque<Scope> scopes = SCOPES.get();
        if (scopes == null) {
            scopes = new ArrayDeque<>();
            SCOPES.set(scopes);
        }
        scopes.push(new Scope(request, translated));
    }

    /** Only close the scope opened by this parser invocation.
     * An exception handler can also run for code before beginCaption().
     */
    public static void endCaption(Object request) {
        ArrayDeque<Scope> scopes = SCOPES.get();
        if (scopes == null || scopes.isEmpty() || scopes.peek().request != request) return;
        scopes.pop();
        if (scopes.isEmpty()) SCOPES.remove();
    }

    /** Native pen lookup can occur during a translated or original document parse. */
    public static int getPenId(int penId) {
        ArrayDeque<Scope> scopes = SCOPES.get();
        return scopes != null && !scopes.isEmpty() && scopes.peek().translated
                ? DEFAULT_PEN : penId;
    }
}
