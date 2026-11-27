/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;

/**
 * Serves media to the Cast device over HTTP, with byte ranges and CORS.
 * <p>
 * Nothing is stored: every range the receiver asks for is fetched from YouTube when it is asked for,
 * so playback starts at once and seeking only fetches what is played.
 * <p>
 * The receiver needs ranges for single files, as it reads the index at the start or the end of the file,
 * and CORS for DASH, as its player fetches the segments with scripts from another origin.
 */
final class CastStreamServer implements Closeable {

    /**
     * Bytes of a resource, written for a range.
     */
    interface Source {
        long length();

        String contentType();

        void write(OutputStream output, long start, long count) throws IOException;
    }

    static final String MANIFEST_PATH = "manifest.mpd";
    static final String MP4_PATH = "media.mp4";
    static final String FILE_PATH = "file.mp4";
    private static final String STREAM_PATH_PREFIX = "stream/";
    private static final String CAPTIONS_PATH_PREFIX = "captions/";
    private static final String VTT_CONTENT_TYPE = "text/vtt";
    /**
     * Captions without cues, for captions that could not be fetched.
     */
    private static final byte[] EMPTY_VTT = "WEBVTT\n\n".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_HEADER_LINES = 100;
    private static final int UPSTREAM_TIMEOUT_MILLISECONDS = 20_000;
    private static final int CLIENT_TIMEOUT_MILLISECONDS = 60_000;
    private static final int BACKLOG = 50;
    private static final int BUFFER_SIZE = 64 * 1024;
    /**
     * Largest range asked from YouTube at once, so a receiver that stops reading costs little.
     */
    private static final long UPSTREAM_CHUNK_BYTES = 4L * 1024 * 1024;

    static String streamPath(int itag) {
        return STREAM_PATH_PREFIX + itag;
    }

    static String captionsPath(int index) {
        return CAPTIONS_PATH_PREFIX + index + ".vtt";
    }

    /**
     * Random first path segment, so other devices on the network cannot guess the urls.
     */
    private final String root = "/" + UUID.randomUUID() + "/";
    private final Map<String, Source> sources = new ConcurrentHashMap<>();
    private final ServerSocket serverSocket;
    private final InetAddress localAddress;
    private final InetAddress client;

    /**
     * Serves only on the network of the Cast device, and only to it.
     *
     * @param localAddress Address of this device on the network of the Cast device.
     * @param client       Address of the Cast device.
     */
    CastStreamServer(InetAddress localAddress, InetAddress client) throws IOException {
        this.localAddress = localAddress;
        this.client = client;
        serverSocket = new ServerSocket(0, BACKLOG, localAddress);
        Thread thread = new Thread(this::acceptConnections, "Morphe cast server");
        thread.setDaemon(true);
        thread.start();
    }

    void put(String path, Source source) {
        sources.put(path, source);
    }

    /**
     * Stops serving the media of the previous video. Requests that already started end normally.
     */
    void clear() {
        sources.clear();
    }

    String getUrl(String path) {
        String host = localAddress.getHostAddress();
        if (localAddress instanceof Inet6Address) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + serverSocket.getLocalPort() + root + path;
    }

