package app.morphe.extension.music.patches.spoof;

import static app.morphe.extension.music.settings.Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_VR_1_47_48;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_VR_1_54_20;
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
        // For some users No SDK can fail at 1 minute. Only use it if the user has explicitly set it.
        List<ClientType> availableClients = List.of(
                ANDROID_VR_1_47_48,
                VISIONOS,
                TV,
                ANDROID_VR_1_54_20
        );

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
    }
}
