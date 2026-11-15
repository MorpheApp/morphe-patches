/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import android.os.Process;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;

/**
 * Embedded HTTP server streaming live linear PCM audio as an endless WAV stream to Sonos speakers.
 */
public final class LocalStreamServer {
    public static final int PORT = 8090;
    public static final int RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final long TICK_MS = 20L;
    private static final double BURST_SECONDS = 1.0;
    private static final int CLIENT_QUEUE = 512;
    private static final int MAX_CONNECTIONS = 16;

    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final AtomicBoolean isStreamingActive = new AtomicBoolean(false);
    private static final AtomicBoolean isPaused = new AtomicBoolean(false);

    @Nullable
    private static ServerSocket serverSocket;
    private static final ExecutorService pool = Executors.newCachedThreadPool();
    private static final AtomicInteger connections = new AtomicInteger(0);

    private static final ArrayBlockingQueue<byte[]> live = new ArrayBlockingQueue<>(512);
    private static byte[] remainder = new byte[0];

    private static final Set<Client> clients = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final ArrayDeque<byte[]> history = new ArrayDeque<>();
    private static int historyBytes = 0;

    @Nullable
    private static volatile String lastTargetDeviceIp = null;
    @Nullable
    private static volatile byte[] currentAlbumArtBytes = null;

    private static final class Client {
        final Socket socket;
        final String peer;
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(CLIENT_QUEUE);
        volatile long sent = 0L;
        volatile long dropped = 0L;

        Client(Socket socket, String peer) {
            this.socket = socket;
            this.peer = peer;
        }
    }

    private LocalStreamServer() {}

    public static boolean hasActiveClients() {
        return !clients.isEmpty();
    }

    public static boolean isStreamingActive() {
        return isStreamingActive.get();
    }

    public static boolean isRunning() {
        return isRunning.get();
    }

    public static int getSampleRate() {
        return RATE;
    }

    public static void setAlbumArt(@Nullable byte[] bytes) {
        currentAlbumArtBytes = bytes;
    }

