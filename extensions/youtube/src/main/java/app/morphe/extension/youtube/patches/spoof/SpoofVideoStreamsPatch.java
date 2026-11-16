package app.morphe.extension.youtube.patches.spoof;

import java.util.List;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.youtube.patches.DisableVideoCodecsPatch;
import app.morphe.extension.youtube.patches.DisableVideoCodecsPatch.VideoCodec;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SpoofVideoStreamsPatch {

    public static final class SpoofClientAv1Availability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            ClientType client = Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get();
            return Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.isAvailable()
                    && (client == ClientType.ANDROID_VR_DASH || client == ClientType.ANDROID_VR_SABR
                    || client == ClientType.ANDROID_VR_EXPERIMENTAL
                    || client == ClientType.VISIONOS_1_02 || client == ClientType.VISIONOS_1_06);
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

        // Use the Android XR or visionOS client that has AV1 if user settings allow it.
        // AVC cannot be forced with these clients because they use VP9 and AV1.
        // VP9 blocks AV1, so the AV1 clients are not used.
        final VideoCodec codec = DisableVideoCodecsPatch.getVideoCodec();
        if (Settings.SPOOF_VIDEO_STREAMS_AV1.get() && codec == VideoCodec.DEFAULT) {
            if (client == ClientType.ANDROID_VR_DASH) {
                client = ClientType.ANDROID_XR_DASH;
            } else if (client == ClientType.ANDROID_VR_SABR) {
                client = ClientType.ANDROID_XR_SABR;
            } else if (client == ClientType.ANDROID_VR_EXPERIMENTAL) {
                client = ClientType.ANDROID_XR_EXPERIMENTAL;
            } else if (client == ClientType.VISIONOS_1_02) {
                client = ClientType.VISIONOS_1_03;
            } else if (client == ClientType.VISIONOS_1_06) {
                client = ClientType.VISIONOS_1_06_AV1;
            }
        }

        List<ClientType> availableClients = List.of(
                ClientType.TV_SABR,
                ClientType.ANDROID_CREATOR
                // If not signed in to Android VR, there may be playback issues.
                // Only use it if the user has selected it.
                // ClientType.ANDROID_VR_DASH
        );

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, client);
    }
}
