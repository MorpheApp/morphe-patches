/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.view.View;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class SonosCastPatch {

    /**
     * Set while the Google Cast entry of the speaker sheet clicks the Cast button.
     */
    private static boolean openingCastDialog;

    private SonosCastPatch() {
    }

    /**
     * Injection point.
     *
     * @param isAvailable If Cast devices are available.
     * @return If the Cast buttons are shown.
     */
    public static boolean isCastButtonAvailable(boolean isAvailable) {
        return isAvailable || Settings.SONOS_CAST.get();
    }

    /**
     * Injection point.
     *
     * @return If the click was handled and the Cast dialog of the app must not open.
     */
    public static boolean onCastButtonClick(View castButton) {
        try {
            if (!Settings.SONOS_CAST.get() || openingCastDialog) {
                return false;
            }

            SonosCastBottomSheet.show(castButton);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onCastButtonClick failure", ex);
            return false;
        }
    }

    /**
     * Opens the Cast dialog of the app.
     */
    static void openCastDialog(View castButton) {
        openingCastDialog = true;
        try {
            castButton.performClick();
        } finally {
            openingCastDialog = false;
        }
    }

    /**
     * Injection point. Replaces the AudioTrack write of the ExoPlayer audio sink.
     */
    public static int writeAudio(AudioTrack track, ByteBuffer buffer, int sizeInBytes, int writeMode) {
        if (!SonosCastManager.isConnected()) {
            return track.write(buffer, sizeInBytes, writeMode);
        }

        final int position = buffer.position();
        final int written = track.write(buffer, sizeInBytes, writeMode);
        if (written > 0) {
            try {
                ByteBuffer audio = buffer.duplicate();
                audio.position(position);
                audio.limit(position + written);
                SonosCastManager.onAudioWritten(track, audio);
            } catch (Exception ex) {
                Logger.printException(() -> "writeAudio failure", ex);
            }
        }
        return written;
    }

    /**
     * Injection point. Replaces the AudioTrack volume change of the ExoPlayer audio sink.
     */
    public static int setVolume(AudioTrack track, float volume) {
        if (!Settings.SONOS_CAST.get()) {
            return track.setVolume(volume);
        }

        return track.setVolume(SonosCastManager.onPlayerVolume(track, volume));
    }

    /**
     * Injection point.
     */
    public static boolean deferAudioFocusChange(AudioManager.OnAudioFocusChangeListener listener, int focusChange) {
        return SonosCastManager.deferAudioFocusChange(listener, focusChange);
    }

    /**
     * Injection point.
     */
    public static void onSetMetadata(MediaSession session, @Nullable MediaMetadata metadata) {
        if (!Settings.SONOS_CAST.get()) {
            return;
        }

        try {
            SonosCastManager.onMetadata(session, metadata);
        } catch (Exception ex) {
            Logger.printException(() -> "onSetMetadata failure", ex);
        }
    }
}
