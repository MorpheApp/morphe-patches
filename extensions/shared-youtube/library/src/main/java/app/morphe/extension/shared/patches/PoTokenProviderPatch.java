/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.content.pm.PackageManager;
import android.net.Uri;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

@SuppressWarnings("unused")
public class PoTokenProviderPatch {
    public static final class PoTokenProviderAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return isPoTokenProviderAvailable();
        }
    }

    private static final String PO_TOKEN_SERVICE_SUFFIX = ".potokens.service.START";
    private static final String PO_TOKEN_HELPER_PACKAGE_NAME = "app.morphe.pot.helper";
    private static final String PO_TOKEN_HELPER_SERVICE_ACTION = PO_TOKEN_HELPER_PACKAGE_NAME + PO_TOKEN_SERVICE_SUFFIX;
    private static final Uri PO_TOKEN_HELPER_CONTENT_AUTHORITIES;

    static {
        Uri.Builder builder = new Uri.Builder();
        builder.scheme("content");
        builder.authority(PO_TOKEN_HELPER_PACKAGE_NAME + ".chimera");
        PO_TOKEN_HELPER_CONTENT_AUTHORITIES = builder.build();
    }

    /**
     * Injection point.
     */
    public static Uri overrideAuthorities(String serviceAction, Uri uri) {
        return useExternalPoTokenProvider(serviceAction)
                ? PO_TOKEN_HELPER_CONTENT_AUTHORITIES
                : uri;
    }

    /**
     * Injection point.
     */
    public static String overrideServiceAction(String serviceAction) {
        return useExternalPoTokenProvider(serviceAction)
                ? PO_TOKEN_HELPER_SERVICE_ACTION
                : serviceAction;
    }

    private static boolean useExternalPoTokenProvider(String serviceAction) {
        return serviceAction != null
                && serviceAction.endsWith(PO_TOKEN_SERVICE_SUFFIX)
                && SharedYouTubeSettings.EXTERNAL_POTOKEN_PROVIDER.get()
                && isPoTokenProviderAvailable();
    }

    private static boolean isPoTokenProviderAvailable() {
        // To minimize confusion, it works only when 'Spoof video streams' is turned off.
        if (!SpoofVideoStreamsPatch.isPatchIncluded() || !SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.get()) {
            try {
                if (Utils.getContext().getPackageManager().getApplicationInfo(PO_TOKEN_HELPER_PACKAGE_NAME, 0).enabled) {
                    Logger.printDebug(() -> "App installed: " + PO_TOKEN_HELPER_PACKAGE_NAME);
                    return true;
                }
            } catch (PackageManager.NameNotFoundException error) {
                Logger.printDebug(() -> "App not installed: " + PO_TOKEN_HELPER_PACKAGE_NAME);
            }
        }
        return false;
    }
}
