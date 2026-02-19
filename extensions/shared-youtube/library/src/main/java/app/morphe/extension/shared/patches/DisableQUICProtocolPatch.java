package app.morphe.extension.shared.patches;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class DisableQUICProtocolPatch {

    public static boolean disableQUICProtocol(boolean original) {
        boolean isDisabled = SharedYouTubeSettings.DISABLE_QUIC_PROTOCOL.get();
        return !isDisabled && original;
    }
}