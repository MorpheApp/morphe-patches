/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * UPnP control requests to a speaker.
 */
final class SonosSoapClient {

    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int READ_TIMEOUT_MILLIS = 4000;
    /**
     * Time the speaker gets to load a new stream before it is told to play it.
     */
    private static final long PLAY_DELAY_MILLIS = 150;
    private static final int MAX_VOLUME = 100;

    /**
     * A single thread, so the commands reach the speaker in the order they are sent.
     */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SonosSoapClient() {
    }

    /**
     * Makes the speaker play a stream, with the track shown in the speaker apps.
     */
    static void playStream(SonosDevice device, String streamUrl, @Nullable TrackInfo track) {
        executor.execute(() -> {
            final String setUri = "<u:SetAVTransportURI xmlns:u=\"" + AV_TRANSPORT + "\">"
                    + "<InstanceID>0</InstanceID>"
                    + "<CurrentURI>" + escapeXml(streamUrl) + "</CurrentURI>"
                    + "<CurrentURIMetaData>" + (track == null ? "" : escapeXml(didl(streamUrl, track)))
                    + "</CurrentURIMetaData>"
                    + "</u:SetAVTransportURI>";
            if (send(device.avTransportControlUrl, AV_TRANSPORT, "SetAVTransportURI", setUri) == null) {
                Utils.showToastLong(str("morphe_music_sonos_cast_connection_failed", device.name));
                return;
            }

            try {
                Thread.sleep(PLAY_DELAY_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            send(device.avTransportControlUrl, AV_TRANSPORT, "Play",
                    "<u:Play xmlns:u=\"" + AV_TRANSPORT + "\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>");
        });
    }

    static void stop(SonosDevice device) {
        executor.execute(() -> send(device.avTransportControlUrl, AV_TRANSPORT, "Stop",
                "<u:Stop xmlns:u=\"" + AV_TRANSPORT + "\"><InstanceID>0</InstanceID></u:Stop>"));
    }

    static void setVolume(SonosDevice device, int volume) {
        final int clampedVolume = Math.max(0, Math.min(MAX_VOLUME, volume));
        executor.execute(() -> send(device.renderingControlUrl, RENDERING_CONTROL, "SetVolume",
                "<u:SetVolume xmlns:u=\"" + RENDERING_CONTROL + "\">"
                        + "<InstanceID>0</InstanceID><Channel>Master</Channel>"
                        + "<DesiredVolume>" + clampedVolume + "</DesiredVolume>"
                        + "</u:SetVolume>"));
    }

    /**
     * Blocks until the speaker answers. Runs after the volume changes that are still queued.
     *
     * @return The volume of the speaker in percent, or null if the request failed.
     */
    @Nullable
    static Integer getVolume(SonosDevice device) {
        try {
            return executor.submit(() -> readVolume(device)).get();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getVolume failure", ex);
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Nullable
    private static Integer readVolume(SonosDevice device) {
        final String response = send(device.renderingControlUrl, RENDERING_CONTROL, "GetVolume",
                "<u:GetVolume xmlns:u=\"" + RENDERING_CONTROL + "\">"
                        + "<InstanceID>0</InstanceID><Channel>Master</Channel>"
                        + "</u:GetVolume>");
        final String volume = extractTag(response, "CurrentVolume");
        if (volume == null) {
            return null;
        }

        try {
            return Integer.parseInt(volume);
        } catch (NumberFormatException ex) {
            Logger.printInfo(() -> "Invalid volume: " + volume, ex);
            return null;
        }
    }

    /**
     * Blocks until the speaker answers.
     *
     * @return The transport state such as PLAYING or STOPPED, or null if the request failed.
     */
    @Nullable
    static String getTransportState(SonosDevice device) {
        final String response = send(device.avTransportControlUrl, AV_TRANSPORT, "GetTransportInfo",
                "<u:GetTransportInfo xmlns:u=\"" + AV_TRANSPORT + "\"><InstanceID>0</InstanceID></u:GetTransportInfo>");
        return extractTag(response, "CurrentTransportState");
    }

    /**
     * Blocks until the speaker answers.
     *
     * @return The URI the speaker plays, empty if it has none, or null if the request failed.
     */
    @Nullable
    static String getSourceUri(SonosDevice device) {
        final String response = send(device.avTransportControlUrl, AV_TRANSPORT, "GetMediaInfo",
                "<u:GetMediaInfo xmlns:u=\"" + AV_TRANSPORT + "\"><InstanceID>0</InstanceID></u:GetMediaInfo>");
        return extractTag(response, "CurrentURI");
    }

    private static String didl(String streamUrl, TrackInfo track) {
        return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
                + " xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
                + "<item id=\"-1\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + escapeXml(track.title()) + "</dc:title>"
                + "<dc:creator>" + escapeXml(track.artist()) + "</dc:creator>"
                + (track.albumArtUrl() == null ? "" : "<upnp:albumArtURI>" + escapeXml(track.albumArtUrl()) + "</upnp:albumArtURI>")
                + "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>"
                + "<res protocolInfo=\"http-get:*:audio/wav:*\">" + escapeXml(streamUrl) + "</res>"
                + "</item></DIDL-Lite>";
    }

    /**
     * @return The response body, or null if the request failed.
     */
    @Nullable
    private static String send(String controlUrl, String service, String action, String body) {
        final String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>" + body + "</s:Body>"
                + "</s:Envelope>";

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(controlUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            connection.setRequestProperty("SOAPAction", "\"" + service + "#" + action + "\"");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(envelope.getBytes(StandardCharsets.UTF_8));
            }

            final int responseCode = connection.getResponseCode();
            if (responseCode / 100 != 2) {
                Logger.printInfo(() -> action + " failed with response code: " + responseCode);
                return null;
            }
            return Requester.parseString(connection);
        } catch (IOException ex) {
            // The speaker can be turned off or leave the network at any time.
            Logger.printInfo(() -> action + " failed", ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private static String extractTag(@Nullable String xml, String tag) {
        if (xml == null) {
            return null;
        }

        final String startTag = "<" + tag + ">";
        final int start = xml.indexOf(startTag);
        if (start < 0) {
            return null;
        }

        final int contentStart = start + startTag.length();
        final int end = xml.indexOf("</" + tag + ">", contentStart);
        return (end < 0) ? null : xml.substring(contentStart, end).trim();
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
