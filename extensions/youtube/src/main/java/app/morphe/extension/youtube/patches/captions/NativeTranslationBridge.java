/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.captions;

import java.util.List;
import java.util.Locale;
import app.morphe.extension.youtube.patches.captions.CaptionTranslationList.CaptionTrack;

/** Menu augmentation only. Caption visibility and preferred-language policy belong to Morphe. */
public final class NativeTranslationBridge {
    private NativeTranslationBridge() { }

    private static Locale menuLocale() {
        try {
            Locale locale = app.morphe.extension.shared.requests.Requester.getAppLocale();
            return locale == null ? Locale.getDefault() : locale;
        } catch (RuntimeException | LinkageError ignored) {
            return Locale.getDefault();
        }
    }

    public static List<CaptionTrack> fallbackDirect(List<CaptionTrack> tracks,
            EmptyTranslationFallback.CaptionSource source) {
        if (!app.morphe.extension.youtube.settings.Settings.AUTO_TRANSLATE_ADDITIONAL_LANGUAGES.get())
            return tracks;
        return EmptyTranslationFallback.direct(tracks, source, menuLocale());
    }

    public static List<CaptionTrack> fallbackTranslated(List<CaptionTrack> tracks,
            EmptyTranslationFallback.CaptionSource source) {
        if (!app.morphe.extension.youtube.settings.Settings.AUTO_TRANSLATE_ADDITIONAL_LANGUAGES.get())
            return tracks;
        return EmptyTranslationFallback.translated(tracks, source, menuLocale());
    }
}
