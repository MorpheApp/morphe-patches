/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * Lightweight embedded localhost HTTP server that feeds locally downloaded songs directly
 * into YouTube Music's ExoPlayer without leaving the device or violating HttpDataSource schemes.
 */
public final class LocalStreamServer {

    private static final Pattern REQUEST_PATTERN = Pattern.compile("^GET\\s+/stream\\?id=([A-Za-z0-9_-]+)\\s+HTTP/1\\.[01]", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE_PATTERN = Pattern.compile("Range:\\s*bytes=(\\d+)-(\\d*)", Pattern.CASE_INSENSITIVE);

    private static volatile ServerSocket serverSocket;
    private static volatile int serverPort = 0;
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Object lock = new Object();

    private LocalStreamServer() {}

    /**
     * Starts the localhost HTTP streaming server if it is not already running.
     */
    public static void start() {
        synchronized (lock) {
            if (serverSocket != null && !serverSocket.isClosed()) {
                return;
            }

            try {
                // Bind to loopback address (127.0.0.1) on an ephemeral port.
                serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                serverPort = serverSocket.getLocalPort();
                Logger.printDebug(() -> "LocalStreamServer started on 127.0.0.1:" + serverPort);

                executor.submit(LocalStreamServer::listenLoop);
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to start LocalStreamServer", ex);
            }
        }
    }

    /**
     * Resolves the stream URI for a downloaded track.
     */
    @Nullable
    public static Uri getStreamUri(String videoId) {
        start();
        if (serverPort <= 0) return null;
        return Uri.parse("http://127.0.0.1:" + serverPort + "/stream?id=" + videoId);
    }

    private static void listenLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            } catch (SocketException se) {
                // Server socket was closed.
                break;
            } catch (Exception ex) {
                Logger.printException(() -> "Error accepting LocalStreamServer connection", ex);
            }
        }
    }

    private static void handleClient(Socket socket) {
        try (socket;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            byte[] headerBuf = new byte[4096];
            int read = in.read(headerBuf);
            if (read <= 0) return;

            String request = new String(headerBuf, 0, read, StandardCharsets.US_ASCII);
            String[] lines = request.split("\r\n");
            if (lines.length == 0) return;

            Matcher reqMatcher = REQUEST_PATTERN.matcher(lines[0]);
            if (!reqMatcher.find()) {
                sendNotFound(out);
                return;
            }

            String videoId = reqMatcher.group(1);
            File songFile = LocalDownloadManager.getSongFile(videoId);
            if (songFile == null || !songFile.isFile()) {
                sendNotFound(out);
                return;
            }

            long fileLength = songFile.length();
            if (fileLength <= 0) {
                sendNotFound(out);
                return;
            }

            // Determine MIME type
            String mimeType = "audio/mp4";
            String name = songFile.getName().toLowerCase();
            if (name.endsWith(".webm") || name.endsWith(".opus")) {
                mimeType = "audio/webm";
            } else if (name.endsWith(".mp3")) {
                mimeType = "audio/mpeg";
            }

            // Parse Range header if present
            long start = 0;
            long end = fileLength - 1;
            boolean isRange = false;

            for (String line : lines) {
                Matcher rangeMatcher = RANGE_PATTERN.matcher(line);
                if (rangeMatcher.find()) {
                    isRange = true;
                    start = Long.parseLong(rangeMatcher.group(1));
                    String endStr = rangeMatcher.group(2);
                    if (endStr != null && !endStr.isEmpty()) {
                        end = Long.parseLong(endStr);
                    }
                    break;
                }
            }

            if (start > end || start >= fileLength) {
                String error = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + fileLength + "\r\n\r\n";
                out.write(error.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }

            end = Math.min(end, fileLength - 1);
            long contentLength = end - start + 1;

            StringBuilder responseHeaders = new StringBuilder();
            if (isRange) {
                responseHeaders.append("HTTP/1.1 206 Partial Content\r\n");
                responseHeaders.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(fileLength).append("\r\n");
            } else {
                responseHeaders.append("HTTP/1.1 200 OK\r\n");
            }

            responseHeaders.append("Content-Type: ").append(mimeType).append("\r\n");
            responseHeaders.append("Content-Length: ").append(contentLength).append("\r\n");
            responseHeaders.append("Accept-Ranges: bytes\r\n");
            responseHeaders.append("Connection: close\r\n\r\n");

            out.write(responseHeaders.toString().getBytes(StandardCharsets.US_ASCII));

            // Stream file bytes
            try (RandomAccessFile raf = new RandomAccessFile(songFile, "r")) {
                raf.seek(start);
                byte[] buffer = new byte[32 * 1024];
                long bytesRemaining = contentLength;

                while (bytesRemaining > 0) {
                    int toRead = (int) Math.min(buffer.length, bytesRemaining);
                    int bytesRead = raf.read(buffer, 0, toRead);
                    if (bytesRead < 0) break;

                    out.write(buffer, 0, bytesRead);
                    bytesRemaining -= bytesRead;
                }
            }

            out.flush();
        } catch (Exception ignored) {
            // Socket disconnect or playback aborted by ExoPlayer.
        }
    }

    private static void sendNotFound(OutputStream out) {
        try {
            String res = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(res.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (Exception ignored) {
        }
    }
}
