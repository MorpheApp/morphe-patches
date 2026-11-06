/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.SabrResponseReader;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.ui.ReminderDialog;

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
    /**
     * The status YouTube sends once it no longer accepts the token of the app.
     */
    private static final int ATTESTATION_REQUIRED = 3;
    /**
     * YouTube Music sends no status, its server only stops sending media and asks to retry later.
     * Normal playback requests media only when it needs it, so every response has some.
     */
    private static final int RESPONSES_WITHOUT_MEDIA_LIMIT = 5;

    /**
     * Readers of the SABR responses, by the native callbacks of their request.
     */
    private static final Map<Object, SabrResponseReader> sabrResponseReaders =
            Collections.synchronizedMap(new WeakHashMap<>());
    @Nullable
    private static SabrResponseReader lastSabrResponseReader;
    private static int responsesWithoutMedia;
    @Nullable
    private static volatile Boolean helperInUse;

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

    /**
     * Settings that change this restart the app, so it is checked only once.
     */
    private static boolean isHelperInUse() {
        Boolean inUse = helperInUse;
        if (inUse == null) {
            inUse = SharedYouTubeSettings.EXTERNAL_POTOKEN_PROVIDER.get()
                    && SpoofVideoStreamsPatch.isPatchIncluded()
                    && isPoTokenProviderAvailable();
            helperInUse = inUse;
        }
        return inUse;
    }

    /**
     * Injection point.
     * Called off the main thread when a media request starts.
     */
    public static void onMediaRequest(MediaHttpRequest request, Object callbacks) {
        try {
            if (!isHelperInUse()) {
                return;
            }
            String url = request.patch_getUrl();
            // Live streams can have responses without media while the stream waits for new segments.
            if (url != null && url.contains("/videoplayback") && url.contains("sabr=1")
                    && !url.contains("yt_live_broadcast") && !url.contains("yt_premiere_broadcast")) {
                onSabrRequest(callbacks);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onMediaRequest failure", ex);
        }
    }

    /**
     * A new request means the previous response is complete.
     */
    private static synchronized void onSabrRequest(Object callbacks) {
        SabrResponseReader previous = lastSabrResponseReader;
        if (previous != null) {
            if (previous.sawMedia()) {
                responsesWithoutMedia = 0;
            } else if (++responsesWithoutMedia >= RESPONSES_WITHOUT_MEDIA_LIMIT) {
                responsesWithoutMedia = 0;
                onTokenRejected("media responses no longer contain media");
            }
        }
        SabrResponseReader reader = new SabrResponseReader();
        sabrResponseReaders.put(callbacks, reader);
        lastSabrResponseReader = reader;
    }

    /**
     * Injection point.
     * Called with each chunk of a media response, before native code reads it.
     */
    public static void onMediaResponseChunk(Object callbacks, @Nullable ByteBuffer chunk) {
        if (chunk == null) {
            return;
        }
        try {
            if (sabrResponseReaders.isEmpty()) {
                return;
            }
            SabrResponseReader reader = sabrResponseReaders.get(callbacks);
            if (reader == null) {
                return;
            }
            if (reader.read(chunk) == ATTESTATION_REQUIRED) {
                sabrResponseReaders.remove(callbacks);
                onTokenRejected("attestation is required");
            }
        } catch (Exception ex) {
            sabrResponseReaders.remove(callbacks);
            Logger.printInfo(() -> "Could not read media response", ex);
        }
    }

    /**
     * Playback stops once the buffered media runs out, so offer to turn on 'Spoof video streams'.
     */
    private static synchronized void onTokenRejected(String reason) {
        Logger.printDebug(() -> "YouTube no longer accepts the token made by the helper: " + reason);
        // Log only until the check stops firing while the token still works.
        if (!SharedYouTubeSettings.EXTERNAL_POTOKEN_PROVIDER_EXPIRED_DIALOG.get()) {
            return;
        }
        LongSetting lastShown = SharedYouTubeSettings.EXTERNAL_POTOKEN_PROVIDER_EXPIRED_DIALOG_LAST_SHOWN;
        Activity activity = Utils.getActivity();
        if (activity == null || !ReminderDialog.isDue(lastShown)) {
            return;
        }
        ReminderDialog.show(
                activity,
                lastShown,
                str("morphe_external_potoken_provider_expired_dialog_title"),
                str("morphe_external_potoken_provider_expired_dialog_message"),
                str("morphe_external_potoken_provider_expired_dialog_turn_on"),
                () -> {
                    SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.save(true);
                    Utils.restartApp(activity);
                }
        );
    }
}
