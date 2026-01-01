package app.morphe.extension.music.patches.spoof;

import static app.morphe.extension.music.settings.Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_MUSIC_NO_SDK;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_NO_SDK;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_VR_1_47_48;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_VR_1_54_20;
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
                ANDROID_NO_SDK,
                ANDROID_VR_1_47_48,
                VISIONOS,
                ANDROID_VR_1_54_20,
                ANDROID_MUSIC_NO_SDK
        );

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
    }
}
