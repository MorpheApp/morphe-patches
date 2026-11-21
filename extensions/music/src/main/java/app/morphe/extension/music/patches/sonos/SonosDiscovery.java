/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * Finds the speakers on the network with an SSDP search, and also by probing the Sonos port of
 * every address of the subnet, for networks that block multicast.
 */
final class SonosDiscovery {

    interface Listener {
        void onDevicesChanged(List<SonosDevice> devices);
    }

    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String[] SSDP_SEARCH_TARGETS = {
            "urn:schemas-upnp-org:device:ZonePlayer:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1"
    };
    private static final int SSDP_MAX_WAIT_SECONDS = 2;
    private static final long SSDP_LISTEN_MILLIS = 6000;
    private static final int SSDP_RECEIVE_TIMEOUT_MILLIS = 500;
    private static final int SSDP_MAX_RESPONSE_BYTES = 4096;
    private static final String LOCATION_HEADER = "location:";

    private static final int SONOS_PORT = 1400;
    private static final String SONOS_DESCRIPTION_PATH = "/xml/device_description.xml";
    private static final int PROBE_THREADS = 16;
    private static final int PROBE_TIMEOUT_MILLIS = 800;
    private static final int LAST_HOST_ADDRESS = 254;
    private static final int DESCRIPTION_TIMEOUT_MILLIS = 2000;

    private static final String AV_TRANSPORT_SERVICE = ":service:AVTransport:";
    private static final String RENDERING_CONTROL_SERVICE = ":service:RenderingControl:";

    /**
     * Found devices by their unique device name.
     */
    private static final Map<String, SonosDevice> devices = new ConcurrentHashMap<>();
    /**
     * Device descriptions requested by the current search.
     */
    private static final Set<String> requestedLocations = ConcurrentHashMap.newKeySet();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    @Nullable
    private static volatile Listener listener;
    /**
     * Changes for each search, which ends the tasks of the previous one.
     */
    private static volatile int searchId;

    private SonosDiscovery() {
    }

    static List<SonosDevice> getDevices() {
        return new ArrayList<>(devices.values());
    }

    static void setListener(@Nullable Listener newListener) {
        listener = newListener;
    }

    static void startSearch() {
        final int id = ++searchId;
        requestedLocations.clear();
        executor.execute(() -> searchSsdp(id));
        executor.execute(() -> probeSubnet(id));
    }

    static void stopSearch() {
        searchId++;
    }

    private static void searchSsdp(int id) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(SSDP_RECEIVE_TIMEOUT_MILLIS);
            final InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            for (String target : SSDP_SEARCH_TARGETS) {
                final byte[] query = ("M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: " + SSDP_MAX_WAIT_SECONDS + "\r\n"
                        + "ST: " + target + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(query, query.length, group, SSDP_PORT));
            }

            final byte[] buffer = new byte[SSDP_MAX_RESPONSE_BYTES];
            final long endMillis = System.currentTimeMillis() + SSDP_LISTEN_MILLIS;
            while (id == searchId && System.currentTimeMillis() < endMillis) {
                final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException ex) {
                    continue;
                }

