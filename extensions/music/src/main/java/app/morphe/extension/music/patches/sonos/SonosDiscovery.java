/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import android.content.Context;
import android.net.wifi.WifiManager;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Discovers Sonos and UPnP MediaRenderer devices via SSDP multicast and fallback subnet scanning.
 */
public final class SonosDiscovery {
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private static final ConcurrentHashMap<String, SonosDevice> discoveredDevices = new ConcurrentHashMap<>();
    private static final List<DiscoveryListener> listeners = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicBoolean isScanning = new AtomicBoolean(false);
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    @Nullable
    private static WifiManager.MulticastLock multicastLock;

    public interface DiscoveryListener {
        void onDevicesUpdated(List<SonosDevice> devices);
    }

    private SonosDiscovery() {}

    public static void addListener(DiscoveryListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
            listener.onDevicesUpdated(new ArrayList<>(discoveredDevices.values()));
        }
    }

    public static void removeListener(DiscoveryListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public static List<SonosDevice> getDevices() {
        return new ArrayList<>(discoveredDevices.values());
    }

    public static void startDiscovery() {
        if (isScanning.getAndSet(true)) return;

        try {
            final Context context = Utils.getContext();
            final WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("SonosDiscoveryLock");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not acquire MulticastLock", ex);
        }

        executor.execute(SonosDiscovery::runSsdpSearch);
        executor.execute(SonosDiscovery::runFastSubnetFallback);
    }

    public static void stopDiscovery() {
        isScanning.set(false);
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {}
    }

    private static void runSsdpSearch() {
        final List<String> targets = Arrays.asList(
                "urn:schemas-upnp-org:device:ZonePlayer:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1"
        );

        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(4000);
            final InetAddress group = InetAddress.getByName(SSDP_ADDR);

            for (String target : targets) {
                final String query =
                        "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: " + target + "\r\n\r\n";

                final byte[] bytes = query.getBytes(StandardCharsets.UTF_8);
                final DatagramPacket packet = new DatagramPacket(bytes, bytes.length, group, SSDP_PORT);
                socket.send(packet);
            }

            final byte[] buf = new byte[4096];
            final long startTime = System.currentTimeMillis();
            while (isScanning.get() && (System.currentTimeMillis() - startTime < 6000)) {
                try {
                    final DatagramPacket recvPacket = new DatagramPacket(buf, buf.length);
                    socket.receive(recvPacket);
                    final String response = new String(recvPacket.getData(), 0, recvPacket.getLength(), StandardCharsets.UTF_8);
                    final InetAddress senderAddr = recvPacket.getAddress();
                    parseLocationAndFetch(response, senderAddr != null ? senderAddr.getHostAddress() : "");
                } catch (Exception e) {
                    break;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "SSDP search error", ex);
        } finally {
            if (socket != null) socket.close();
        }
    }

    private static void runFastSubnetFallback() {
        final String localIp = LocalStreamServer.getLocalIpAddress(null);
        if ("127.0.0.1".equals(localIp) || !localIp.contains(".")) return;

        final String baseIp = localIp.substring(0, localIp.lastIndexOf('.') + 1);
        final ExecutorService probePool = Executors.newFixedThreadPool(16);

        for (int i = 1; i <= 254; i++) {
            final String ip = baseIp + i;
            probePool.execute(() -> {
                if (!isScanning.get()) return;
                try {
                    final URL url = new URL("http://" + ip + ":1400/xml/device_description.xml");
                    final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(800);
                    conn.setReadTimeout(800);
                    conn.setRequestMethod("GET");

                    if (conn.getResponseCode() == 200) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            final StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line);
                            parseDeviceXml(sb.toString(), ip, 1400);
                        }
                    }
                    conn.disconnect();
                } catch (Exception ignored) {}
            });
        }
        probePool.shutdown();
    }

    private static void parseLocationAndFetch(String ssdpResponse, String senderIp) {
        final String[] lines = ssdpResponse.split("\r\n");
        String locationUrl = null;
        for (String line : lines) {
            if (line.toUpperCase().startsWith("LOCATION:")) {
                locationUrl = line.substring(9).trim();
                break;
            }
        }

        final String urlToFetch = (locationUrl != null) ? locationUrl : "http://" + senderIp + ":1400/xml/device_description.xml";
        executor.execute(() -> {
            try {
                final URL url = new URL(urlToFetch);
                final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);

                if (conn.getResponseCode() == 200) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        final StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        final int port = (url.getPort() > 0) ? url.getPort() : 1400;
                        parseDeviceXml(sb.toString(), url.getHost(), port);
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        });
    }

    private static void parseDeviceXml(String xml, String ip, int port) {
        try {
            final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            final XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int eventType = parser.getEventType();
            String friendlyName = null;
            String modelName = null;
            String udn = null;
            String currentTag = "";

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    currentTag = parser.getName();
                } else if (eventType == XmlPullParser.TEXT) {
                    final String text = parser.getText().trim();
                    if (!text.isEmpty()) {
                        if ("friendlyName".equals(currentTag) && friendlyName == null) friendlyName = text;
                        else if ("modelName".equals(currentTag) && modelName == null) modelName = text;
                        else if ("UDN".equals(currentTag) && udn == null) udn = text;
                    }
                }
                eventType = parser.next();
            }

            if (friendlyName != null) {
                final SonosDevice device = new SonosDevice(
                        ip,
                        port,
                        friendlyName,
                        (udn != null) ? udn : "uuid:" + ip,
                        (modelName != null) ? modelName : "Sonos"
                );
                discoveredDevices.put(device.getUdn(), device);
                notifyListeners();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "XML parse error for " + ip, ex);
        }
    }

    private static void notifyListeners() {
        final List<SonosDevice> currentList = new ArrayList<>(discoveredDevices.values());
        synchronized (listeners) {
            for (DiscoveryListener l : listeners) {
                try {
                    l.onDevicesUpdated(currentList);
                } catch (Exception ignored) {}
            }
        }
    }
}
