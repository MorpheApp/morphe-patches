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

import static app.morphe.extension.shared.patches.AppCheckPatch.IS_YOUTUBE_MUSIC;

import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Locale;

import app.morphe.extension.shared.Logger;

@SuppressWarnings({"ConstantLocale", "deprecation"})
public enum ClientType {
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
    ANDROID_MUSIC_NO_SDK(
            21,
            "ANDROID_MUSIC",
            ANDROID_MUSIC_REEL.deviceMake,
            ANDROID_MUSIC_REEL.deviceModel,
            ANDROID_MUSIC_REEL.osName,
            ANDROID_MUSIC_REEL.osVersion,
            "7.12.52",
            null,
            "com.google.android.apps.youtube.music/7.12.52 (Linux; U; Android " + Build.VERSION.RELEASE + ") gzip",
            IS_YOUTUBE_MUSIC,
            IS_YOUTUBE_MUSIC,
            false,
            false,
            false,
            false,
            false,
            "Android Music No SDK"
    ),
    /**
     * Video not playable: Kids.
     * AV1 codec not available.
     */
    ANDROID_VR_SABR(
            28,
            "ANDROID_VR",
            "com.google.android.apps.youtube.vr.pico",
            "Pico",
            "A8110", // PICO 4.
            "Android",
            "10",
            "29",
            "5.13.7",
            "1.73.21",
            null,
            false,
            true,
            true,
            true,
            true,
            true,
            true,
            "Android VR"
    ),
    /**
     * Same as {@code ANDROID_VR_SABR} but supports dash streams.
     */
    ANDROID_VR_DASH(
            ANDROID_VR_SABR.id,
            ANDROID_VR_SABR.clientName,
            ANDROID_VR_SABR.packageName,
            ANDROID_VR_SABR.deviceMake,
            ANDROID_VR_SABR.deviceModel,
            ANDROID_VR_SABR.osName,
            ANDROID_VR_SABR.osVersion,
            ANDROID_VR_SABR.androidSdkVersion,
            ANDROID_VR_SABR.buildID,
            "1.64.34",
            ANDROID_VR_SABR.clientPlatform,
            ANDROID_VR_SABR.canLogin,
            ANDROID_VR_SABR.requireLogin,
            false,
            ANDROID_VR_SABR.supportsOAuth2,
            ANDROID_VR_SABR.supportsVRImmersiveMode,
            false,
            ANDROID_VR_SABR.usePlayerEndpoint,
            "Android VR Downgraded"
    ),
    /**
     * Same as {@code ANDROID_VR_SABR} but supports AV1 codec.
     */
    ANDROID_XR_SABR(
            ANDROID_VR_SABR.id,
            ANDROID_VR_SABR.clientName,
            "com.google.android.apps.youtube.xr",
            "Samsung",
            "SM-I610", // Galaxy XR.
            ANDROID_VR_SABR.osName,
            "14",
            "34",
            "UML1.250710.002.A1",
            "1.73.21",
            ANDROID_VR_SABR.clientPlatform,
            ANDROID_VR_SABR.canLogin,
            ANDROID_VR_SABR.requireLogin,
            ANDROID_VR_SABR.supportsMultiAudioTracks,
            ANDROID_VR_SABR.supportsOAuth2,
            ANDROID_VR_SABR.supportsVRImmersiveMode,
            ANDROID_VR_SABR.requireSABR,
            ANDROID_VR_SABR.usePlayerEndpoint,
            "Android XR"
    ),
    /**
     * Same as {@code ANDROID_XR_SABR} but supports dash streams.
     */
    ANDROID_XR_DASH(
            ANDROID_XR_SABR.id,
            ANDROID_XR_SABR.clientName,
            ANDROID_XR_SABR.packageName,
            ANDROID_XR_SABR.deviceMake,
            ANDROID_XR_SABR.deviceModel,
            ANDROID_XR_SABR.osName,
            ANDROID_XR_SABR.osVersion,
            ANDROID_XR_SABR.androidSdkVersion,
            ANDROID_XR_SABR.buildID,
            "1.69.27",
            ANDROID_XR_SABR.clientPlatform,
            ANDROID_XR_SABR.canLogin,
            ANDROID_XR_SABR.requireLogin,
            false,
            ANDROID_XR_SABR.supportsOAuth2,
            ANDROID_XR_SABR.supportsVRImmersiveMode,
            false,
            ANDROID_XR_SABR.usePlayerEndpoint,
            "Android XR Downgraded"
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
    TV_SABR(
            7,
            "TVHTML5",
            "Sony",
            "PS4",
            "PlayStation 4",
            "",
            "7.20260707.07.00",
            "GAME_CONSOLE",
            "Mozilla/5.0 (PS4; Leanback Shell) Gecko/20100101 Firefox/65.0 LeanbackShell/01.00.01.75 Sony PS4/ (PS4, , no, CH)",
            true,
            false,
            true,
            false,
            true,
            false,
            true,
            "TV"
    ),
    /**
     * Same as {@code TV_SABR} but supports dash streams.
     * This client cannot be selected in the settings and is used only for livestreams.
     */
    TV_DASH(
            TV_SABR.id,
            TV_SABR.clientName,
            "Samsung",
            "SmartTV",
            "Tizen",
            "2.4.0",
            "5.20150304",
            "TV",
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
            TV_SABR.canLogin,
            TV_SABR.requireLogin,
            TV_SABR.supportsMultiAudioTracks,
            TV_SABR.supportsVRImmersiveMode,
            TV_SABR.requireJS,
            TV_SABR.requirePoToken,
            false,
            "TV Downgraded"
    ),
    /**
     * Video not playable: None.
     * AV1 codec available.
     */
    TV_SIMPLY(
            75,
            "TVHTML5_SIMPLY",
            TV_SABR.deviceMake,
            TV_SABR.deviceModel,
            TV_SABR.osName,
            TV_SABR.osVersion,
            "1.1",
            TV_SABR.clientPlatform,
            TV_SABR.userAgent,
            true,
            // This client requires a PoToken for logout.
            false,
            TV_SABR.supportsMultiAudioTracks,
            TV_SABR.supportsVRImmersiveMode,
            TV_SABR.requireJS,
            true,
            false,
            "TV Simply"
    ),
    /**
     * Video not playable: Kids, Paid, Movie, Private, Age-restricted.
     * AV1 codec available.
     * May stop working at any time.
     */
    VISIONOS_1_03(
            101,
            "VISIONOS",
            "Apple",
            "RealityDevice17,1",
            "visionOS",
            "26.6.1",
            "1.03",
            null,
            "com.google.visionosyoutube/1.03 (RealityDevice17,1; U; CPU visionOS 26_6_1 like Mac OS X; en_US) gzip",
            false,
            false,
            true,
            true,
            false,
            false,
            false,
            "visionOS 1.03"
    ),
    /**
     * Video not playable: Kids, Paid, Movie, Private, Age-restricted.
     * AV1 codec not available.
     * May stop working at any time.
     */
    VISIONOS_1_02(
            VISIONOS_1_03.id,
            VISIONOS_1_03.clientName,
            VISIONOS_1_03.deviceMake,
            "RealityDevice14,1",
            VISIONOS_1_03.osName,
            VISIONOS_1_03.osVersion,
            "1.02",
            VISIONOS_1_03.clientPlatform,
            "com.google.visionosyoutube/1.02 (RealityDevice14,1; U; CPU visionOS 26_6_1 like Mac OS X; en_US) gzip",
            VISIONOS_1_03.canLogin,
            VISIONOS_1_03.requireLogin,
            VISIONOS_1_03.supportsMultiAudioTracks,
            VISIONOS_1_03.supportsVRImmersiveMode,
            VISIONOS_1_03.requireJS,
            VISIONOS_1_03.requirePoToken,
            VISIONOS_1_03.requireSABR,
            "visionOS 1.02"
    );

    /**
     * YouTube
     * <a href="https://github.com/zerodytrash/YouTube-Internal-Clients?tab=readme-ov-file#clients">client type</a>
     */
    public final int id;

    public final String clientName;

    /**
     * App package name.
     * Field is empty if not applicable.
     */
    @NonNull
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
     * Field is empty if not applicable.
     */
    @NonNull
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
     * If the client requires PoToken.
     */
    public final boolean requirePoToken;

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
                "%s/%s (Linux; U; Android %s; %s; %s Build/%s)",
                packageName,
                clientVersion,
                osVersion,
                defaultLocale,
                deviceModel,
                buildId
        );
        Logger.printDebug(() -> "userAgent: " + this.userAgent);

        requireJS = false;
        requirePoToken = false;
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
               boolean requirePoToken,
               boolean requireSABR,
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
        this.requirePoToken = requirePoToken;
        this.requireSABR = requireSABR;
        this.friendlyName = friendlyName;

        androidSdkVersion = "";
        buildID = null;
        packageName = "";
        supportsOAuth2 = false;
        usePlayerEndpoint = true;
    }
}
