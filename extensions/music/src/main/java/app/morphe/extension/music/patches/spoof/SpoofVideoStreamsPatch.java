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
                ClientType.TV,
                ClientType.ANDROID_VR_1_73,
                ClientType.VISIONOS_1_02,
                ClientType.ANDROID_MUSIC_NO_SDK,
                ClientType.ANDROID_MUSIC_REEL
        );

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
    }
}
