/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import java.util.concurrent.atomic.AtomicLong;

import android.os.SystemClock;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class AutoCaptionsPatch {

    public enum AutoCaptionsStyle {
        DEFAULT,
        BOTH_ENABLED,
        BOTH_DISABLED,
        WITH_VOLUME_ONLY,
        WITHOUT_VOLUME_ONLY
    }

    private static final AtomicLong captionGuardUntil = new AtomicLong(Long.MAX_VALUE);

    /**
     * Reason of a caption track selection, when YouTube selects the track itself
     * because the caption tracks of a video are loaded. The selections of the user have other reasons.
     */
    private static final int SELECTION_REASON_TRACKS_LOADED = 2;

    /**
     * Reason of the caption track selection that is being applied, set on the main thread
     * before {@link #disableAutoCaptions(boolean)} is called for it.
     */
    private static int captionSelectionReason;

    /**
     * Injection point.
     */
    public static void setCaptionSelectionReason(int reason) {
        captionSelectionReason = reason;
    }

    /**
     * Injection point.
     */
    public static boolean disableAutoCaptions(boolean original) {
        AutoCaptionsStyle style = Settings.AUTO_CAPTIONS_STYLE.get();
        if (style == AutoCaptionsStyle.DEFAULT) {
            return original;
        }

        // 'Always show' overrides a manual caption-off, including after the guard window.
        if (style == AutoCaptionsStyle.BOTH_ENABLED) {
            return false;
        }

        // After the guard window (150ms), respect the user's manual CC button toggle.
        // The guard starts only when a player is created, and the Shorts reuse their player,
        // so the track that YouTube selects for every next Short is found by the reason of the selection.
        if (SystemClock.uptimeMillis() >= captionGuardUntil.get()
                && captionSelectionReason != SELECTION_REASON_TRACKS_LOADED) {
            return original;
        }

        // When a video loads, force captions on or off based on setting.
        boolean wantCaptions = style == AutoCaptionsStyle.WITH_VOLUME_ONLY;
        return !wantCaptions;
    }

    /**
     * Injection point.
     * <p>
     * If no caption track is selected, YouTube still shows the caption track that the video marks as forced.
     *
     * @param hasForcedTrack If the audio track of the video has a forced caption track.
     * @return If the forced caption track is shown.
     */
    public static boolean showForcedCaptions(boolean hasForcedTrack) {
        return hasForcedTrack && Settings.AUTO_CAPTIONS_STYLE.get() != AutoCaptionsStyle.BOTH_DISABLED;
    }

    /**
     * Injection point.
     * <p>
     * Note: 'captionGuardUntil' field check is not needed here
     * because it's only related to 'disableAutoCaptions()' method
     * in order to prevent auto-captioning with volume enabled
     */
    public static boolean disableMuteAutoCaptions(boolean original) {
        AutoCaptionsStyle style = Settings.AUTO_CAPTIONS_STYLE.get();
        if (style == AutoCaptionsStyle.DEFAULT) {
            return original;
        }

        return style == AutoCaptionsStyle.BOTH_ENABLED || style == AutoCaptionsStyle.WITHOUT_VOLUME_ONLY;
    }

    /**
     * Injection point.
     * Called before {@link #disableAutoCaptions(boolean)}.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        captionGuardUntil.set(Long.MAX_VALUE);
    }

    /**
     * Injection point.
     * Called after {@link #disableAutoCaptions(boolean)}.
     */
    public static void videoInformationLoaded() {
        // Repeated loaded events must not re-lock a video after its initial guard expired.
        captionGuardUntil.compareAndSet(Long.MAX_VALUE, SystemClock.uptimeMillis() + 150);
    }
}
