/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.patches;

import android.net.Uri;

import com.google.protobuf.MessageLite;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
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

    /**
     * Interface to use obfuscated methods.
     */
    public interface RequestInterface {
        // Method is added during patching.
        String patch_getEndpoint();
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
    /**
     * Field number of the continuation token in the body of 'next' requests.
     * Watch page requests have no continuation. Comment requests do.
     */
    private static final int NEXT_REQUEST_CONTINUATION_FIELD = 8;
    /**
     * Time when the body of a watch page 'next' request was last built, or zero if none is pending.
     * Comment requests also use the 'next' endpoint, and overriding their config
     * prevents newly posted comments from showing until the comment sorting is changed.
     */
    private static volatile long watchNextRequestTime;
    private static final long WATCH_NEXT_REQUEST_TIMEOUT_MILLISECONDS = 10_000;

    private static void fetchRequestIfNeeded(String url, Map<String, String> requestHeaders) {
        if (Settings.INNERTUBE_COLD_CONFIG_DATA.isSetToDefault() || Settings.INNERTUBE_COLD_HASH_DATA.isSetToDefault()) {
            if (needFetch) {
                if (requestHeaders != null)  {
                    String visitorId = requestHeaders.get(VISITOR_ID_HEADER);
                    if (Utils.isNotEmpty(visitorId)) {
                        Map<String, String> minHeaders = new HashMap<>();
                        minHeaders.put(VISITOR_ID_HEADER, visitorId);

                        String authorization = requestHeaders.get(AUTHORIZATION_HEADER);
                        if (Utils.isNotEmpty(authorization)) {
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
     * Turns off a feature flag that interferes with overriding config.
     */
    public static boolean useMediaSessionFeatureFlag(boolean original) {
        if (FIX_VIDEO_ACTION_BAR) {
            return false;
        }
        return original;
    }


    /**
     * Injection point.
     */
    public static Map<String, String> fixVideoActionBar(String url, Map<String, String> requestHeaders) {
        if (FIX_VIDEO_ACTION_BAR && url != null) {
            fetchRequestIfNeeded(url, requestHeaders);

            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            if (path != null && path.contains("next") && requestHeaders != null && isWatchNextRequest()) {
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
     * Whether a 'next' request is for the watch page, and not for comments.
     */
    private static boolean isWatchNextRequest() {
        final long requestTime = watchNextRequestTime;
        watchNextRequestTime = 0;
        return requestTime != 0
                && System.currentTimeMillis() - requestTime < WATCH_NEXT_REQUEST_TIMEOUT_MILLISECONDS;
    }

    /**
     * Injection point.
     * Called when the body of an InnerTube request is built.
     */
    public static void onBuildRequestBody(MessageLite body, RequestInterface request) {
        try {
            if (FIX_VIDEO_ACTION_BAR && body != null && request != null
                    && "next".equals(request.patch_getEndpoint())
                    && !hasTopLevelField(body.toByteArray(), NEXT_REQUEST_CONTINUATION_FIELD)) {
                watchNextRequestTime = System.currentTimeMillis();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onBuildRequestBody failure", ex);
        }
    }

    /**
     * @return If the serialized protocol buffer message has a top level field with the given number.
     *         If the message cannot be read, true is returned.
     */
    private static boolean hasTopLevelField(byte[] message, int fieldNumber) {
        int position = 0;
        final int end = message.length;
        while (position < end) {
            long tag = 0;
            int shift = 0;
            int value;
            do {
                // A varint is at most 10 bytes; a larger shift would wrap (shift & 63) and corrupt the tag.
                if (position >= end || shift >= 64) return true;
                value = message[position++] & 0xFF;
                tag |= (long) (value & 0x7F) << shift;
                shift += 7;
            } while ((value & 0x80) != 0);

            if ((tag >>> 3) == fieldNumber) return true;

            switch ((int) (tag & 0x7)) {
                case 0: // Varint.
                    do {
                        if (position >= end) return true;
                    } while ((message[position++] & 0x80) != 0);
                    break;
                case 1: // 64-bit.
                    // Truncated message: subtracting avoids int overflow and stops position running past the end.
                    if (end - position < 8) return true;
                    position += 8;
                    break;
                case 2: // Length delimited.
                    long length = 0;
                    shift = 0;
                    do {
                        // Same 10-byte varint limit as the tag loop above.
                        if (position >= end || shift >= 64) return true;
                        value = message[position++] & 0xFF;
                        length |= (long) (value & 0x7F) << shift;
                        shift += 7;
                    } while ((value & 0x80) != 0);
                    // A 10-byte varint can set bit 63, making length negative and moving position backwards.
                    if (length < 0 || length > end - position) return true;
                    position += (int) length;
                    break;
                case 5: // 32-bit.
                    // Truncated message: same overflow-safe check as the 64-bit case.
                    if (end - position < 4) return true;
                    position += 4;
                    break;
                default: // Groups (3, 4) and invalid wire types (6, 7): treat as unreadable.
                    return true;
            }
        }
        return false;
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
        if (FIX_VIDEO_ACTION_BAR
                && !Settings.INNERTUBE_COLD_CONFIG_DATA.isSetToDefault()
                && !Settings.INNERTUBE_COLD_HASH_DATA.isSetToDefault()) {
            return false;
        }

        return original;
    }
}
