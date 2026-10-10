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

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
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
    @Nullable
    private static volatile Field requestUrlField;

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
    public static void onMediaRequest(Object request, Object callbacks) {
        try {
            if (!isHelperInUse()) {
                return;
            }
            String url = getRequestUrl(request);
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
            if (previous.sawMedia) {
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

    @Nullable
    private static String getRequestUrl(Object request) throws IllegalAccessException {
        Field field = requestUrlField;
        if (field == null) {
            // The URL is the only String field of the request.
            for (Field candidate : request.getClass().getDeclaredFields()) {
                if (candidate.getType() == String.class) {
                    candidate.setAccessible(true);
                    field = candidate;
                    break;
                }
            }
            if (field == null) {
                return null;
            }
            requestUrlField = field;
        }
        return (String) field.get(request);
    }

    /**
     * Injection point.
     * Called with each chunk of a media response, before native code reads it.
     */
    public static void onMediaResponseChunk(Object callbacks, @Nullable ByteBuffer chunk) {
        if (chunk == null || sabrResponseReaders.isEmpty()) {
            return;
        }
        SabrResponseReader reader = sabrResponseReaders.get(callbacks);
        if (reader == null) {
            return;
        }
        try {
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
        LongSetting lastShown = SharedYouTubeSettings.EXTERNAL_POTOKEN_PROVIDER_EXPIRED_DIALOG_LAST_SHOWN;
        Activity activity = Utils.getActivity();
        if (activity == null || !ReminderDialog.isDue(lastShown)) {
            return;
        }
        Logger.printDebug(() -> "YouTube no longer accepts the token made by the helper: " + reason);
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

    /**
     * Reads a UMP response for its stream protection status and whether it has media. Media parts
     * are skipped without copying them, and parts and headers can be split across any number of chunks.
     */
    private static final class SabrResponseReader {
        private static final int PART_MEDIA = 21;
        private static final int PART_STREAM_PROTECTION_STATUS = 58;
        private static final int MAX_STATUS_PART_SIZE = 1024;

        private final byte[] header = new byte[5];
        private int headerLength;
        private long partType = -1;
        private long partRemaining = -1;
        @Nullable
        private byte[] statusPart;
        private int statusPartLength;
        volatile boolean sawMedia;

        /**
         * @return The status of the last status part completed in this chunk, or -1 if none was.
         */
        synchronized int read(ByteBuffer chunk) {
            // A duplicate, so the position native code reads from is not changed.
            ByteBuffer buffer = chunk.duplicate();
            int status = -1;
            while (buffer.hasRemaining()) {
                if (partType < 0 && (partType = readInteger(buffer)) < 0) {
                    break;
                }
                if (partRemaining < 0) {
                    if ((partRemaining = readInteger(buffer)) < 0) {
                        break;
                    }
                    if (partType == PART_MEDIA) {
                        sawMedia = true;
                    } else if (partType == PART_STREAM_PROTECTION_STATUS) {
                        if (partRemaining > MAX_STATUS_PART_SIZE) {
                            throw new IllegalStateException("Status part is too large: " + partRemaining);
                        }
                        statusPart = new byte[(int) partRemaining];
                        statusPartLength = 0;
                    }
                }

                final int count = (int) Math.min(partRemaining, buffer.remaining());
                if (statusPart != null) {
                    buffer.get(statusPart, statusPartLength, count);
                    statusPartLength += count;
                } else {
                    buffer.position(buffer.position() + count);
                }
                partRemaining -= count;

                if (partRemaining == 0) {
                    if (statusPart != null) {
                        status = readStatus(statusPart);
                        statusPart = null;
                    }
                    partType = -1;
                    partRemaining = -1;
                }
            }
            return status;
        }

        /**
         * UMP integers: the leading bits of the first byte give the size, not a continuation bit.
         *
         * @return The integer, or -1 if the chunk ended before it.
         */
        private long readInteger(ByteBuffer buffer) {
            if (headerLength == 0) {
                if (!buffer.hasRemaining()) {
                    return -1;
                }
                header[headerLength++] = buffer.get();
            }
            final int first = header[0] & 0xFF;
            final int size = first < 0x80 ? 1 : first < 0xC0 ? 2 : first < 0xE0 ? 3 : first < 0xF0 ? 4 : 5;
            while (headerLength < size && buffer.hasRemaining()) {
                header[headerLength++] = buffer.get();
            }
            if (headerLength < size) {
                return -1;
            }
            headerLength = 0;

            if (size == 5) {
                // The first byte only gives the size, the value follows in little endian.
                return (header[1] & 0xFFL) | (header[2] & 0xFFL) << 8
                        | (header[3] & 0xFFL) << 16 | (header[4] & 0xFFL) << 24;
            }
            long value = first & (0xFF >> size);
            for (int i = 1; i < size; i++) {
                value |= (header[i] & 0xFFL) << (8 - size + 8 * (i - 1));
            }
            return value;
        }

        /**
         * @return Field 1 of the StreamProtectionStatus message, or -1 if it is missing.
         */
        private static int readStatus(byte[] part) {
            int position = 0;
            while (position < part.length) {
                final int tag = part[position++] & 0xFF;
                if ((tag & 7) != 0) {
                    // The message has only varint fields.
                    return -1;
                }
                long value = 0;
                for (int shift = 0; position < part.length; shift += 7) {
                    final byte b = part[position++];
                    value |= (long) (b & 0x7F) << shift;
                    if (b >= 0) {
                        break;
                    }
                }
                if (tag >> 3 == 1) {
                    return (int) value;
                }
            }
            return -1;
        }
    }
}
