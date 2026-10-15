/*
 * Copyright 2026 itolkien.
 * Adapted from https://github.com/itolkien/morphe-universal-captions
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.captions.CaptionTranslationList;
import app.morphe.extension.youtube.patches.captions.CaptionTranslationList.CaptionTrack;

@SuppressWarnings("unused")
public final class AutoTranslateCaptionsPatch {
    private AutoTranslateCaptionsPatch() { }

    public static List<CaptionTrack> augment(List<CaptionTrack> tracks) {
        try {
            return CaptionTranslationList.augment(tracks, Requester.getAppLocale());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not extend caption translation languages", ex);
            return tracks;
        }
    }
}
