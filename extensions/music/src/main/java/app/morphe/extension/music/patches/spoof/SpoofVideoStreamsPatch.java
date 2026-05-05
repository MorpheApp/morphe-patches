package app.morphe.extension.music.patches.spoof;

import static app.morphe.extension.music.settings.Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE;
import static app.morphe.extension.shared.spoof.ClientType.Stream.ANDROID_REEL;
import static app.morphe.extension.shared.spoof.ClientType.Stream.ANDROID_VR_1_64;
import static app.morphe.extension.shared.spoof.ClientType.Stream.ANDROID_VR_1_65;
import static app.morphe.extension.shared.spoof.ClientType.Stream.TV;
import static app.morphe.extension.shared.spoof.ClientType.Stream.VISIONOS;

import java.util.List;

import app.morphe.extension.shared.spoof.ClientType;

@SuppressWarnings("unused")
public class SpoofVideoStreamsPatch {

    /**
     * Injection point.
     */
    public static void setClientOrderToUse() {
        // For some users No SDK can fail at 1 minute. Only use it if the user has explicitly set it.
        List<ClientType.Stream> availableClients = List.of(
                ANDROID_REEL,
                TV,
                ANDROID_VR_1_64,
                VISIONOS,
                ANDROID_VR_1_65
        );

        app.morphe.extension.shared.spoof.SpoofVideoStreamsOrGetVideoDetailsPatch.setClientsToUse(
                availableClients, SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
    }
}
