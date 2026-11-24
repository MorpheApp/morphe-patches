/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.spoof;

import java.util.List;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.spoof.ClientType;

@SuppressWarnings("unused")
public class SpoofVideoStreamsPatch {

    private static final List<ClientType> AVAILABLE_CLIENTS = List.of(
            ClientType.TV_SABR,
            ClientType.VISIONOS_1_02,
            ClientType.ANDROID_MUSIC_NO_SDK,
            ClientType.ANDROID_MUSIC_REEL
            // If not signed in to Android VR, there may be playback issues.
            // Only use it if the user has selected it.
            // ClientType.ANDROID_VR_DASH
    );

    /**
     * Injection point.
     */
    public static void setClientOrderToUse() {
        app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setClientsToUse(
                AVAILABLE_CLIENTS, Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
    }

    /**
     * @return The clients a download may resolve its stream with, whether spoofing is on.
     */
    public static List<ClientType> getAvailableClients() {
        return AVAILABLE_CLIENTS;
    }
}
