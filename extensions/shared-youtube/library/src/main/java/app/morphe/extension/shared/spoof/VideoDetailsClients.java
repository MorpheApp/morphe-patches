package app.morphe.extension.shared.spoof;

import android.os.Build;

import app.morphe.extension.shared.Utils;

public enum VideoDetailsClients {
    ANDROID(
        "defaultAudioTrackID,saveVideoToWatchLater",
        String.valueOf(Build.VERSION.SDK_INT),
        3,
        "com.google.android.youtube",
        Utils.getAppVersionName(),
        Build.MODEL,
        Build.MANUFACTURER,
        Build.DISPLAY,
        "Android",
        Build.VERSION.RELEASE,
        null
    ),
    WEB_REMIX(
        "channelID",
        null,
        29,
        null,
        "1.20241218.01.00",
        null,
        null,
        null,
        null,
        null,
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
    );

    public final String infoToBindTo;
    public final String androidSDKVersion;
    public final int clientID;
    public final String clientVersion;
    public final String deviceMake;
    public final String deviceModel;
    public final String osBuildID;
    public final String osName;
    public final String osVersion;
    public final String userAgent;
    VideoDetailsClients(String infoToBindTo, String androidSDKVersion, int clientID, String clientPackageName, String clientVersion, String deviceMake, String deviceModel, String osBuildID, String osName, String osVersion, String userAgent) {
        this.infoToBindTo = infoToBindTo;
        this.androidSDKVersion = androidSDKVersion;
        this.clientID = clientID;
        this.clientVersion = clientVersion;
        this.deviceMake = deviceMake;
        this.deviceModel = deviceModel;
        this.osBuildID = osBuildID;
        this.osName = osName;
        this.osVersion = osVersion;

        this.userAgent =
            userAgent == null
            ?
                String.format(
                    "%s/%s (Linux; U; Android %s%s%s)",

                    clientPackageName,
                    clientVersion,
                    osVersion,
                    deviceModel != null ? String.format("; %s;", deviceModel) : "",
                    osBuildID != null ? String.format("; Build/%s", osBuildID) : ""
                )
            :
                userAgent;
    }
}
