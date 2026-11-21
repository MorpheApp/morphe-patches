/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import static app.morphe.extension.music.patches.sonos.PcmConverter.OUTPUT_BITS_PER_SAMPLE;
import static app.morphe.extension.music.patches.sonos.PcmConverter.OUTPUT_BYTES_PER_FRAME;
import static app.morphe.extension.music.patches.sonos.PcmConverter.OUTPUT_BYTES_PER_SECOND;
import static app.morphe.extension.music.patches.sonos.PcmConverter.OUTPUT_CHANNELS;
import static app.morphe.extension.music.patches.sonos.PcmConverter.OUTPUT_SAMPLE_RATE;

import android.os.Process;

import androidx.annotation.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;

/**
 * HTTP server on the phone that the speaker plays from. It streams the audio as an endless WAV file.
 */
final class LocalStreamServer {

    private static final String STREAM_PATH = "/live.wav";
    private static final String COVER_PATH = "/cover.jpg";

    /**
     * Audio a new connection gets at once, so the speaker can start playing without waiting.
     */
    private static final int BURST_BYTES = OUTPUT_BYTES_PER_SECOND;
    /**
     * Silence is sent when the app writes no audio for this long, such as while paused,
     * so the speaker keeps the connection open.
     */
    private static final long SILENCE_AFTER_MILLIS = 1500;
    private static final long SILENCE_CHUNK_MILLIS = 20;
    private static final int SILENCE_CHUNK_BYTES = (int) (OUTPUT_BYTES_PER_SECOND * SILENCE_CHUNK_MILLIS / 1000);
    private static final int QUEUE_CHUNKS = 512;
    private static final long CLIENT_IDLE_TIMEOUT_SECONDS = 30;
    private static final int MAX_CONNECTIONS = 16;
    private static final int MAX_LINE_LENGTH = 8192;
    private static final int SEND_BUFFER_BYTES = 512 * 1024;
    private static final int OUTPUT_BUFFER_BYTES = 64 * 1024;
    private static final int WAV_HEADER_BYTES = 44;
    private static final int WAV_FMT_CHUNK_BYTES = 16;
    private static final short WAV_FORMAT_PCM = 1;
    /**
     * Size of a stream with no end.
     */
    private static final int WAV_UNKNOWN_SIZE = -1;

    private static final String NOT_FOUND_RESPONSE = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n";

    private static final ExecutorService clientPool = Executors.newCachedThreadPool();
    private static final AtomicInteger connections = new AtomicInteger();

    private static final ArrayBlockingQueue<byte[]> liveQueue = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
    private static final Set<Client> clients = Collections.synchronizedSet(new LinkedHashSet<>());
    /**
     * The last audio sent, for the burst of a new connection.
     * Synchronizing on it also guards adding a client, so a client never misses or repeats a chunk.
     */
    private static final ArrayDeque<byte[]> history = new ArrayDeque<>();
    private static int historyBytes;

    @Nullable
    private static volatile ServerSocket serverSocket;
    @Nullable
    private static volatile byte[] coverArt;

    private static final class Client {
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
    }

    private LocalStreamServer() {
    }

