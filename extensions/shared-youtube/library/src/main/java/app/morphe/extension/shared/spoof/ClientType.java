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
import android.webkit.WebSettings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("ConstantLocale")
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
     * Same as {@code ANDROID_VR_SABR} but uses the latest app version.
     * The server can send another player config to a newer app version,
     * so it is experimental and the version of {@code ANDROID_VR_SABR} is not updated.
     * AV1 codec not available.
     */
    ANDROID_VR_EXPERIMENTAL(
            ANDROID_VR_SABR.id,
            ANDROID_VR_SABR.clientName,
            ANDROID_VR_SABR.packageName,
            ANDROID_VR_SABR.deviceMake,
            ANDROID_VR_SABR.deviceModel,
            ANDROID_VR_SABR.osName,
            ANDROID_VR_SABR.osVersion,
            ANDROID_VR_SABR.androidSdkVersion,
            ANDROID_VR_SABR.buildID,
            "1.91.10",
            ANDROID_VR_SABR.clientPlatform,
            ANDROID_VR_SABR.canLogin,
            ANDROID_VR_SABR.requireLogin,
            ANDROID_VR_SABR.supportsMultiAudioTracks,
            ANDROID_VR_SABR.supportsOAuth2,
            ANDROID_VR_SABR.supportsVRImmersiveMode,
            ANDROID_VR_SABR.requireSABR,
            ANDROID_VR_SABR.usePlayerEndpoint,
            "Android VR Experimental"
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
     * Same as {@code ANDROID_VR_EXPERIMENTAL} but with the device of {@code ANDROID_XR_SABR}.
     * The server sends AV1 streams only to this device, whatever the app version.
     */
    ANDROID_XR_EXPERIMENTAL(
            ANDROID_XR_SABR.id,
            ANDROID_XR_SABR.clientName,
            ANDROID_XR_SABR.packageName,
            ANDROID_XR_SABR.deviceMake,
            ANDROID_XR_SABR.deviceModel,
            ANDROID_XR_SABR.osName,
            ANDROID_XR_SABR.osVersion,
            ANDROID_XR_SABR.androidSdkVersion,
            ANDROID_XR_SABR.buildID,
            ANDROID_VR_EXPERIMENTAL.clientVersion,
            ANDROID_XR_SABR.clientPlatform,
            ANDROID_XR_SABR.canLogin,
            ANDROID_XR_SABR.requireLogin,
            ANDROID_XR_SABR.supportsMultiAudioTracks,
            ANDROID_XR_SABR.supportsOAuth2,
            ANDROID_XR_SABR.supportsVRImmersiveMode,
            ANDROID_XR_SABR.requireSABR,
            ANDROID_XR_SABR.usePlayerEndpoint,
            "Android XR Experimental"
    ),
    /**
     * Video not playable: Live stream.
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
     * Plain ANDROID client used for caption / transcript fetching.
     */
    ANDROID(
            3,
            "ANDROID",
            "com.google.android.youtube",
            Build.MANUFACTURER,
            Build.MODEL,
            "Android",
            Build.VERSION.RELEASE,
            String.valueOf(Build.VERSION.SDK_INT),
            Build.ID,
            "20.10.38",
            null,
            true,
            true,
            false,
            false,
            false,
            false,
            true,
            "Android"
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
            "7.20261007.13.00",
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
     * Same as {@code TV_SABR} but uses another device.
     * This client cannot be selected in the settings and is used only if {@code TV_SABR} fails.
     * <p>
     * User agent: <a href="https://console.maban.co.uk/device/wiiu">console.maban.co.uk/device/wiiu</a>.
     * Keep "WiiU" without a space, YouTube reads "Nintendo Wii U" as a Wii and asks it for a PoToken.
     */
    TV_SABR_WII_U(
            TV_SABR.id,
            TV_SABR.clientName,
            "Nintendo",
            "Wii U",
            "Nintendo WiiU",
            TV_SABR.osVersion,
            TV_SABR.clientVersion,
            TV_SABR.clientPlatform,
            "Mozilla/5.0 (Nintendo WiiU) AppleWebKit/534.52 (KHTML, like Gecko) NX/2.1.0.8.23 NintendoBrowser/1.1.0.7579.EU",
            TV_SABR.canLogin,
            TV_SABR.requireLogin,
            TV_SABR.supportsMultiAudioTracks,
            TV_SABR.supportsVRImmersiveMode,
            TV_SABR.requireJS,
            TV_SABR.requirePoToken,
            TV_SABR.requireSABR,
            "TV Wii U"
    ),
    /**
     * Same as {@code TV_SABR} but supports dash streams.
     * This client cannot be selected in the settings and is used only for live streams.
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
     * Mobile web (m.youtube.com), as the Chrome browser of the device.
     * The user agent is of the Chrome version of the WebView, which opens the website (see {@link #getUserAgent()}).
     * Uses the web sign in if there is one (see {@link MwebSignIn}), and is used signed out otherwise.
     * Its SABR media requests use the PoToken and the streaming session that the website makes for the video
     * (see {@link MwebStream}), so the player request has no PoToken.
     */
    MWEB(
            2,
            "MWEB",
            "Google",
            "K",
            "Android",
            "10",
            "2.20261110.07.00",
            "MOBILE",
            String.format(Locale.ENGLISH, ClientType.WEB_USER_AGENT_FORMAT, ClientType.DEFAULT_CHROME_MAJOR_VERSION),
            // Uses the cookies of the web sign in, and not the access token of the app.
            false,
            false,
            true,
            false,
            true,
            false,
            true,
            "Mobile Web"
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
    ),
    /**
     * Same as {@code VISIONOS_1_02} but with app version 1.06 and visionOS 27.2.
     * The server can send other streams to a newer app version,
     * so the version of {@code VISIONOS_1_02} is not updated.
     * AV1 codec not available.
     */
    VISIONOS_1_06(
            VISIONOS_1_02.id,
            VISIONOS_1_02.clientName,
            VISIONOS_1_02.deviceMake,
            VISIONOS_1_02.deviceModel,
            VISIONOS_1_02.osName,
            "27.2",
            "1.06",
            VISIONOS_1_02.clientPlatform,
            "com.google.visionosyoutube/1.06 (RealityDevice14,1; U; CPU visionOS 27_2 like Mac OS X; en_US) gzip",
            VISIONOS_1_02.canLogin,
            VISIONOS_1_02.requireLogin,
            VISIONOS_1_02.supportsMultiAudioTracks,
            VISIONOS_1_02.supportsVRImmersiveMode,
            VISIONOS_1_02.requireJS,
            VISIONOS_1_02.requirePoToken,
            VISIONOS_1_02.requireSABR,
            "visionOS 1.06"
    ),
    /**
     * Same as {@code VISIONOS_1_06} but with the device model of {@code VISIONOS_1_03}.
     * The server sends AV1 streams only to this device model, whatever the app version.
     * AV1 codec available.
     */
    VISIONOS_1_06_AV1(
            VISIONOS_1_06.id,
            VISIONOS_1_06.clientName,
            VISIONOS_1_06.deviceMake,
            VISIONOS_1_03.deviceModel,
            VISIONOS_1_06.osName,
            VISIONOS_1_06.osVersion,
            VISIONOS_1_06.clientVersion,
            VISIONOS_1_06.clientPlatform,
            "com.google.visionosyoutube/1.06 (RealityDevice17,1; U; CPU visionOS 27_2 like Mac OS X; en_US) gzip",
            VISIONOS_1_06.canLogin,
            VISIONOS_1_06.requireLogin,
            VISIONOS_1_06.supportsMultiAudioTracks,
            VISIONOS_1_06.supportsVRImmersiveMode,
            VISIONOS_1_06.requireJS,
            VISIONOS_1_06.requirePoToken,
            VISIONOS_1_06.requireSABR,
            "visionOS 1.06 AV1"
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
     * Android SDK version, equivalent to {@code Build.VERSION.SDK} (System property: ro.build.version.sdk)
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
     * User agent of Chrome on Android, with the version and the device hidden as Chrome sends it.
     */
    private static final String WEB_USER_AGENT_FORMAT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/%s.0.0.0 Mobile Safari/537.36";

    /**
     * A recent major version of Chrome, used if the version of the WebView is not known.
     */
    private static final String DEFAULT_CHROME_MAJOR_VERSION = "142";

    private static final Pattern CHROME_MAJOR_VERSION_PATTERN = Pattern.compile("Chrome/(\\d+)");

    @Nullable
    private static volatile String webUserAgent;

    /**
     * The user agent of the client.
     * <p>
     * A web client is the Chrome browser of the device, with the Chrome version of the WebView.
     * The website opens in the WebView, so the requests name the same browser as the website session.
     */
    public String getUserAgent() {
        if (!isWebClient()) {
            return userAgent;
        }
        String agent = webUserAgent;
        if (agent == null) {
            String version = DEFAULT_CHROME_MAJOR_VERSION;
            try {
                Matcher matcher = CHROME_MAJOR_VERSION_PATTERN
                        .matcher(WebSettings.getDefaultUserAgent(Utils.getContext()));
                if (matcher.find()) {
                    version = matcher.group(1);
                }
            } catch (Exception ex) {
                // WebView is missing or broken.
                Logger.printInfo(() -> "Could not read the WebView user agent", ex);
            }
            agent = String.format(Locale.ENGLISH, WEB_USER_AGENT_FORMAT, version);
            webUserAgent = agent;
        }
        return agent;
    }

    /**
     * If the client is a client of the YouTube website. A web client signs in with the cookies of the website
     * (see {@link MwebSignIn}), and its PoTokens are made by the website (see {@link MwebSiteSession}).
     */
    public boolean isWebClient() {
        return this == MWEB;
    }

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