                final String location = parseLocation(
                        new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                if (location != null) {
                    requestDescription(id, location);
                }
            }
        } catch (IOException ex) {
            Logger.printInfo(() -> "SSDP search failed", ex);
        }
    }

    @Nullable
    private static String parseLocation(String response) {
        for (String line : response.split("\r\n")) {
            if (line.toLowerCase(Locale.US).startsWith(LOCATION_HEADER)) {
                return line.substring(LOCATION_HEADER.length()).trim();
            }
        }
        return null;
    }

    private static void requestDescription(int id, String location) {
        if (!requestedLocations.add(location)) {
            return;
        }

        executor.execute(() -> {
            if (id != searchId) {
                return;
            }
            try {
                addDevice(location, fetch(location, DESCRIPTION_TIMEOUT_MILLIS));
            } catch (IOException ex) {
                Logger.printInfo(() -> "Could not load the device description: " + location, ex);
            }
        });
    }

    private static void probeSubnet(int id) {
        final String localIp;
        try {
            localIp = LocalStreamServer.getLocalIpAddress(null);
        } catch (IOException ex) {
            Logger.printInfo(() -> "No local network to probe", ex);
            return;
        }

        final String subnet = localIp.substring(0, localIp.lastIndexOf('.') + 1);
        final ExecutorService probePool = Executors.newFixedThreadPool(PROBE_THREADS);
        for (int host = 1; host <= LAST_HOST_ADDRESS; host++) {
            final String location = "http://" + subnet + host + ":" + SONOS_PORT + SONOS_DESCRIPTION_PATH;
            probePool.execute(() -> {
                if (id != searchId || !requestedLocations.add(location)) {
                    return;
                }
                try {
                    addDevice(location, fetch(location, PROBE_TIMEOUT_MILLIS));
                } catch (IOException ex) {
                    // Most addresses have no speaker.
                    Logger.printVerbose(() -> "No speaker at " + location, ex);
                }
            });
        }
        probePool.shutdown();
    }

    private static String fetch(String url, int timeoutMillis) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        try {
            final int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Response code: " + responseCode);
            }
            return Requester.parseString(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static void addDevice(String location, String descriptionXml) {
        final SonosDevice device;
        try {
            device = parseDescription(location, descriptionXml);
        } catch (XmlPullParserException | IOException ex) {
            Logger.printInfo(() -> "Invalid device description: " + location, ex);
            return;
        }
        if (device == null) {
            Logger.printDebug(() -> "Not a media renderer: " + location);
            return;
        }

        Logger.printDebug(() -> "Found speaker: " + device);
        devices.put(device.udn, device);

        final Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onDevicesChanged(getDevices());
        }
    }

    /**
     * @return The device, or null if it can't play audio streams.
     */
    @Nullable
    private static SonosDevice parseDescription(String location, String xml)
            throws XmlPullParserException, IOException {
        final XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new StringReader(xml));

        String urlBase = null;
        String udn = null;
        String friendlyName = null;
        String roomName = null;
        String modelName = null;
        String serviceType = null;
        String avTransportControlUrl = null;
        String renderingControlUrl = null;

        String tag = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                tag = parser.getName();
                if ("service".equals(tag)) {
                    serviceType = null;
                }
            } else if (event == XmlPullParser.END_TAG) {
                tag = null;
            } else if (event == XmlPullParser.TEXT && tag != null) {
                final String text = parser.getText().trim();
                if (text.isEmpty()) {
                    continue;
                }

                // The first value is the one of the root device.
                switch (tag) {
                    case "URLBase" -> urlBase = text;
                    case "UDN" -> udn = (udn == null) ? text : udn;
                    case "friendlyName" -> friendlyName = (friendlyName == null) ? text : friendlyName;
                    case "roomName" -> roomName = (roomName == null) ? text : roomName;
                    case "modelName" -> modelName = (modelName == null) ? text : modelName;
                    case "serviceType" -> serviceType = text;
                    case "controlURL" -> {
                        if (serviceType != null && avTransportControlUrl == null
                                && serviceType.contains(AV_TRANSPORT_SERVICE)) {
                            avTransportControlUrl = text;
                        } else if (serviceType != null && renderingControlUrl == null
                                && serviceType.contains(RENDERING_CONTROL_SERVICE)) {
                            renderingControlUrl = text;
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        if (avTransportControlUrl == null || renderingControlUrl == null) {
            return null;
        }

        final URL descriptionUrl = new URL(location);
        final URL baseUrl = (urlBase == null) ? descriptionUrl : new URL(urlBase);
        // Sonos uses the room name in its apps. The friendly name also has the address and the model.
        final String name = (roomName != null) ? roomName
                : (friendlyName != null) ? friendlyName
                : descriptionUrl.getHost();

        return new SonosDevice(
                descriptionUrl.getHost(),
                name,
                (udn != null) ? udn : location,
                (modelName != null) ? modelName : "",
                new URL(baseUrl, avTransportControlUrl).toString(),
                new URL(baseUrl, renderingControlUrl).toString()
        );
    }
}
