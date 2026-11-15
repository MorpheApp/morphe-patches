/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;

/**
 * Handles UPnP SOAP communication with Sonos speakers for playback, volume, and metadata control.
 */
public final class SonosSoapClient {
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    private SonosSoapClient() {}

    public static void setUriAndPlay(
            SonosDevice device,
            String streamUrl,
            String title,
            String artist,
            @Nullable String albumArtUri,
            @Nullable PlayCallback callback
    ) {
        executor.execute(() -> {
            final String escapedTitle = escapeDidlXml(title);
            final String escapedArtist = escapeDidlXml(artist);
            final String singleEscapedUrl = escapeXml(streamUrl);
            final String didlEscapedUrl = escapeDidlXml(streamUrl);
            final String escapedArt = (albumArtUri != null && !albumArtUri.isEmpty()) ? escapeDidlXml(albumArtUri) : "";
            final String mimeType = streamUrl.contains(".wav") ? "audio/wav" : "audio/mpeg";

            final String artXml = !escapedArt.isEmpty()
                    ? "&lt;upnp:albumArtURI&gt;" + escapedArt + "&lt;/upnp:albumArtURI&gt;"
                    : "";

            final String broadcastDidl = !title.isEmpty()
                    ? "&lt;DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"&gt;&lt;item id=\"-1\" parentID=\"-1\" restricted=\"1\"&gt;&lt;dc:title&gt;" + escapedTitle + "&lt;/dc:title&gt;&lt;dc:creator&gt;" + escapedArtist + "&lt;/dc:creator&gt;" + artXml + "&lt;upnp:class&gt;object.item.audioItem.audioBroadcast&lt;/upnp:class&gt;&lt;res protocolInfo=\"http-get:*:" + mimeType + ":*\"&gt;" + didlEscapedUrl + "&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
                    : "";

            final String setUriBody =
                    "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                    "    <InstanceID>0</InstanceID>\n" +
                    "    <CurrentURI>" + singleEscapedUrl + "</CurrentURI>\n" +
                    "    <CurrentURIMetaData>" + broadcastDidl + "</CurrentURIMetaData>\n" +
                    "</u:SetAVTransportURI>";

            final boolean successUri = sendSoapRequest(
                    device.getAvTransportControlUrl(),
                    "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
                    setUriBody
            );

            if (!successUri) {
                Logger.printException(() -> "Failed to SetAVTransportURI on " + device.getName());
                if (callback != null) callback.onResult(false);
                return;
            }

            try { Thread.sleep(150); } catch (Exception ignored) {}

            final String playBody =
                    "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                    "    <InstanceID>0</InstanceID>\n" +
                    "    <Speed>1</Speed>\n" +
                    "</u:Play>";

            final boolean successPlay = sendSoapRequest(
                    device.getAvTransportControlUrl(),
                    "urn:schemas-upnp-org:service:AVTransport:1#Play",
                    playBody
            );

            if (callback != null) callback.onResult(successPlay);
        });
    }

    public static void play(SonosDevice device) {
        executor.execute(() -> {
            final String body =
                    "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                    "    <InstanceID>0</InstanceID>\n" +
                    "    <Speed>1</Speed>\n" +
                    "</u:Play>";
            sendSoapRequest(device.getAvTransportControlUrl(), "urn:schemas-upnp-org:service:AVTransport:1#Play", body);
        });
    }

    public static void pause(SonosDevice device) {
        executor.execute(() -> {
            final String body =
                    "<u:Pause xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                    "    <InstanceID>0</InstanceID>\n" +
                    "</u:Pause>";
            sendSoapRequest(device.getAvTransportControlUrl(), "urn:schemas-upnp-org:service:AVTransport:1#Pause", body);
        });
    }

    public static void stop(SonosDevice device) {
        executor.execute(() -> {
            final String body =
                    "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                    "    <InstanceID>0</InstanceID>\n" +
                    "</u:Stop>";
            sendSoapRequest(device.getAvTransportControlUrl(), "urn:schemas-upnp-org:service:AVTransport:1#Stop", body);
        });
    }

    public static void setVolume(SonosDevice device, int volume) {
        executor.execute(() -> {
            final int safeVolume = Math.max(0, Math.min(100, volume));
            final String body =
                    "<u:SetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">\n" +
                    "    <InstanceID>0</InstanceID>\n" +
                    "    <Channel>Master</Channel>\n" +
                    "    <DesiredVolume>" + safeVolume + "</DesiredVolume>\n" +
                    "</u:SetVolume>";
            final boolean ok = sendSoapRequest(device.getRenderingControlUrl(), "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume", body);
            if (ok) device.setVolume(safeVolume);
        });
    }

    @Nullable
    public static Integer getVolumeSync(SonosDevice device) {
        final String body =
                "<u:GetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">\n" +
                "    <InstanceID>0</InstanceID>\n" +
                "    <Channel>Master</Channel>\n" +
                "</u:GetVolume>";
        final String response = sendSoapRequestWithResponse(device.getRenderingControlUrl(), "urn:schemas-upnp-org:service:RenderingControl:1#GetVolume", body);
        final String volStr = extractTag(response, "CurrentVolume");
        if (volStr != null) {
            try {
                final int vol = Integer.parseInt(volStr);
                device.setVolume(vol);
                return vol;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    @Nullable
    public static String getTransportState(SonosDevice device) {
        final String body =
                "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                "    <InstanceID>0</InstanceID>\n" +
                "</u:GetTransportInfo>";
        final String response = sendSoapRequestWithResponse(device.getAvTransportControlUrl(), "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo", body);
        return extractTag(response, "CurrentTransportState");
    }

    private static boolean sendSoapRequest(String url, String action, String body) {
        return sendSoapRequestWithResponse(url, action, body) != null;
    }

    @Nullable
    private static String sendSoapRequestWithResponse(String urlString, String action, String bodyContent) {
        final String soapEnvelope =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "    <s:Body>\n" +
                "        " + bodyContent + "\n" +
                "    </s:Body>\n" +
                "</s:Envelope>";

        HttpURLConnection conn = null;
        try {
            final URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(4000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPAction", "\"" + action + "\"");

            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(soapEnvelope);
                writer.flush();
            }

            final int statusCode = conn.getResponseCode();
            if (statusCode >= 200 && statusCode <= 299) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    final StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    return sb.toString();
                }
            } else {
                return null;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "SOAP request failed: " + action, ex);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Nullable
    private static String extractTag(@Nullable String xml, String tag) {
        if (xml == null) return null;
        final String startTag = "<" + tag + ">";
        final String endTag = "</" + tag + ">";
        final int startIdx = xml.indexOf(startTag);
        if (startIdx == -1) return null;
        final int contentStart = startIdx + startTag.length();
        final int endIdx = xml.indexOf(endTag, contentStart);
        if (endIdx == -1) return null;
        return xml.substring(contentStart, endIdx).trim();
    }

    public static String escapeXml(String input) {
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public static String escapeDidlXml(String input) {
        return input.replace("&", "&amp;amp;")
                .replace("<", "&amp;lt;")
                .replace(">", "&amp;gt;")
                .replace("\"", "&amp;quot;")
                .replace("'", "&amp;apos;");
    }

    public interface PlayCallback {
        void onResult(boolean success);
    }
}
