package app.morphe.extension.music.patches.spoof;

import android.text.TextUtils;

import java.util.List;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.oauth2.requests.OAuth2Requester;
import app.morphe.extension.shared.spoof.ClientType;

@SuppressWarnings("unused")
public class SpoofVideoStreamsPatch {

    /**
     * Injection point.
     */
    public static void setClientOrderToUse() {
        List<ClientType> availableClients = List.of(
                ClientType.TV_SABR,
                ClientType.ANDROID_VR,
                ClientType.VISIONOS_1_02,
                ClientType.ANDROID_MUSIC_NO_SDK,
                ClientType.ANDROID_MUSIC_REEL
        );

        // If not signed in to Android VR, there may be playback issues.
        // If the user has not signed in to Android VR, remove them from the available clients.
        // Only use it if the user has selected it.
        String oauth2Authorization = OAuth2Requester.getAndUpdateAccessTokenIfNeeded();
        if (TextUtils.isEmpty(oauth2Authorization)) {
            availableClients.remove(ANDROID_VR);
        }

        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                availableClients, Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
    }
}