    public static synchronized String start(@Nullable String targetDeviceIp) {
        lastTargetDeviceIp = targetDeviceIp;
        isStreamingActive.set(true);
        isPaused.set(false);
        flush();

        if (!isRunning.get()) {
            try {
                final ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(PORT));
                serverSocket = socket;
                isRunning.set(true);
                Logger.printInfo(() -> "LocalStreamServer started on port " + PORT);

                new Thread(() -> acceptLoop(socket), "sonos-accept").start();
                new Thread(LocalStreamServer::pumpLoop, "sonos-pump").start();
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to bind LocalStreamServer to port " + PORT, ex);
            }
        }
        return getLocalStreamUrl(targetDeviceIp);
    }

    public static synchronized void flush() {
        live.clear();
        remainder = new byte[0];
        synchronized (clients) {
            for (Client c : clients) {
                c.queue.clear();
            }
        }
        synchronized (history) {
            history.clear();
            historyBytes = 0;
        }
    }

    public static synchronized void stop() {
        isStreamingActive.set(false);
        isPaused.set(false);
        isRunning.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (Exception ignored) {}

        synchronized (clients) {
            for (Client c : clients) {
                try { c.socket.close(); } catch (Exception ignored) {}
            }
            clients.clear();
        }
        live.clear();
        synchronized (history) {
            history.clear();
            historyBytes = 0;
        }
        currentAlbumArtBytes = null;
        Logger.printInfo(() -> "LocalStreamServer stopped");
    }

    public static String getLocalStreamUrl(@Nullable String targetDeviceIp) {
        final String ip = getLocalIpAddress(targetDeviceIp != null ? targetDeviceIp : lastTargetDeviceIp);
        return "http://" + ip + ":" + PORT + "/live.wav";
    }

    public static synchronized void offer(byte[] data, int length) {
        if (!isRunning.get() || !isStreamingActive.get() || length <= 0) return;

        final byte[] joined;
        if (remainder.length == 0) {
            joined = Arrays.copyOf(data, length);
        } else {
            joined = new byte[remainder.length + length];
            System.arraycopy(remainder, 0, joined, 0, remainder.length);
            System.arraycopy(data, 0, joined, remainder.length, length);
        }

        final int aligned = (joined.length / 4) * 4;
        remainder = Arrays.copyOfRange(joined, aligned, joined.length);
        if (aligned == 0) return;

        final byte[] chunk = (aligned == joined.length) ? joined : Arrays.copyOf(joined, aligned);
        if (!live.offer(chunk)) {
            live.poll();
            live.offer(chunk);
        }
    }

    private static void pumpLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        } catch (Exception ignored) {}

        final int bytesPerSec = RATE * CHANNELS * (BITS_PER_SAMPLE / 8);
        final int tickBytes = (int) (bytesPerSec * TICK_MS / 1000L);
        final byte[] silence = new byte[tickBytes];
        long lastAudioMs = System.currentTimeMillis();

        while (isRunning.get()) {
            final byte[] data;
            try {
                data = live.poll(TICK_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }

            final long now = System.currentTimeMillis();
            if (data != null && data.length > 0) {
                lastAudioMs = now;
                broadcast(data, bytesPerSec);
            } else {
                if (now - lastAudioMs >= 1500L) {
                    broadcast(silence, bytesPerSec);
                }
            }
        }
    }

    private static void broadcast(byte[] data, int bytesPerSec) {
        final int burstBytes = (int) (bytesPerSec * BURST_SECONDS) / 4 * 4;
        final List<Client> targets;
        synchronized (history) {
            history.addLast(data);
            historyBytes += data.length;
            while (historyBytes > burstBytes && !history.isEmpty()) {
                historyBytes -= history.removeFirst().length;
            }
            synchronized (clients) {
                targets = new ArrayList<>(clients);
            }
        }
        for (Client c : targets) {
            if (!c.queue.offer(data)) {
                c.dropped++;
                c.queue.poll();
                c.queue.offer(data);
            }
        }
    }

    private static void acceptLoop(ServerSocket socket) {
        while (isRunning.get()) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (isRunning.get()) continue; else break;
            }
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet();
                try { client.close(); } catch (Exception ignored) {}
                continue;
            }
            pool.execute(() -> {
                try {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                    } catch (Exception ignored) {}
                    handle(client);
                } finally {
                    connections.decrementAndGet();
                }
            });
        }
    }

    private static void handle(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(524288);

            final BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            final BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 65536);

            final String requestLine = readLine(input);
            if (requestLine == null) return;
            final String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            final String method = parts[0].toUpperCase();
            final String path = parts[1].contains("?") ? parts[1].substring(0, parts[1].indexOf('?')) : parts[1];

            while (true) {
                final String header = readLine(input);
                if (header == null || header.isEmpty()) break;
            }

            final InetAddress addr = socket.getInetAddress();
            final String peer = (addr != null) ? addr.getHostAddress() : "?";

            if ("/cover.jpg".equals(path)) {
                final byte[] art = currentAlbumArtBytes;
                if (art != null && art.length > 0) {
                    final String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: " + art.length + "\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.US_ASCII));
                    out.write(art);
                    out.flush();
                } else {
                    final String notFound = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n";
                    out.write(notFound.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                return;
            }

            if ("/live.wav".equals(path)) {
                if ("HEAD".equals(method)) {
                    final String headResp = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: audio/wav\r\n" +
                            "Cache-Control: no-cache, no-store\r\n" +
                            "Connection: close\r\n\r\n";
                    out.write(headResp.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }

                final String responseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/wav\r\n" +
                        "Cache-Control: no-cache, no-store\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(responseHeader.getBytes(StandardCharsets.US_ASCII));

                final Client client = new Client(socket, peer);
                final List<byte[]> burst;
                final int currentHistoryBytes;
                synchronized (history) {
                    burst = new ArrayList<>(history);
                    currentHistoryBytes = historyBytes;
                    clients.add(client);
                }

                try {
                    out.write(wavHeader(RATE));

                    final int bytesPerSec = RATE * CHANNELS * (BITS_PER_SAMPLE / 8);
                    final int targetBurstBytes = (int) (bytesPerSec * BURST_SECONDS) / 4 * 4;

                    for (byte[] chunk : burst) {
                        out.write(chunk);
                        client.sent += chunk.length;
                    }

                    if (currentHistoryBytes < targetBurstBytes) {
                        final int padNeeded = targetBurstBytes - currentHistoryBytes;
                        final byte[] pad = new byte[padNeeded];
                        out.write(pad);
                        client.sent += pad.length;
                    }
                    out.flush();

                    while (isRunning.get() && !socket.isClosed()) {
                        final byte[] chunk = client.queue.poll(30, TimeUnit.SECONDS);
                        if (chunk == null) break;
                        out.write(chunk);
                        client.sent += chunk.length;
                        if (client.queue.isEmpty()) {
                            out.flush();
                        }
                    }
                } finally {
                    clients.remove(client);
                }
            } else {
                final String notFound = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n";
                out.write(notFound.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "LocalStreamServer client error", ex);
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] wavHeader(int rate) {
        final int byteRate = rate * CHANNELS * (BITS_PER_SAMPLE / 8);
        final int blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8);
        final ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(-1);
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        b.putShort((short) 1).putShort((short) CHANNELS).putInt(rate).putInt(byteRate);
        b.putShort((short) blockAlign).putShort((short) BITS_PER_SAMPLE);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(-1);
        return b.array();
    }

    @Nullable
    private static String readLine(InputStream input) throws IOException {
        final StringBuilder sb = new StringBuilder();
        while (true) {
            final int b = input.read();
            if (b == -1) return (sb.length() == 0) ? null : sb.toString();
            if (b == '\n') {
                String line = sb.toString();
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            sb.append((char) b);
            if (sb.length() > 8192) return sb.toString();
        }
    }

    public static String getLocalIpAddress(@Nullable String targetDeviceIp) {
        try {
            final Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "127.0.0.1";
            final List<String> candidates = new ArrayList<>();

            while (interfaces.hasMoreElements()) {
                final NetworkInterface intf = interfaces.nextElement();
                if (intf.isLoopback() || !intf.isUp()) continue;
                final Enumeration<InetAddress> addrs = intf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    final InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        final String host = addr.getHostAddress();
                        if (host != null && !host.startsWith("127.")) {
                            candidates.add(host);
                        }
                    }
                }
            }

            if (targetDeviceIp != null && targetDeviceIp.contains(".")) {
                final String prefix = targetDeviceIp.substring(0, targetDeviceIp.lastIndexOf('.') + 1);
                for (String c : candidates) {
                    if (c.startsWith(prefix)) return c;
                }
            }

            for (String c : candidates) {
                if (c.startsWith("192.168.") || c.startsWith("10.") || c.startsWith("172.")) {
                    return c;
                }
            }

            if (!candidates.isEmpty()) return candidates.get(0);
        } catch (Exception ex) {
            Logger.printException(() -> "Error resolving local IP", ex);
        }
        return "127.0.0.1";
    }
}
