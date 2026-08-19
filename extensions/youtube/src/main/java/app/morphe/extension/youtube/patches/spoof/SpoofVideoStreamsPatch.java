package app.morphe.extension.youtube.patches.spoof;

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
                    && (client == ClientType.ANDROID_VR_DASH || client == ClientType.ANDROID_XR_DASH || client == ClientType.VISIONOS_1_02);
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

        // Use [Android XR, Android XR Downgraded, visonOS 1.03] client that has AV1 if user settings allow it.
        // AVC cannot be forced with [Android XR, Android XR Downgraded, visonOS 1.03] because it uses VP9 and AV1.
        // If both settings are on, then force AVC takes priority and [Android VR, Android VR Downgraded, visonOS 1.02] is used.
        if (Settings.SPOOF_VIDEO_STREAMS_AV1.get() && !Settings.FORCE_AVC_CODEC.get() ) {
            if (client == ClientType.ANDROID_VR_DASH) {
                client = ClientType.ANDROID_XR_DASH;
            } else if (client == ClientType.ANDROID_VR_SABR) {
                client = ClientType.ANDROID_XR_SABR;
            } else if (client == ClientType.VISIONOS_1_02) {
                client = ClientType.VISIONOS_1_03;
            }
        }

        List<ClientType> availableClients = List.of(
                ClientType.TV_SABR,
                ClientType.VISIONOS_1_02,
                ClientType.ANDROID_CREATOR
                // If not signed in to Android VR, there may be playback issues.
                // Only use it if the user has selected it.
                // ClientType.ANDROID_VR_DASH
        );

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, client);
    }
}