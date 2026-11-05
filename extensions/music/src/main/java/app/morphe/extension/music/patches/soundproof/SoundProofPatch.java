/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof;

import android.media.MediaMetadata;
import android.media.session.MediaSession;

import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class SoundProofPatch {

    private SoundProofPatch() {
    }

    /**
     * Injection point. Called right before YouTube Music runs {@code MediaSession.setMetadata}.
     */
    public static void onSetMetadata(MediaSession session, @Nullable MediaMetadata metadata) {
        if (metadata == null
                || !(Settings.SOUNDPROOF_SKIP_AI_ARTISTS.get() || Settings.SOUNDPROOF_SHOW_BUTTON.get())) {
            return;
        }
        // Posted so that a skip never runs inside the app's own metadata publication.
        Utils.runOnMainThread(() -> {
            try {
                SoundProofManager.getInstance().onSetMetadata(session, metadata);
            } catch (Exception ex) {
                Logger.printException(() -> "SoundProof onSetMetadata failure", ex);
            }
        });
    }
}
