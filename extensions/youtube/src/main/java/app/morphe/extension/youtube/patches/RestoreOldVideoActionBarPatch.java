/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.patches;

import android.net.Uri;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.requests.ConfigRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class RestoreOldVideoActionBarPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface ConfigInfoInterface {
        // Methods are added during patching.
        void patch_setColdConfigData(String coldConfigData);
        void patch_setColdHashData(String coldHashData);
    }

    private static final boolean FIX_VIDEO_ACTION_BAR = Settings.RESTORE_OLD_VIDEO_ACTION_BAR.get()
            // If 'Disable layout updates' is enabled, fix is not required.
            && !Settings.DISABLE_LAYOUT_UPDATES.get()
            // Tablets already have a non-collapsed video action bar.
            // If it does not work on a foldable device, please remove this.
            && !Utils.isTablet();
    private static final String AUTHORIZATION_HEADER = "Authorization";
    /**
     * This field value is fetched when the app is first installed.
     * It does not change unless the server-side kill switch is activated.
     * The recent wide rollout of the modern video action bar is also one instance where the server-side kill switch was activated.
     */
    private static final String COLD_CONFIG_DATA_HEADER = "X-Youtube-Cold-Config-Data";
    private static final String COLD_HASH_DATA_HEADER = "X-Youtube-Cold-Hash-Data";
    private static final String VISITOR_ID_HEADER = "X-Goog-Visitor-Id";
    private static boolean needFetch = true;

    private static void fetchRequestIfNeeded(String url, Map<String, String> requestHeaders) {
        if (Settings.INNERTUBE_COLD_CONFIG_DATA.isSetToDefault() || Settings.INNERTUBE_COLD_HASH_DATA.isSetToDefault()) {
            if (needFetch) {
                if (requestHeaders != null)  {
                    String visitorId = requestHeaders.get(VISITOR_ID_HEADER);
                    if (Utils.isNotEmpty(visitorId)) {
                        Map<String, String> minHeaders = new HashMap<>();
                        minHeaders.put(VISITOR_ID_HEADER, visitorId);

                        String authorization = requestHeaders.get(AUTHORIZATION_HEADER);
                        if (Utils.isNotEmpty(visitorId)) {
                            minHeaders.put(AUTHORIZATION_HEADER, authorization);
                        }

                        needFetch = false;
                        ConfigRequest.fetchRequest(minHeaders);
                    }
                }
            }
        } else {
            needFetch = false;
        }
    }

    /**
     * Injection point.
     */
    public static Map<String, String> fixVideoActionBar(String url, Map<String, String> requestHeaders) {
        if (FIX_VIDEO_ACTION_BAR && url != null) {
            fetchRequestIfNeeded(url, requestHeaders);

            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            if (path != null && path.contains("next") && requestHeaders != null) {
                if (requestHeaders.get(COLD_CONFIG_DATA_HEADER) != null) {
                    String coldConfigData = Settings.INNERTUBE_COLD_CONFIG_DATA.get();
                    if (Utils.isNotEmpty(coldConfigData)) {
                        requestHeaders.put(COLD_CONFIG_DATA_HEADER, coldConfigData);
                    }
                }
                if (requestHeaders.get(COLD_HASH_DATA_HEADER) != null) {
                    String coldHashData = Settings.INNERTUBE_COLD_HASH_DATA.get();
                    if (Utils.isNotEmpty(coldHashData)) {
                        requestHeaders.put(COLD_HASH_DATA_HEADER, coldHashData);
                    }
                }
            }
        }

        return requestHeaders;
    }

    /**
     * Injection point.
     */
    public static void fixVideoActionBar(ConfigInfoInterface configInfo) {
        if (FIX_VIDEO_ACTION_BAR && configInfo != null) {
            String coldConfigData = Settings.INNERTUBE_COLD_CONFIG_DATA.get();
            if (Utils.isNotEmpty(coldConfigData)) {
                configInfo.patch_setColdConfigData(coldConfigData);
            }
            String coldHashData = Settings.INNERTUBE_COLD_HASH_DATA.get();
            if (Utils.isNotEmpty(coldHashData)) {
                configInfo.patch_setColdHashData(coldHashData);
            }
        }
    }

    /**
     * Injection point.
     */
    public static String getVideoActionBarAppVersionOverride(String original) {
        return FIX_VIDEO_ACTION_BAR
                ? "20.13.41"
                : original;
    }

    /**
     * Injection point.
     */
    public static boolean fixRelatedVideoOverlay(boolean original) {
        if (FIX_VIDEO_ACTION_BAR && !Settings.INNERTUBE_COLD_CONFIG_DATA.isSetToDefault() && !Settings.INNERTUBE_COLD_HASH_DATA.isSetToDefault()) {
            return false;
        }

        return original;
    }
}
