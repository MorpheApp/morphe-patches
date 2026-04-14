/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class AutoCaptionsPatch {

    public enum AutoCaptionsStyle {
        BOTH_ENABLED,
        BOTH_DISABLED,
        WITH_VOLUME_ONLY,
        WITHOUT_VOLUME_ONLY
    }

    private static final AtomicBoolean captionsButtonStatus = new AtomicBoolean(false);

    /**
     * Injection point.
     */
    public static boolean disableAutoCaptions(boolean original) {
        AutoCaptionsStyle style = Settings.AUTO_CAPTIONS_STYLE.get();
        final boolean withVolumeAutoCaptioningEnabled = (style == AutoCaptionsStyle.BOTH_ENABLED)
                || (style == AutoCaptionsStyle.WITH_VOLUME_ONLY);

        if (!withVolumeAutoCaptioningEnabled) {
            // Disable auto-captioning only
            // when 'withVolumeAutoCaptioningEnabled'
            // field is false
            return !captionsButtonStatus.get() || original;
        }

        return original;
    }

    /**
     * Injection point.
     * <p>
     * Note: 'captionsButtonStatus' field check is not needed here
     * because it's only related to 'disableAutoCaptions()' method
     * in order to prevent auto-captioning with volume enabled
     */
    public static boolean disableMuteAutoCaptions() {
        AutoCaptionsStyle style =
            Settings.AUTO_CAPTIONS_STYLE.get();

        return style == AutoCaptionsStyle.BOTH_ENABLED || style == AutoCaptionsStyle.WITHOUT_VOLUME_ONLY;
    }

    /**
     * Injection point.
     * Called before {@link #disableAutoCaptions(boolean)}.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        captionsButtonStatus.set(false);
    }

    /**
     * Injection point.
     * Called after {@link #disableAutoCaptions(boolean)}.
     */
    public static void videoInformationLoaded() {
        Utils.runOnMainThreadDelayed(() -> captionsButtonStatus.compareAndSet(false, true), 150);
    }
}
