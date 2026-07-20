/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

import java.util.Arrays;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class ForceOriginalAudioPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface AudioTrackInterface {
        // Methods are added during patching.
        String patch_getDisplayName();
        String patch_getId();
        boolean patch_getIsDefault();
    }

    private static final boolean FORCE_ORIGINAL_AUDIO = SharedYouTubeSettings.FORCE_ORIGINAL_AUDIO.get();

    private static final String DEFAULT_AUDIO_TRACKS_SUFFIX = ".4";

    /**
     * The available audio tracks of the current video.
     */
    @Nullable
    private static AudioTrackInterface[] currentAudioTracks;

    /**
     * Injection point.
     * Called after {@link #isDefaultAudioStream(boolean, String, String)}.
     *
     * @param   audioTracks Audio tracks available, sorted alphabetically.
     * @return  Original audio track id.
     */
    @Nullable
    public static String getDefaultAudioTrackId(AudioTrackInterface[] audioTracks) {
        // No need to override when the number of audio tracks is 1 or less.
        if (FORCE_ORIGINAL_AUDIO && audioTracks != null && audioTracks.length > 1) {
            try {
                Utils.verifyOnMainThread();

                final boolean availableAudioTracksChanged = (currentAudioTracks == null)
                        || !Arrays.equals(currentAudioTracks, audioTracks);
                if (availableAudioTracksChanged) {
                    currentAudioTracks = audioTracks;

                    for (AudioTrackInterface audioTrack : audioTracks) {
                        boolean isDefault = audioTrack.patch_getIsDefault();
                        String audioTrackDisplayName = audioTrack.patch_getDisplayName();
                        String audioTrackId = audioTrack.patch_getId();

                        if (isDefault) {
                            Logger.printDebug(() -> "Overriding audio track: " + audioTrackDisplayName);
                            return audioTrackId;
                        }
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "getDefaultAudioTrackId failure", ex);
            }
        }

        return null;
    }

    /**
     * Injection point.
     */
    public static boolean ignoreDefaultAudioStream(boolean original) {
        if (FORCE_ORIGINAL_AUDIO) {
            return false;
        }
        return original;
    }

    /**
     * Injection point.
     */
    public static boolean isDefaultAudioStream(boolean isDefault, String audioTrackId, String audioTrackDisplayName) {
        if (FORCE_ORIGINAL_AUDIO) {
            try {
                if (audioTrackId.isEmpty()) {
                    // Older app targets can have empty audio tracks and these might be placeholders.
                    // The real audio tracks are called after these.
                    return isDefault;
                }

                Logger.printDebug(() -> "default: " + String.format("%-5s", isDefault) + " id: "
                        + String.format("%-8s", audioTrackId) + " name:" + audioTrackDisplayName);

                final boolean isOriginal = audioTrackId.endsWith(DEFAULT_AUDIO_TRACKS_SUFFIX);
                if (isOriginal) {
                    Logger.printDebug(() -> "Using audio: " + audioTrackId);
                }

                return isOriginal;
            } catch (Exception ex) {
                Logger.printException(() -> "isDefaultAudioStream failure", ex);
            }
        }

        return isDefault;
    }
}