    /**
     * Starts the server on the network of the speaker.
     *
     * @return The base URL of the server.
     */
    static synchronized String start(String speakerIp) throws IOException {
        stop();

        final String ip = getLocalIpAddress(speakerIp);
        final ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(ip), 0));
        serverSocket = socket;
        Logger.printInfo(() -> "Stream server started on " + ip + ":" + socket.getLocalPort());

        new Thread(() -> acceptLoop(socket), "morphe-sonos-accept").start();
        new Thread(() -> pumpLoop(socket), "morphe-sonos-pump").start();

        return "http://" + ip + ":" + socket.getLocalPort();
    }

    static synchronized void stop() {
        final ServerSocket socket = serverSocket;
        if (socket == null) {
            return;
        }
        serverSocket = null;

        try {
            socket.close();
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not close the server socket", ex);
        }

        liveQueue.clear();
        synchronized (history) {
            history.clear();
            historyBytes = 0;
        }
        coverArt = null;
        Logger.printInfo(() -> "Stream server stopped");
    }

    static boolean hasClients() {
        return !clients.isEmpty();
    }

    static String getStreamUrl(String baseUrl) {
        return baseUrl + STREAM_PATH;
    }

    /**
     * @param version Changes the URL for each cover, as speakers cache images by their URL.
     */
    static String getCoverUrl(String baseUrl, int version) {
        return baseUrl + COVER_PATH + "?v=" + version;
    }

    static void setCoverArt(@Nullable byte[] jpeg) {
        coverArt = jpeg;
    }

    /**
     * Queues audio in the stream format.
     */
    static void offer(byte[] audio) {
        if (serverSocket != null && audio.length > 0) {
            offerDroppingOldest(liveQueue, audio);
        }
    }

    private static void offerDroppingOldest(ArrayBlockingQueue<byte[]> queue, byte[] chunk) {
        while (!queue.offer(chunk)) {
            queue.poll();
        }
    }

    private static void pumpLoop(ServerSocket socket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        final byte[] silence = new byte[SILENCE_CHUNK_BYTES];
        long lastAudioMillis = System.currentTimeMillis();

        while (!socket.isClosed()) {
            final byte[] chunk;
            try {
                chunk = liveQueue.poll(SILENCE_CHUNK_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            final long now = System.currentTimeMillis();
            if (chunk != null) {
                lastAudioMillis = now;
                broadcast(chunk);
            } else if (now - lastAudioMillis >= SILENCE_AFTER_MILLIS) {
                broadcast(silence);
            }
        }
    }

    private static void broadcast(byte[] chunk) {
        final List<Client> targets;
        synchronized (history) {
            history.addLast(chunk);
            historyBytes += chunk.length;
            while (historyBytes > BURST_BYTES) {
                historyBytes -= history.removeFirst().length;
            }
            synchronized (clients) {
                targets = new ArrayList<>(clients);
            }
        }

        for (Client client : targets) {
            offerDroppingOldest(client.queue, chunk);
        }
    }

    private static void acceptLoop(ServerSocket serverSocket) {
        while (!serverSocket.isClosed()) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException ex) {
                if (!serverSocket.isClosed()) {
                    Logger.printInfo(() -> "Accept failed", ex);
                }
                continue;
            }

            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet();
                closeQuietly(socket);
                continue;
            }

            clientPool.execute(() -> {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                    handle(serverSocket, socket);
                } finally {
                    connections.decrementAndGet();
                    closeQuietly(socket);
                }
            });
        }
    }

    private static void handle(ServerSocket serverSocket, Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(SEND_BUFFER_BYTES);

            final InputStream input = socket.getInputStream();
            final OutputStream output = new BufferedOutputStream(socket.getOutputStream(), OUTPUT_BUFFER_BYTES);

            final String requestLine = readLine(input);
            if (requestLine == null) {
                return;
            }
            final String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            final boolean isHead = "HEAD".equalsIgnoreCase(parts[0]);
            final int queryStart = parts[1].indexOf('?');
            final String path = (queryStart < 0) ? parts[1] : parts[1].substring(0, queryStart);

            // Skip the headers.
            String header;
            do {
                header = readLine(input);
            } while (header != null && !header.isEmpty());

            if (COVER_PATH.equals(path)) {
                sendCover(output);
            } else if (STREAM_PATH.equals(path)) {
                sendStream(serverSocket, output, isHead);
            } else {
                output.write(NOT_FOUND_RESPONSE.getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }
        } catch (SocketException ex) {
            // The speaker closes the stream on each track change.
            Logger.printDebug(() -> "Connection closed: " + ex.getMessage());
        } catch (IOException ex) {
            Logger.printInfo(() -> "Connection failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sendCover(OutputStream output) throws IOException {
        final byte[] cover = coverArt;
        if (cover == null) {
            output.write(NOT_FOUND_RESPONSE.getBytes(StandardCharsets.US_ASCII));
        } else {
            output.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: image/jpeg\r\n"
                    + "Content-Length: " + cover.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(cover);
        }
        output.flush();
    }

    private static void sendStream(ServerSocket serverSocket, OutputStream output, boolean isHead)
            throws IOException, InterruptedException {
        output.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: audio/wav\r\n"
                + "Cache-Control: no-cache, no-store\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (isHead) {
            output.flush();
            return;
        }

        final Client client = new Client();
        final List<byte[]> burst;
        final int burstBytes;
        synchronized (history) {
            burst = new ArrayList<>(history);
            burstBytes = historyBytes;
            clients.add(client);
        }

        try {
            output.write(wavHeader());
            for (byte[] chunk : burst) {
                output.write(chunk);
            }
            // Pad with silence, so the speaker always gets a full burst.
            if (burstBytes < BURST_BYTES) {
                output.write(new byte[BURST_BYTES - burstBytes]);
            }
            output.flush();

            while (!serverSocket.isClosed()) {
                final byte[] chunk = client.queue.poll(CLIENT_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (chunk == null) {
                    return;
                }
                output.write(chunk);
                if (client.queue.isEmpty()) {
                    output.flush();
                }
            }
        } finally {
            clients.remove(client);
        }
    }

    private static byte[] wavHeader() {
        final ByteBuffer header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(WAV_UNKNOWN_SIZE);
        header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(WAV_FMT_CHUNK_BYTES);
        header.putShort(WAV_FORMAT_PCM).putShort((short) OUTPUT_CHANNELS);
        header.putInt(OUTPUT_SAMPLE_RATE).putInt(OUTPUT_BYTES_PER_SECOND);
        header.putShort((short) OUTPUT_BYTES_PER_FRAME).putShort((short) OUTPUT_BITS_PER_SAMPLE);
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(WAV_UNKNOWN_SIZE);
        return header.array();
    }

    @Nullable
    private static String readLine(InputStream input) throws IOException {
        final StringBuilder line = new StringBuilder();
        while (line.length() < MAX_LINE_LENGTH) {
            final int b = input.read();
            if (b == -1) {
                return (line.length() == 0) ? null : line.toString();
            }
            if (b == '\n') {
                final int end = line.length() - 1;
                return (end >= 0 && line.charAt(end) == '\r') ? line.substring(0, end) : line.toString();
            }
            line.append((char) b);
        }
        return line.toString();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not close socket", ex);
        }
    }

    /**
     * @param speakerIp If not null, the address on the same subnet is preferred.
     * @return The private IPv4 address of this phone.
     */
    static String getLocalIpAddress(@Nullable String speakerIp) throws SocketException {
        final List<String> addresses = new ArrayList<>();
        final Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        if (networkInterfaces == null) {
            throw new SocketException("No network interfaces");
        }
        for (NetworkInterface networkInterface : Collections.list(networkInterfaces)) {
            if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                continue;
            }
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    addresses.add(address.getHostAddress());
                }
            }
        }

        if (speakerIp != null) {
            final String subnet = speakerIp.substring(0, speakerIp.lastIndexOf('.') + 1);
            for (String address : addresses) {
                if (address.startsWith(subnet)) {
                    return address;
                }
            }
        }

        if (addresses.isEmpty()) {
            throw new SocketException("Not connected to a local network");
        }
        return addresses.get(0);
    }
}
