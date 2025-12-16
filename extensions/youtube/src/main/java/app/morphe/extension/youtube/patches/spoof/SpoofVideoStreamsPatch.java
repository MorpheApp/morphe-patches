package app.morphe.extension.youtube.patches.spoof;

import static app.morphe.extension.shared.spoof.ClientType.ANDROID_CREATOR;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_NO_SDK;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_VR_1_47_48;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_VR_1_54_20;
import static app.morphe.extension.shared.spoof.ClientType.VISIONOS;

import java.util.List;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SpoofVideoStreamsPatch {

    public static final class SpoofClientAv1Availability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.isAvailable()
                    && Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get() == ANDROID_VR_1_47_48;
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

        // Use VR 1.54 client that has AV1 if user settings allow it.
        // AVC cannot be forced with VR 1.54 because it uses VP9 and AV1.
        // If both settings are on, then force AVC takes priority and VR 1.47 is used.
        if (client == ANDROID_VR_1_47_48 && Settings.SPOOF_VIDEO_STREAMS_AV1.get()
                && !Settings.FORCE_AVC_CODEC.get()) {
            client = ANDROID_VR_1_54_20;
        }

        List<ClientType> availableClients = List.of(
                VISIONOS,
                ANDROID_VR_1_47_48,
                ANDROID_CREATOR,
                ANDROID_NO_SDK
        );

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, client);
    }
}