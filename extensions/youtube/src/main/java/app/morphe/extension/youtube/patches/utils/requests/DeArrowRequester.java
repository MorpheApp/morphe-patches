/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * DeArrow (<a href="http://dearrow.ajay.app">...</a>) API state
 * shared by the alternative thumbnails and the DeArrow titles.
 */
public final class DeArrowRequester {

    public static final String DEARROW_API_URL = "https://sponsor.ajay.app";

    /**
     * How long to temporarily turn off DeArrow if it fails for any reason.
     */
    private static final long DEARROW_FAILURE_API_BACKOFF_MILLISECONDS = 5 * 60 * 1000; // 5 Minutes.

    /**
     * If non-zero, then the system time of when DeArrow API calls can resume.
     */
    private static volatile long timeToResumeDeArrowAPICalls;

    private DeArrowRequester() {
    }

    /**
     * @return If this client has not recently experienced any DeArrow API errors.
     */
    public static boolean canUseDeArrowAPI() {
        if (timeToResumeDeArrowAPICalls == 0) {
            return true;
        }
        if (timeToResumeDeArrowAPICalls < System.currentTimeMillis()) {
            Logger.printDebug(() -> "Resuming DeArrow API calls");
            timeToResumeDeArrowAPICalls = 0;
            return true;
        }
        return false;
    }

    public static void handleDeArrowError(@NonNull String url, int statusCode) {
        Logger.printDebug(() -> "Encountered DeArrow error.  URL: " + url);
        final long now = System.currentTimeMillis();
        if (timeToResumeDeArrowAPICalls < now) {
            timeToResumeDeArrowAPICalls = now + DEARROW_FAILURE_API_BACKOFF_MILLISECONDS;
            if (Settings.ALT_THUMBNAIL_DEARROW_CONNECTION_TOAST.get()) {
                String toastMessage = (statusCode != 0)
                        ? str("morphe_alt_thumbnail_dearrow_error", statusCode)
                        : str("morphe_alt_thumbnail_dearrow_error_generic");
                Utils.showToastLong(toastMessage);
            }
        }
    }
}
