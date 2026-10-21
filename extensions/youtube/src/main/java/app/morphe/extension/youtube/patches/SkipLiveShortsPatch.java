/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3847
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Skips live streams that appear in the Shorts feed.
 */
@SuppressWarnings("unused")
public class SkipLiveShortsPatch {

    /**
     * Implemented by the Shorts player class.
     * The rest of the implementation is added by the patch.
     */
    public interface ReelSequenceController {
        void patch_skipToNextShort();
    }

    private static WeakReference<ReelSequenceController> reelSequenceControllerRef =
            new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void setReelSequenceController(ReelSequenceController controller) {
        reelSequenceControllerRef = new WeakReference<>(controller);
    }

    /**
     * Called by {@link VideoInformation} when the live stream state of the current video changes.
     */
    public static void onLiveStreamChanged(boolean isLive) {
        try {
            if (!isLive || !Settings.SKIP_LIVE_SHORTS.get()) {
                return;
            }

            ReelSequenceController controller = reelSequenceControllerRef.get();
            if (controller == null) {
                return;
            }

            Utils.runOnMainThreadNowOrLater(() -> {
                Logger.printDebug(() -> "Skipping live Short");
                controller.patch_skipToNextShort();
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onLiveStreamChanged failure", ex);
        }
    }
}