    private void acceptConnections() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                if (!client.equals(socket.getInetAddress())) {
                    Logger.printDebug(() -> "Refusing cast request from " + socket.getInetAddress());
                    socket.close();
                    continue;
                }
                // A receiver that stops reading does not keep its request thread.
                socket.setSoTimeout(CLIENT_TIMEOUT_MILLISECONDS);
                Thread thread = new Thread(() -> serve(socket), "Morphe cast request");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException ex) {
                if (!serverSocket.isClosed()) {
                    Logger.printException(() -> "Cast server failure", ex);
                }
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            String range = null;
            for (int i = 0; i < MAX_HEADER_LINES; i++) {
                String line = reader.readLine();
                if (line == null || line.isEmpty()) {
                    break;
                }
                if (line.toLowerCase(Locale.US).startsWith("range:")) {
                    range = line.substring(line.indexOf(':') + 1).trim();
                }
            }

            String[] request = requestLine.split(" ");
            OutputStream output = socket.getOutputStream();
            if (request.length < 2) {
                return;
            }
            final String method = request[0];
            if (method.equals("OPTIONS")) {
                writeHead(output, "204 No Content", null, 0, null);
                return;
            }
            @Nullable Source source = request[1].startsWith(root)
                    ? sources.get(request[1].substring(root.length()))
                    : null;
            if (source == null) {
                writeHead(output, "404 Not Found", null, 0, null);
                return;
            }
            final boolean head = method.equals("HEAD");
            if (!head && !method.equals("GET")) {
                writeHead(output, "405 Method Not Allowed", null, 0, null);
                return;
            }

            final long length = source.length();
            @Nullable long[] bounds = parseRange(range, length);
            String finalRange = range;
            Logger.printDebug(() -> "Cast request: " + request[1].substring(root.length()) + " " + finalRange);
            if (bounds == null) {
                writeHead(output, "200 OK", source.contentType(), length, null);
                bounds = new long[]{0, length - 1};
            } else {
                writeHead(output, "206 Partial Content", source.contentType(), bounds[1] - bounds[0] + 1,
                        "bytes " + bounds[0] + "-" + bounds[1] + "/" + length);
            }
            if (!head && length > 0) {
                source.write(output, bounds[0], bounds[1] - bounds[0] + 1);
                output.flush();
            }
        } catch (IOException ex) {
            // The receiver closes connections it no longer needs, such as after seeking.
            Logger.printDebug(() -> "Cast request ended: " + ex.getMessage());
        }
    }

    private static void writeHead(OutputStream output, String status, @Nullable String contentType,
                                  long contentLength, @Nullable String contentRange) throws IOException {
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append("\r\n")
                .append("Content-Length: ").append(contentLength).append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Headers: Range\r\n")
                .append("Access-Control-Expose-Headers: Content-Range, Content-Length\r\n")
                .append("Connection: close\r\n");
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (contentRange != null) {
            head.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        head.append("\r\n");
        output.write(head.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * @return First and last byte of the range, or null to send the whole resource.
     */
    @Nullable
    static long[] parseRange(@Nullable String header, long length) {
        if (header == null || !header.startsWith("bytes=") || length == 0) {
            return null;
        }
        String spec = header.substring("bytes=".length());
        final int comma = spec.indexOf(',');
        if (comma >= 0) {
            spec = spec.substring(0, comma);
        }
        final int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String first = spec.substring(0, dash).trim();
            String last = spec.substring(dash + 1).trim();
            if (first.isEmpty()) {
                final long suffix = Long.parseLong(last);
                return new long[]{Math.max(0, length - suffix), length - 1};
            }
            final long start = Long.parseLong(first);
            final long end = last.isEmpty() ? length - 1 : Math.min(Long.parseLong(last), length - 1);
            return start <= end ? new long[]{start, end} : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    /**
     * Fixed bytes, such as the manifest.
     */
    static Source bytes(byte[] data, String contentType) {
        return new Source() {
            @Override
            public long length() {
                return data.length;
            }

            @Override
            public String contentType() {
                return contentType;
            }

            @Override
            public void write(OutputStream output, long start, long count) throws IOException {
                output.write(data, (int) start, (int) count);
            }
        };
    }

    static Source file(File file, String contentType) {
        return new Source() {
            @Override
            public long length() {
                return file.length();
            }

            @Override
            public String contentType() {
                return contentType;
            }

            @Override
            public void write(OutputStream output, long start, long count) throws IOException {
                try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                    input.seek(start);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long remaining = count;
                    while (remaining > 0) {
                        final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) {
                            return;
                        }
                        output.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
            }
        };
    }

    /**
     * A YouTube stream, passed through.
     */
    static Source stream(CastStreams.Stream stream, String userAgent) {
        return new Source() {
            @Override
            public long length() {
                return stream.contentLength;
            }

            @Override
            public String contentType() {
                return stream.mimeType;
            }

            @Override
            public void write(OutputStream output, long start, long count) throws IOException {
                copyUpstream(stream.url, userAgent, start, count, output, null);
            }
        };
    }

    /**
     * YouTube captions as WebVTT, which the receiver shows. Fetched once, when first asked for.
     */
    static Source captions(String url, String userAgent) {
        return new Source() {
            @Nullable
            private byte[] data;

            private synchronized byte[] data() {
                if (data == null) {
                    data = fetchCaptions(url, userAgent);
                }
                return data;
            }

            @Override
            public long length() {
                return data().length;
            }

            @Override
            public String contentType() {
                return VTT_CONTENT_TYPE;
            }

            @Override
            public void write(OutputStream output, long start, long count) throws IOException {
                output.write(data(), (int) start, (int) count);
            }
        };
    }

    private static byte[] fetchCaptions(String url, String userAgent) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url.replaceAll("&fmt=[^&]*", "") + "&fmt=vtt").openConnection();
            connection.setConnectTimeout(UPSTREAM_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(UPSTREAM_TIMEOUT_MILLISECONDS);
            connection.setRequestProperty("User-Agent", userAgent);
            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Logger.printInfo(() -> "Captions request failed with HTTP " + code);
                return EMPTY_VTT;
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    data.write(buffer, 0, read);
                }
                return data.size() > 0 ? data.toByteArray() : EMPTY_VTT;
            }
        } catch (IOException ex) {
            Logger.printInfo(() -> "Captions request failed", ex);
            return EMPTY_VTT;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    interface Patcher {
        /**
         * Changes bytes before they are written.
         *
         * @param position Offset of {@code buffer[0]} in the upstream resource.
         */
        void patch(byte[] buffer, int length, long position);
    }

    /**
     * Copies a range of a YouTube stream, in chunks.
     */
    static void copyUpstream(String url, String userAgent, long start, long count, OutputStream output,
                             @Nullable Patcher patcher) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long position = start;
        final long end = start + count;
        while (position < end) {
            final long chunkEnd = Math.min(end, position + UPSTREAM_CHUNK_BYTES);
            HttpURLConnection connection = openUpstream(url, userAgent, position, chunkEnd - 1);
            try (InputStream input = connection.getInputStream()) {
                int read;
                while (position < chunkEnd
                        && (read = input.read(buffer, 0, (int) Math.min(buffer.length, chunkEnd - position))) > 0) {
                    if (patcher != null) {
                        patcher.patch(buffer, read, position);
                    }
                    output.write(buffer, 0, read);
                    position += read;
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    static byte[] readUpstream(String url, String userAgent, long start, long end) throws IOException {
        HttpURLConnection connection = openUpstream(url, userAgent, start, end);
        try (InputStream input = connection.getInputStream()) {
            byte[] data = new byte[(int) (end - start + 1)];
            int offset = 0;
            int read;
            while (offset < data.length && (read = input.read(data, offset, data.length - offset)) > 0) {
                offset += read;
            }
            if (offset != data.length) {
                throw new IOException("Short read from stream: " + offset + " of " + data.length);
            }
            return data;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openUpstream(String url, String userAgent, long start, long end) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(UPSTREAM_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(UPSTREAM_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        final int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("Stream request failed with HTTP " + code);
        }
        return connection;
    }
}
