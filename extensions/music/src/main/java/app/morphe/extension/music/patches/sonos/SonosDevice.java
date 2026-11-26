/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import androidx.annotation.NonNull;

/**
 * A Sonos speaker or other UPnP media renderer.
 */
final class SonosDevice {

    final String ip;
    final String name;
    /**
     * Unique device name from the device description.
     */
    final String udn;
    final String modelName;
    final String avTransportControlUrl;
    final String renderingControlUrl;

    SonosDevice(String ip, String name, String udn, String modelName,
                String avTransportControlUrl, String renderingControlUrl) {
        this.ip = ip;
        this.name = name;
        this.udn = udn;
        this.modelName = modelName;
        this.avTransportControlUrl = avTransportControlUrl;
        this.renderingControlUrl = renderingControlUrl;
    }

    @NonNull
    @Override
    public String toString() {
        return name + " (" + ip + ")";
    }
}
