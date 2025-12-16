package app.morphe.extension.shared.spoof;

import static app.morphe.extension.shared.patches.AppCheckPatch.IS_YOUTUBE;
import static app.morphe.extension.shared.patches.AppCheckPatch.IS_YOUTUBE_MUSIC;

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
     * Uses non adaptive bitrate.
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
            false,
            false,
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
            ANDROID_VR_1_54_20.canLogin,
            ANDROID_VR_1_54_20.requireLogin,
            ANDROID_VR_1_54_20.supportsMultiAudioTracks,
            "Android VR 1.47"
    ),
    /**
     * Video not playable in YouTube: None.
     * Video not playable in YouTube Music: Paid, Movie, Private, Age-restricted.
     * Uses adaptive bitrate.
     */
    ANDROID_NO_SDK(
            3,
            "ANDROID",
            "",
            "",
            "Android",
            Build.VERSION.RELEASE,
            "20.05.46",
            "com.google.android.youtube/20.05.46 (Linux; U; Android " + Build.VERSION.RELEASE + ") gzip",
            // Due to Google API changes in September 2025, Authorization issued with a different 'client_sig' can no longer be used.
            // That is, this client must use an OAuth2 token issued by Android YouTube (com.google.android.youtube).
            IS_YOUTUBE,
            false,
            true,
            "Android No SDK"
    ),
    /**
     * Video not playable in YouTube: All videos (This client requires login, but cannot log in with YouTube's access token).
     * Video not playable in YouTube Music: None.
     * Uses non adaptive bitrate.
     */
    ANDROID_MUSIC_NO_SDK(
            21,
            "ANDROID_MUSIC",
            ANDROID_NO_SDK.deviceMake,
            ANDROID_NO_SDK.deviceModel,
            ANDROID_NO_SDK.osName,
            ANDROID_NO_SDK.osVersion,
            "7.12.52",
            "com.google.android.apps.youtube.music/7.12.52 (Linux; U; Android " + Build.VERSION.RELEASE + ") gzip",
            // Due to Google API changes in September 2025, Authorization issued with a different 'client_sig' can no longer be used.
            // That is, this client must use an OAuth2 token issued by Android YouTube Music (com.google.android.apps.youtube.music).
            IS_YOUTUBE_MUSIC,
            true,
            false,
            "Android Music No SDK"
    ),
    /**
     * Video not playable: Livestream.
     * Uses non adaptive bitrate.
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
            true,
            true,
            false,
            "Android Studio"
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
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            false,
            false,
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
     * App version.
     */
    public final String clientVersion;

    /**
     * If the client can access the API logged in.
     */
    public final boolean canLogin;

    /**
     * If the client should use authentication if available.
     */
    public final boolean requireLogin;

    /**
     * If the client supports multiple audio tracks.
     */
    public final boolean supportsMultiAudioTracks;

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
               boolean canLogin,
               boolean requireLogin,
               boolean supportsMultiAudioTracks,
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
        this.canLogin = canLogin;
        this.requireLogin = requireLogin;
        this.supportsMultiAudioTracks = supportsMultiAudioTracks;
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
               String userAgent,
               boolean canLogin,
               boolean requireLogin,
               boolean supportsMultiAudioTracks,
               String friendlyName) {
        this.id = id;
        this.clientName = clientName;
        this.deviceMake = deviceMake;
        this.deviceModel = deviceModel;
        this.osName = osName;
        this.osVersion = osVersion;
        this.clientVersion = clientVersion;
        this.userAgent = userAgent;
        this.canLogin = canLogin;
        this.requireLogin = requireLogin;
        this.supportsMultiAudioTracks = supportsMultiAudioTracks;
        this.friendlyName = friendlyName;
        this.packageName = null;
        this.androidSdkVersion = null;
    }
}
