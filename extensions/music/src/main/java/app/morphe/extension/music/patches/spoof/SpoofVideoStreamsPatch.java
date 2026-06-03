package app.morphe.extension.music.patches.spoof;

import static app.morphe.extension.music.settings.Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_REEL_NO_AUTH;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_VR_1_64;
import static app.morphe.extension.shared.spoof.ClientType.TV;
import static app.morphe.extension.shared.spoof.ClientType.VISIONOS;

import java.util.List;

import app.morphe.extension.shared.spoof.ClientType;

@SuppressWarnings("unused")
public class SpoofVideoStreamsPatch {

    /**
     * Injection point.
     */
    public static void setClientOrderToUse() {
        List<ClientType> availableClients = List.of(
                TV,
                ANDROID_VR_1_64,
                VISIONOS,
                ANDROID_REEL_NO_AUTH
        );

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
    }
}
