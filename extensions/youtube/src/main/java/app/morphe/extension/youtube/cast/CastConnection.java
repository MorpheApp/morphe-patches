/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.cast.CastChannel.CastMessage;

/**
 * Sender side of the Cast v2 protocol, used to play media on the Default Media Receiver.
 * <p>
 * The Default Media Receiver is hosted by Google, needs no registration and plays a DASH manifest
 * or a file from a url. Unlike the YouTube receiver, it does not show ads.
 */
final class CastConnection implements Closeable {

    /**
     * The media to load: the title and image the receiver shows, and the position to start at.
     *
     * @param textTracks    Captions the receiver can show.
     * @param activeTrackId Id of the captions to show, or {@link #NO_TRACK}.
     */
    record Media(String title, String imageUrl, double startSeconds, List<TextTrack> textTracks,
                 int activeTrackId) {
    }

    /**
     * WebVTT captions.
     */
    record TextTrack(int id, String url, String language) {
    }

    static final int NO_TRACK = -1;

    interface Listener {
        /**
         * Called on the reader thread with each media status.
         */
        void onMediaStatus(JSONObject status);

        /**
         * Called on the reader thread if the receiver could not play the media.
         */
        void onLoadFailed();

        /**
         * Called on the reader thread once the receiver app or the connection has closed.
         */
        void onClosed();
    }

    /**
     * Receiver app that plays a media url, available on every Cast device.
     */
    private static final String DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845";

    private static final String NAMESPACE_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NAMESPACE_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NAMESPACE_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String NAMESPACE_MEDIA = "urn:x-cast:com.google.cast.media";

    private static final String SENDER_ID = "sender-0";
    private static final String RECEIVER_ID = "receiver-0";
    private static final int PROTOCOL_VERSION_CASTV2_1_0 = 0;
    private static final int PAYLOAD_TYPE_STRING = 0;
    private static final int METADATA_TYPE_GENERIC = 0;

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 10_000;
    /**
     * The receiver closes the connection if it receives no ping for a while.
     */
    private static final int HEARTBEAT_INTERVAL_MILLISECONDS = 5_000;
    /**
     * Time without any message, pongs included, after which the device is taken to be gone.
     */
    private static final int SILENCE_TIMEOUT_MILLISECONDS = 3 * HEARTBEAT_INTERVAL_MILLISECONDS;
    /**
     * How long a read waits for a message, so the reader can stop to load another video.
     */
    private static final int READ_TIMEOUT_MILLISECONDS = 500;
    private static final int LAUNCH_TIMEOUT_MILLISECONDS = 20_000;
    /**
     * Time for the receiver to start playing after a load, including its first buffering.
     */
    private static final int LOAD_TIMEOUT_MILLISECONDS = 45_000;
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private final SSLSocket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final AtomicInteger requestId = new AtomicInteger(1);

    @Nullable
    private String transportId;
    @Nullable
    private String sessionId;
    private volatile int mediaSessionId;
    private volatile double volumeLevel = 1;
    private volatile boolean closed;
    /**
     * If {@link #readUntilClosed} or {@link #loadAndWait} returns, to load another video.
     */
    private volatile boolean stopReading;
    private long lastPingTime;
    private long lastMessageTime = System.currentTimeMillis();
    /**
     * Request id of the last load. Failures of other requests do not end the cast.
     */
    private int activeLoadRequestId = -1;

