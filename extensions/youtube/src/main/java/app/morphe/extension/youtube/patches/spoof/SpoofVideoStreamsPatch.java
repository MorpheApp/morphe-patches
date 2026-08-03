package app.morphe.extension.youtube.patches.spoof;

import static app.morphe.extension.shared.spoof.ClientType.ANDROID_CREATOR;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_VR_1_73;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_VR_1_74;
import static app.morphe.extension.shared.spoof.ClientType.TV_SABR;
import static app.morphe.extension.shared.spoof.ClientType.VISIONOS_1_02;
import static app.morphe.extension.shared.spoof.ClientType.VISIONOS_1_03;

import java.util.List;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SpoofVideoStreamsPatch {

    public static final class SpoofClientAv1Availability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            ClientType client = Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get();
            return Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.isAvailable()
                    && (client == ANDROID_VR_1_73 || client == VISIONOS_1_02);
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE);
        }
    }

    /**
     * Injection point.
     */
    public static void setClientOrderToUse() {
        ClientType client = Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get();

        // Use Android VR 1.74 (visonOS 1.03) client that has AV1 if user settings allow it.
        // AVC cannot be forced with Android VR 1.74 (visonOS 1.03) because it uses VP9 and AV1.
        // If both settings are on, then force AVC takes priority and Android VR 1.73 (visionOS 1.02) is used.
        if (Settings.SPOOF_VIDEO_STREAMS_AV1.get() && !Settings.FORCE_AVC_CODEC.get() ) {
            if (client == ANDROID_VR_1_73) {
                client = ANDROID_VR_1_74;
            } else if (client == VISIONOS_1_02) {
                client = VISIONOS_1_03;
            }
        }

        // Reels can take up to 1 minute for videos start playback.
        // Only use it if the user has selected it.
        List<ClientType> availableClients = List.of(
                TV_SABR,
                ANDROID_VR_1_73,
                VISIONOS_1_02,
                ANDROID_CREATOR
        );

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, client);
    }
}