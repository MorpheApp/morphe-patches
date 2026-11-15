/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Data model representing a discovered Sonos or UPnP MediaRenderer speaker.
 */
public final class SonosDevice {
    private final String ip;
    private final int port;
    private final String name;
    private final String udn;
    private final String modelName;
    private int volume = 25;

    public SonosDevice(String ip, int port, String name, String udn, String modelName) {
        this.ip = ip;
        this.port = port;
        this.name = name;
        this.udn = udn;
        this.modelName = modelName;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public String getName() {
        return name;
    }

    public String getUdn() {
        return udn;
    }

    public String getModelName() {
        return modelName;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    public String getAvTransportControlUrl() {
        return "http://" + ip + ":" + port + "/MediaRenderer/AVTransport/Control";
    }

    public String getRenderingControlUrl() {
        return "http://" + ip + ":" + port + "/MediaRenderer/RenderingControl/Control";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SonosDevice that = (SonosDevice) o;
        return Objects.equals(udn, that.udn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(udn);
    }

    @NonNull
    @Override
    public String toString() {
        return name + " (" + ip + ":" + port + ")";
    }
}
