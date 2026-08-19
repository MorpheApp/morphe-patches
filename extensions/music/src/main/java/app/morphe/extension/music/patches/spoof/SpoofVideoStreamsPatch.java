package app.morphe.extension.music.patches.spoof;

import java.util.List;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.spoof.ClientType;

@SuppressWarnings("unused")
public class SpoofVideoStreamsPatch {

    /**
     * Injection point.
     */
    public static void setClientOrderToUse() {
        List<ClientType> availableClients = List.of(
                ClientType.TV_SABR,
                ClientType.VISIONOS_1_02,
                ClientType.ANDROID_MUSIC_NO_SDK,
                ClientType.ANDROID_MUSIC_REEL
                // If not signed in to Android VR, there may be playback issues.
                // Only use it if the user has selected it.
                // ClientType.ANDROID_VR
        );

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
    }
}
