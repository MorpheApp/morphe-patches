/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.spoof;

import static app.morphe.extension.shared.patches.AppCheckPatch.IS_YOUTUBE;
import static app.morphe.extension.shared.patches.AppCheckPatch.IS_YOUTUBE_MUSIC;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Objects;

import app.morphe.extension.shared.Logger;

@SuppressWarnings({"ConstantLocale", "deprecation"})
public enum ClientType {
    /**
     * Video not playable: None.
     * AV1 codec available.
     */
    ANDROID_REEL_AUTH(
            3,
            "ANDROID",
            "com.google.android.youtube",
            Build.MANUFACTURER,
            Build.MODEL,
            "Android",
            Build.VERSION.RELEASE,
            String.valueOf(Build.VERSION.SDK_INT),
            Build.ID,
            // A hardcoded client version is used for YouTube Music.
            "20.47.62",
            null,
            IS_YOUTUBE,
            IS_YOUTUBE,
            true,
            false,
            true,
            true,
            false,
            "Android Reel auth"
    ),
    /**
     * Video not playable: Paid, Movie, Private, Age-restricted.
     * AV1 codec available.
     */
    ANDROID_REEL_NO_AUTH(
            ANDROID_REEL_AUTH.id,
            ANDROID_REEL_AUTH.clientName,
            Objects.requireNonNull(ANDROID_REEL_AUTH.packageName),
            ANDROID_REEL_AUTH.deviceMake,
            ANDROID_REEL_AUTH.deviceModel,
            ANDROID_REEL_AUTH.osName,
            ANDROID_REEL_AUTH.osVersion,
            Objects.requireNonNull(ANDROID_REEL_AUTH.androidSdkVersion),
            ANDROID_REEL_AUTH.buildID,
            ANDROID_REEL_AUTH.clientVersion,
            ANDROID_REEL_AUTH.clientPlatform,
            false,
            false,
            ANDROID_REEL_AUTH.supportsMultiAudioTracks,
            ANDROID_REEL_AUTH.supportsOAuth2,
            ANDROID_REEL_AUTH.supportsVRImmersiveMode,
            ANDROID_REEL_AUTH.requireSABR,
            ANDROID_REEL_AUTH.usePlayerEndpoint,
            "Android Reel no auth"
    ),
    /**
     * Video not playable: None.
     * For YouTube Music only.
     */
    ANDROID_MUSIC_REEL(
            21,
            "ANDROID_MUSIC",
            "com.google.android.apps.youtube.music",
            Build.MANUFACTURER,
            Build.MODEL,
            "Android",
            Build.VERSION.RELEASE,
            String.valueOf(Build.VERSION.SDK_INT),
            Build.ID,
            "9.05.52",
            null,
            IS_YOUTUBE_MUSIC,
            IS_YOUTUBE_MUSIC,
            false,
            false,
            false,
            true,
            false,
            "Android Music Reel"
    ),
    /**
     * Video not playable: Kids.
     * AV1 codec available.
     */
    // https://dumps.tadiphone.dev/dumps/oculus/eureka
    ANDROID_VR_1_74(
            28,
            "ANDROID_VR",
            "com.google.android.apps.youtube.vr.oculus",
            "Oculus",
            "Quest 3",
            "Android",
            "14",
            "34",
            "UP1A.231005.007.A1",
            "1.74.19",
            null,
            false,
            false,
            true,
            true,
            true,
            true,
            true,
            "Android VR 1.74"
    ),
    /**
     * Video not playable: Kids.
     * AV1 codec not available.
     */
    // https://dumps.tadiphone.dev/dumps/oculus/monterey
    ANDROID_VR_1_73(
            ANDROID_VR_1_74.id,
            ANDROID_VR_1_74.clientName,
            Objects.requireNonNull(ANDROID_VR_1_74.packageName),
            ANDROID_VR_1_74.deviceMake,
            "Quest",
            ANDROID_VR_1_74.osName,
            "10",
            "29",
            "QQ3A.200805.001",
            "1.73.24",
            ANDROID_VR_1_74.clientPlatform,
            ANDROID_VR_1_74.canLogin,
            ANDROID_VR_1_74.requireLogin,
            ANDROID_VR_1_74.supportsMultiAudioTracks,
            ANDROID_VR_1_74.supportsOAuth2,
            ANDROID_VR_1_74.supportsVRImmersiveMode,
            ANDROID_VR_1_74.requireSABR,
            ANDROID_VR_1_74.usePlayerEndpoint,
            "Android VR 1.73"
    ),
    /**
     * Video not playable: Livestream.
     * AV1 codec and HDR codec are not available, and the maximum resolution is 720p.
     */
    // https://dumps.tadiphone.dev/dumps/google/mustang
    ANDROID_CREATOR(
            14,
            "ANDROID_CREATOR",
            "com.google.android.apps.youtube.creator",
            "Google",
            "Pixel 10 Pro XL",
            "Android",
            "16",
            "36",
            "BD3A.251005.003.W3",
            "26.10.000",
            null,
            true,
            true,
            false,
            false,
            false,
            false,
            true,
            "Android Studio"
    ),
    /**
     * Video not playable: None.
     * AV1 codec available.
     */
    TV(7,
            "TVHTML5",
            "Samsung",
            "SmartTV",
            "Tizen",
            "2.4.0",
            "5.20150304",
            "TV",
            // Currently, it is the only User-Agent available for signed out among TV clients, but sign in is still required for certain IP bands or countries.
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
            true,
            false,
            true,
            false,
            true,
            "TV"
    ),
    /**
     * Video not playable: Kids, Paid, Movie, Private, Age-restricted.
     * AV1 codec available.
     * May stop working at any time.
     */
    VISIONOS(101,
            "VISIONOS",
            "Apple",
            "RealityDevice17,1",
            "visionOS",
            "26.5.23O471",
            "1.03",
            null,
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            false,
            false,
            true,
            true,
            false,
            "visionOS"
    );