    CastConnection(InetAddress host, int port) throws IOException {
        try {
            // Cast devices use a self-signed certificate. Senders authenticate the device
            // with a separate challenge, which is not needed to play media.
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new TrustAllManager()}, null);
            socket = (SSLSocket) context.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLISECONDS);
            socket.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
            socket.startHandshake();
        } catch (GeneralSecurityException ex) {
            throw new IOException(ex);
        }
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());
    }

    /**
     * Volume of the device, from 0 to 1, as last reported.
     */
    double getVolumeLevel() {
        return volumeLevel;
    }

    /**
     * Address of this device on the network of the Cast device.
     */
    InetAddress getLocalAddress() {
        return socket.getLocalAddress();
    }

    /**
     * Starts the Default Media Receiver and connects to it. Blocks until it runs.
     */
    void launch() throws IOException, JSONException {
        send(RECEIVER_ID, NAMESPACE_CONNECTION, new JSONObject().put("type", "CONNECT"));
        send(RECEIVER_ID, NAMESPACE_RECEIVER, new JSONObject()
                .put("type", "LAUNCH")
                .put("appId", DEFAULT_MEDIA_RECEIVER_APP_ID)
                .put("requestId", requestId.getAndIncrement()));

        final long deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MILLISECONDS;
        while (transportId == null) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("Default Media Receiver did not start");
            }
            pingIfDue();
            @Nullable CastMessage message = read();
            if (message == null) {
                continue;
            }
            JSONObject payload = new JSONObject(message.getPayloadUtf8());
            if ("LAUNCH_ERROR".equals(payload.optString("type"))) {
                throw new IOException("Launch failed: " + payload.optString("reason"));
            }
            readApp(payload);
        }
        send(transportId, NAMESPACE_CONNECTION, new JSONObject().put("type", "CONNECT"));
    }

    /**
     * Loads the media and waits until it plays. Blocks the calling thread.
     *
     * @return If the receiver plays the media, or false if it cannot play it.
     */
    boolean loadAndWait(String url, String contentType, Media media, Listener listener)
            throws IOException, JSONException {
        final int loadRequestId = requestId.getAndIncrement();
        // Media loaded into a running receiver app replaces the media of the previous load.
        final int previousSessionId = mediaSessionId;
        send(transportId, NAMESPACE_MEDIA, new JSONObject()
                .put("type", "LOAD")
                .put("requestId", loadRequestId)
                .put("media", new JSONObject()
                        .put("contentId", url)
                        .put("contentType", contentType)
                        .put("streamType", "BUFFERED")
                        .put("metadata", new JSONObject()
                                .put("metadataType", METADATA_TYPE_GENERIC)
                                .put("title", media.title())
                                .put("images", new JSONArray().put(new JSONObject().put("url", media.imageUrl()))))
                        .put("tracks", textTracks(media.textTracks())))
                .put("activeTrackIds", activeTrackIds(media.activeTrackId()))
                .put("autoplay", true)
                .put("currentTime", media.startSeconds()));

        activeLoadRequestId = loadRequestId;
        final long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MILLISECONDS;
        while (System.currentTimeMillis() < deadline) {
            if (stopReading) {
                // Another video is loaded instead.
                stopReading = false;
                return false;
            }
            pingIfDue();
            @Nullable CastMessage message = read();
            if (message == null) {
                continue;
            }
            JSONObject payload = new JSONObject(message.getPayloadUtf8());
            switch (payload.optString("type")) {
                case "MEDIA_STATUS" -> {
                    JSONArray statuses = payload.optJSONArray("status");
                    if (statuses == null || statuses.length() == 0) {
                        continue;
                    }
                    JSONObject status = statuses.getJSONObject(0);
                    if (previousSessionId != 0 && status.optInt("mediaSessionId") == previousSessionId) {
                        continue;
                    }
                    mediaSessionId = status.optInt("mediaSessionId", mediaSessionId);
                    listener.onMediaStatus(status);
                    String state = status.optString("playerState");
                    if (state.equals("PLAYING") || state.equals("PAUSED")) {
                        return true;
                    }
                    if (state.equals("IDLE") && "ERROR".equals(status.optString("idleReason"))) {
                        return false;
                    }
                }
                case "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> {
                    if (payload.optInt("requestId") == loadRequestId) {
                        Logger.printInfo(() -> "Receiver did not load the media: " + payload);
                        return false;
                    }
                }
                case "RECEIVER_STATUS" -> {
                    if (!readApp(payload)) {
                        throw new IOException("Receiver app closed");
                    }
                }
            }
        }
        Logger.printInfo(() -> "Receiver did not start playing in time");
        return false;
    }

    /**
     * Shows the captions, or no captions for {@link #NO_TRACK}.
     */
    void setActiveTextTrack(int trackId) throws IOException, JSONException {
        if (transportId == null || mediaSessionId == 0) {
            return;
        }
        send(transportId, NAMESPACE_MEDIA, new JSONObject()
                .put("type", "EDIT_TRACKS_INFO")
                .put("mediaSessionId", mediaSessionId)
                .put("requestId", requestId.getAndIncrement())
                .put("activeTrackIds", activeTrackIds(trackId)));
    }

    private static JSONArray textTracks(List<TextTrack> tracks) throws JSONException {
        JSONArray array = new JSONArray();
        for (TextTrack track : tracks) {
            array.put(new JSONObject()
                    .put("trackId", track.id())
                    .put("type", "TEXT")
                    .put("subtype", "SUBTITLES")
                    .put("trackContentId", track.url())
                    .put("trackContentType", "text/vtt")
                    .put("language", track.language()));
        }
        return array;
    }

    private static JSONArray activeTrackIds(int trackId) {
        JSONArray array = new JSONArray();
        if (trackId != NO_TRACK) {
            array.put(trackId);
        }
        return array;
    }

    /**
     * Makes {@link #readUntilClosed} or {@link #loadAndWait} return, such as to load another video into the
     * receiver app.
     */
    void stopReading() {
        stopReading = true;
    }

    boolean isClosed() {
        return closed;
    }

    void play() throws IOException, JSONException {
        sendMediaCommand("PLAY", null);
    }

    void pause() throws IOException, JSONException {
        sendMediaCommand("PAUSE", null);
    }

    void seek(double seconds) throws IOException, JSONException {
        sendMediaCommand("SEEK", seconds);
    }

    /**
     * Sets the volume of the device, from 0 to 1.
     */
    void setVolume(double level) throws IOException, JSONException {
        send(RECEIVER_ID, NAMESPACE_RECEIVER, new JSONObject()
                .put("type", "SET_VOLUME")
                .put("volume", new JSONObject().put("level", level))
                .put("requestId", requestId.getAndIncrement()));
    }

    /**
     * Closes the receiver app, which returns the device to its idle screen.
     */
    void stopReceiverApp() throws IOException, JSONException {
        if (sessionId == null) {
            return;
        }
        send(RECEIVER_ID, NAMESPACE_RECEIVER, new JSONObject()
                .put("type", "STOP")
                .put("sessionId", sessionId)
                .put("requestId", requestId.getAndIncrement()));
    }

    /**
     * Reads messages until the receiver app or the connection closes, or until {@link #stopReading}.
     * Blocks the calling thread.
     */
    void readUntilClosed(Listener listener) {
        try {
            while (!closed) {
                if (stopReading) {
                    stopReading = false;
                    return;
                }
                pingIfDue();
                @Nullable CastMessage message = read();
                if (message == null) {
                    continue;
                }
                JSONObject payload = new JSONObject(message.getPayloadUtf8());
                String type = payload.optString("type");
                switch (type) {
                    case "MEDIA_STATUS" -> {
                        JSONArray statuses = payload.optJSONArray("status");
                        if (statuses != null && statuses.length() > 0) {
                            JSONObject status = statuses.getJSONObject(0);
                            mediaSessionId = status.optInt("mediaSessionId", mediaSessionId);
                            listener.onMediaStatus(status);
                        }
                    }
                    case "RECEIVER_STATUS" -> {
                        // Another sender or the remote of the device can close the receiver app.
                        if (!readApp(payload)) {
                            return;
                        }
                    }
                    case "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> {
                        // A failed command, such as a seek, or a late answer to an earlier load, does not end the cast.
                        if (payload.optInt("requestId", -1) != activeLoadRequestId) {
                            Logger.printInfo(() -> "Receiver did not do a command: " + payload);
                            continue;
                        }
                        Logger.printInfo(() -> "Receiver did not play the media: " + payload);
                        listener.onLoadFailed();
                        return;
                    }
                    case "CLOSE" -> {
                        if (message.getSourceId().equals(transportId)) {
                            return;
                        }
                    }
                }
            }
        } catch (IOException | JSONException ex) {
            if (!closed) {
                Logger.printInfo(() -> "Cast connection ended", ex);
            }
        } finally {
            listener.onClosed();
        }
    }

    /**
     * @return If the Default Media Receiver is running.
     */
    private boolean readApp(JSONObject receiverStatus) throws JSONException {
        if (!"RECEIVER_STATUS".equals(receiverStatus.optString("type"))) {
            return true;
        }
        JSONObject status = receiverStatus.getJSONObject("status");
        @Nullable JSONObject volume = status.optJSONObject("volume");
        if (volume != null && volume.has("level")) {
            volumeLevel = volume.optDouble("level", volumeLevel);
        }
        JSONArray apps = status.optJSONArray("applications");
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                if (DEFAULT_MEDIA_RECEIVER_APP_ID.equals(app.optString("appId"))
                        && app.has("transportId")) {
                    transportId = app.getString("transportId");
                    sessionId = app.getString("sessionId");
                    return true;
                }
            }
        }
        return transportId == null;
    }

    private void sendMediaCommand(String type, @Nullable Double currentTime) throws IOException, JSONException {
        if (transportId == null || mediaSessionId == 0) {
            return;
        }
        JSONObject command = new JSONObject()
                .put("type", type)
                .put("mediaSessionId", mediaSessionId)
                .put("requestId", requestId.getAndIncrement());
        if (currentTime != null) {
            command.put("currentTime", currentTime);
        }
        send(transportId, NAMESPACE_MEDIA, command);
    }

    private void pingIfDue() throws IOException, JSONException {
        final long now = System.currentTimeMillis();
        if (now - lastPingTime >= HEARTBEAT_INTERVAL_MILLISECONDS) {
            if (now - lastPingTime > SILENCE_TIMEOUT_MILLISECONDS) {
                // Nothing was read for a while, such as during the download of a video, so the silence
                // is counted from this ping.
                lastMessageTime = now;
            }
            lastPingTime = now;
            send(RECEIVER_ID, NAMESPACE_HEARTBEAT, new JSONObject().put("type", "PING"));
        }
    }

    private void send(@Nullable String destinationId, String namespace, JSONObject payload) throws IOException {
        if (destinationId == null) {
            throw new IOException("Not connected to the receiver app");
        }
        byte[] bytes = CastMessage.newBuilder()
                .setProtocolVersion(PROTOCOL_VERSION_CASTV2_1_0)
                .setSourceId(SENDER_ID)
                .setDestinationId(destinationId)
                .setNamespace(namespace)
                .setPayloadType(PAYLOAD_TYPE_STRING)
                .setPayloadUtf8(payload.toString())
                .build()
                .toByteArray();
        synchronized (output) {
            output.writeInt(bytes.length);
            output.write(bytes);
            output.flush();
        }
    }

    /**
     * @return The next message, or null if none arrived within the heartbeat interval.
     * Heartbeat messages are answered here and not returned.
     */
    @Nullable
    private CastMessage read() throws IOException, JSONException {
        while (true) {
            final int length;
            try {
                length = input.readInt();
            } catch (SocketTimeoutException ex) {
                if (System.currentTimeMillis() - lastMessageTime > SILENCE_TIMEOUT_MILLISECONDS) {
                    // The device answers each ping, so it is gone, such as when it lost power.
                    // The connection itself can take many minutes to time out then.
                    close();
                    throw new IOException("Cast device stopped answering");
                }
                return null;
            }
            lastMessageTime = System.currentTimeMillis();
            if (length <= 0 || length > MAX_MESSAGE_BYTES) {
                throw new IOException("Invalid Cast message length: " + length);
            }
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            CastMessage message = CastMessage.parseFrom(bytes);
            if (!NAMESPACE_HEARTBEAT.equals(message.getNamespace())) {
                return message;
            }
            if ("PING".equals(new JSONObject(message.getPayloadUtf8()).optString("type"))) {
                send(message.getSourceId(), NAMESPACE_HEARTBEAT, new JSONObject().put("type", "PONG"));
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not close Cast connection", ex);
        }
    }

    @SuppressWarnings("CustomX509TrustManager")
    private static final class TrustAllManager implements X509TrustManager {
        @SuppressWarnings("TrustAllX509TrustManager")
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @SuppressWarnings("TrustAllX509TrustManager")
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
