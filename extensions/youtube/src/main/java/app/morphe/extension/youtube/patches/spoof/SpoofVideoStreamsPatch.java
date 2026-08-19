package app.morphe.extension.youtube.patches.spoof;

import static app.morphe.extension.shared.spoof.ClientType.ANDROID_CREATOR;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_VR;
import static app.morphe.extension.shared.spoof.ClientType.ANDROID_XR;
import static app.morphe.extension.shared.spoof.ClientType.TV_SABR;
import static app.morphe.extension.shared.spoof.ClientType.VISIONOS_1_02;
import static app.morphe.extension.shared.spoof.ClientType.VISIONOS_1_03;

import android.text.TextUtils;

import java.util.List;

import app.morphe.extension.shared.oauth2.requests.OAuth2Requester;
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
                    && (client == ANDROID_VR || client == VISIONOS_1_02);
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

        // Use Android XR (visonOS 1.03) client that has AV1 if user settings allow it.
        // AVC cannot be forced with Android XR (visonOS 1.03) because it uses VP9 and AV1.
        // If both settings are on, then force AVC takes priority and Android VR (visionOS 1.02) is used.
        if (Settings.SPOOF_VIDEO_STREAMS_AV1.get() && !Settings.FORCE_AVC_CODEC.get() ) {
            if (client == ANDROID_VR) {
                client = ANDROID_XR;
            } else if (client == VISIONOS_1_02) {
                client = VISIONOS_1_03;
            }
        }

        List<ClientType> availableClients = List.of(
                TV_SABR,
                ANDROID_VR,
                VISIONOS_1_02,
                ANDROID_CREATOR
        );

        // If not signed in to Android VR, there may be playback issues.
        // If the user has not signed in to Android VR, remove them from the available clients.
        // Only use it if the user has selected it.
        String oauth2Authorization = OAuth2Requester.getAndUpdateAccessTokenIfNeeded();
        if (TextUtils.isEmpty(oauth2Authorization)) {
            availableClients.remove(ANDROID_VR);
        }

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, client);
    }
}