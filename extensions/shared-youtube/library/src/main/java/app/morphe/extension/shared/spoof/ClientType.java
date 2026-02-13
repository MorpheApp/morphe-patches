package app.morphe.extension.shared.spoof;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Objects;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("ConstantLocale")
public enum ClientType {
    /**
     * Video not playable: Kids, Paid, Movie, Private, Age-restricted.
     * Uses non-adaptive bitrate.
     * AV1 codec available.
     */
    // https://dumps.tadiphone.dev/dumps/oculus/eureka
    ANDROID_VR_1_54_20(
            28,
            "ANDROID_VR",
            "com.google.android.apps.youtube.vr.oculus",
            "Oculus",
            "Quest 3",
            "Android",
            "14",
            "34",
            "UP1A.231005.007.A1",
            "122.0.6238.3",
            "1.54.20",
            null,
            false,
            false,
            false,
            true,
            false,
            "Android VR 1.54"
    ),
    /**
     * Uses non adaptive bitrate.
     * AV1 codec not available.
     */
    // https://dumps.tadiphone.dev/dumps/oculus/monterey
    ANDROID_VR_1_47_48(
            ANDROID_VR_1_54_20.id,
            ANDROID_VR_1_54_20.clientName,
            Objects.requireNonNull(ANDROID_VR_1_54_20.packageName),
            ANDROID_VR_1_54_20.deviceMake,
            "Quest",
            ANDROID_VR_1_54_20.osName,
            "10",
            "29",
            "QQ3A.200805.001",
            "113.0.5672.24",
            "1.47.48",
            ANDROID_VR_1_54_20.clientPlatform,
            ANDROID_VR_1_54_20.canLogin,
            ANDROID_VR_1_54_20.requireLogin,
            ANDROID_VR_1_54_20.supportsMultiAudioTracks,
            ANDROID_VR_1_54_20.supportsOAuth2,
            ANDROID_VR_1_54_20.requireJS,
            "Android VR 1.47"
    ),
    /**
     * Video not playable: Livestream.
     * Uses non-adaptive bitrate.
     * AV1 codec and HDR codec are not available, and the maximum resolution is 720p.
     */
    // https://dumps.tadiphone.dev/dumps/google/barbet
    ANDROID_CREATOR(
            14,
            "ANDROID_CREATOR",
            "com.google.android.apps.youtube.creator",
            "Google",
            "Pixel 9 Pro Fold",
            "Android",
            "15",
            "35",
            "AP3A.241005.015.A2",
            "132.0.6779.0",
            "23.47.101",
            null,
            true,
            true,
            false,
            false,
            false,
            "Android Studio"
    ),
    /**
     * Video not playable: None.
     * Uses non adaptive bitrate.
     * AV1 codec available.
     */
    TV(7,
            "TVHTML5",
            "Samsung",
            "SmartTV",
            "Tizen",
            "4.0.0.2",
            "5.20150304",
            "TV",
            // Currently, it is the only User-Agent available for signed out among TV clients, but sign in is still required for certain IP bands or countries.
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            true,
            false,
            true,
            false,
            true,
            "TV"
    ),
    /**
     * Internal YT client for an unreleased YT client. May stop working at any time.
     */
    VISIONOS(101,
            "VISIONOS",
            "Apple",
            "RealityDevice14,1",
            "visionOS",
            "1.3.21O771",
            "0.1",
            null,
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            false,
            false,
            false,
            false,
            false,
            "visionOS"
    ),
    /**
     * Here only to migrate data.
     */
    @Deprecated
    TV_SIMPLY(75,
            "TVHTML5_SIMPLY",
            "Microsoft",
            "Xbox 360",
            "Xbox",
            "6.1",
            "1.0",
            "GAME_CONSOLE",
            "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; Trident/5.0; Xbox)",
            true,
            // PoToken is required to play videos while signed out.
            true,
            true,
            false,
            true,
            "TV Simply"
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
     * The streaming url has an obfuscated 'n' parameter.
     * If true, JavaScript must be fetched to decrypt the 'n' parameter.
     */
    public final boolean requireJS;

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
               @NonNull String cronetVersion,
               String clientVersion,
               String clientPlatform,
               boolean canLogin,
               boolean requireLogin,
               boolean supportsMultiAudioTracks,
               boolean supportsOAuth2,
               boolean requireJS,
               String friendlyName) {
        this.id = id;
        this.clientName = clientName;
        this.packageName = packageName;
        this.deviceMake = deviceMake;
        this.deviceModel = deviceModel;
        this.osName = osName;
        this.osVersion = osVersion;
        this.androidSdkVersion = androidSdkVersion;
        this.clientVersion = clientVersion;
        this.clientPlatform = clientPlatform;
        this.canLogin = canLogin;
        this.requireLogin = requireLogin;
        this.supportsMultiAudioTracks = supportsMultiAudioTracks;
        this.supportsOAuth2 = supportsOAuth2;
        this.requireJS = requireJS;
        this.friendlyName = friendlyName;

        Locale defaultLocale = Locale.getDefault();
        this.userAgent = String.format(Locale.ENGLISH,
                "%s/%s (Linux; U; Android %s; %s; %s; Build/%s; Cronet/%s)",
                packageName,
                clientVersion,
                osVersion,
                defaultLocale,
                deviceModel,
                Objects.requireNonNull(buildId),
                Objects.requireNonNull(cronetVersion)
        );
        Logger.printDebug(() -> "userAgent: " + this.userAgent);
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
               boolean supportsOAuth2,
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
        this.supportsOAuth2 = supportsOAuth2;
        this.requireJS = requireJS;
        this.friendlyName = friendlyName;
        this.packageName = null;
        this.androidSdkVersion = null;
    }
}
