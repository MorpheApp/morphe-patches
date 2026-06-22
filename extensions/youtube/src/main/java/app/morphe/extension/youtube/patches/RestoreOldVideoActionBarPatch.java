/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.youtube.patches;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.youtube.patches.utils.requests.ConfigRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class RestoreOldVideoActionBarPatch {

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
    private static final String VISITOR_ID_HEADER = "X-Goog-Visitor-Id";
    private static boolean needFetch = true;

    static {
        if (FIX_VIDEO_ACTION_BAR) {
            // Must override some spoof stream flags so the headers are available.
            SpoofVideoStreamsPatch.setOverrideSpoofStreamFlagsForHeaders();
        }
    }

    /**
     * Injection point.
     */
    public static void fetchRequest(String url, Map<String, String> requestHeaders) {
        if (FIX_VIDEO_ACTION_BAR && Settings.COLD_CONFIG_DATA.isSetToDefault()) {
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
                        ConfigRequest.clear();
                        ConfigRequest.fetchRequest(minHeaders);
                    }
                }
            } else {
                var request = ConfigRequest.getRequest();
                if (request != null) {
                    String newColdConfigData = request.getConfig();
                    if (Utils.isNotEmpty(newColdConfigData)) {
                        Settings.COLD_CONFIG_DATA.save(newColdConfigData);
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
    public static String fixVideoActionBar(String key, String value) {
        if (FIX_VIDEO_ACTION_BAR && COLD_CONFIG_DATA_HEADER.equals(key)) {
            String coldConfigData = Settings.COLD_CONFIG_DATA.get();
            if (Utils.isNotEmpty(coldConfigData)) {
                return coldConfigData;
            }
        }

        return value;
    }

    /**
     * Injection point.
     */
    public static boolean fixRelatedVideoOverlay(boolean original) {
        if (FIX_VIDEO_ACTION_BAR && !Settings.COLD_CONFIG_DATA.isSetToDefault()) {
            return false;
        }

        return original;
    }
}