    /**
     * YouTube
     * <a href="https://github.com/zerodytrash/YouTube-Internal-Clients?tab=readme-ov-file#clients">client type</a>
     */
    public final int id;

    public final String clientName;

    /**
     * App package name.
     */
    @Nullable
    private final String packageName;

    /**
     * Player user-agent.
     */
    public final String userAgent;

    /**
     * Device model, equivalent to {@link Build#MANUFACTURER} (System property: ro.product.vendor.manufacturer)
     */
    public final String deviceMake;

    /**
     * Device model, equivalent to {@link Build#MODEL} (System property: ro.product.vendor.model)
     */
    public final String deviceModel;

    /**
     * Device OS name.
     */
    public final String osName;

    /**
     * Device OS version.
     */
    public final String osVersion;

    /**
     * Android SDK version, equivalent to {@link Build.VERSION#SDK} (System property: ro.build.version.sdk)
     * Field is null if not applicable.
     */
    @Nullable
    public final String androidSdkVersion;

    /**
     * Device Build id.
     */
    public final String buildID;

    /**
     * App version.
     */
    public final String clientVersion;

    /**
     * Client platform enum.
     */
    public final String clientPlatform;

    /**
     * If the client can access the API logged in.
     */
    public final boolean canLogin;

    /**
     * If the client should use authentication if available.
     */
    public final boolean requireLogin;

    /**
     * If the client supports oauth2.0 for limited-input device.
     */
    public final boolean supportsOAuth2;

    /**
     * If the client supports multiple audio tracks.
     */
    public final boolean supportsMultiAudioTracks;

    /**
     * If the client supports 360° VR immersive mode.
     */
    public final boolean supportsVRImmersiveMode;

    /**
     * The streaming url has an obfuscated 'n' parameter.
     * If true, JavaScript must be fetched to decrypt the 'n' parameter.
     */
    public final boolean requireJS;

    /**
     * If the client require SABR.
     */
    public final boolean requireSABR;

    /**
     * Whether to use the '/player' endpoint.
     */
    public final boolean usePlayerEndpoint;

    /**
     * Friendly name displayed in stats for nerds.
     */
    public final String friendlyName;

    /**
     * Android constructor.
     */
    ClientType(int id,
               String clientName,
               @NonNull String packageName,
               String deviceMake,
               String deviceModel,
               String osName,
               String osVersion,
               @NonNull String androidSdkVersion,
               @NonNull String buildId,
               String clientVersion,
               String clientPlatform,
               boolean canLogin,
               boolean requireLogin,
               boolean supportsMultiAudioTracks,
               boolean supportsOAuth2,
               boolean supportsVRImmersiveMode,
               boolean requireSABR,
               boolean usePlayerEndpoint,
               String friendlyName) {
        this.id = id;
        this.clientName = clientName;
        this.packageName = packageName;
        this.deviceMake = deviceMake;
        this.deviceModel = deviceModel;
        this.osName = osName;
        this.osVersion = osVersion;
        this.androidSdkVersion = androidSdkVersion;
        this.buildID = buildId;
        this.clientVersion = clientVersion;
        this.clientPlatform = clientPlatform;
        this.canLogin = canLogin;
        this.requireLogin = requireLogin;
        this.requireSABR = requireSABR;
        this.supportsMultiAudioTracks = supportsMultiAudioTracks;
        this.supportsOAuth2 = supportsOAuth2;
        this.supportsVRImmersiveMode = supportsVRImmersiveMode;
        this.usePlayerEndpoint = usePlayerEndpoint;
        this.friendlyName = friendlyName;

        Locale defaultLocale = Locale.getDefault();
        this.userAgent = String.format(Locale.ENGLISH,
                "%s/%s (Linux; U; Android %s; %s; %s; Build/%s)",
                packageName,
                clientVersion,
                osVersion,
                defaultLocale,
                deviceModel,
                buildId
        );
        Logger.printDebug(() -> "userAgent: " + this.userAgent);

        requireJS = false;
    }

    ClientType(int id,
               String clientName,
               String deviceMake,
               String deviceModel,
               String osName,
               String osVersion,
               String clientVersion,
               String clientPlatform,
               String userAgent,
               boolean canLogin,
               boolean requireLogin,
               boolean supportsMultiAudioTracks,
               boolean supportsVRImmersiveMode,
               boolean requireJS,
               String friendlyName) {
        this.id = id;
        this.clientName = clientName;
        this.deviceMake = deviceMake;
        this.deviceModel = deviceModel;
        this.osName = osName;
        this.osVersion = osVersion;
        this.clientVersion = clientVersion;
        this.clientPlatform = clientPlatform;
        this.userAgent = userAgent;
        this.canLogin = canLogin;
        this.requireLogin = requireLogin;
        this.supportsMultiAudioTracks = supportsMultiAudioTracks;
        this.supportsVRImmersiveMode = supportsVRImmersiveMode;
        this.requireJS = requireJS;
        this.friendlyName = friendlyName;

        androidSdkVersion = null;
        buildID = null;
        packageName = null;
        requireSABR = false;
        supportsOAuth2 = false;
        usePlayerEndpoint = true;
    }
}
