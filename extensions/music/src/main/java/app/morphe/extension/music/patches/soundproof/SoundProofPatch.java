/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof;

import android.media.MediaMetadata;
import android.media.session.MediaSession;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class SoundProofPatch {

    private SoundProofPatch() {
    }

    /**
     * Injection point. Called right before YouTube Music runs {@code MediaSession.setMetadata}.
     */
    public static void onSetMetadata(MediaSession session, MediaMetadata metadata) {
        if (session == null || metadata == null) {
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
