/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4764
 * https://github.com/MorpheApp/morphe-patches/pull/5208
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import android.net.Uri;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.google.android.libraries.youtube.media.interfaces.HttpHeader;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.MediaHttpHeader;
import app.morphe.extension.shared.patches.MediaHttpRequest;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * A SABR stream of a web client (Mobile Web or Web).
 * <p>
 * Its media requests use the streaming session that the website made for the video: its client, its PoToken
 * and its config. The server sends the media only with them, and with the contexts the previous responses gave.
 * <p>
 * Its media responses are watched, and the client is given up for the video when its streams cannot be played,
 * so the video can be played with another client. The streams can stop when the server asks for an attestation
 * the app cannot give, when the server answers with an error, or when the server puts an ad in the streams
 * of the video, which the player of the app does not expect.
 * <p>
 * The server also tells the website player to seek to the start of the first segment of a response
 * (SABR seek), and puts this part between the format initialization of the video and its first segment.
 * The player of the app then resets the video track and loses its format, so the segment cannot be parsed,
 * and the player loads the same segment again, which shows overlapping frames at the start and fails
 * the playback after seeking back. These parts are hidden from the player, which plays the segment
 * from the position it asked for. Seeks of other sources are kept.
 */
public final class WebsiteStream {
    // UMP parts of media responses.
    private static final int PART_MEDIA_HEADER = 20;
    private static final int PART_SABR_ERROR = 44;
    private static final int PART_SABR_SEEK = 45;

    // MediaHeader.videoId.
    private static final int MEDIA_HEADER_VIDEO_ID_FIELD = 2;

    // SabrSeek.seekSource: the start of the first segment of the response, when the playback starts or after a seek.
    private static final int SABR_SEEK_SOURCE_FIELD = 3;
    private static final int SEEK_SOURCE_PLAYBACK_START = 10;
    private static final int SEEK_SOURCE_SEGMENT_START = 11;

    // StreamProtectionStatus.status.
    static final int STATUS_OK = 1;
    private static final int STATUS_ATTESTATION_PENDING = 2;
    private static final int STATUS_ATTESTATION_REQUIRED = 3;

    /**
     * A player can use its stream for as long as the stream url is valid, which is some hours.
     * For example a video waits in the miniplayer while Shorts are watched, and each Short has its own stream,
     * so a stream is kept until its url expires, and not only while it is one of the last streams.
     * This limit only bounds the memory, since a stream takes up to about 12 kilobytes,
     * and past it the streams not used for the longest time are forgotten.
     */
    private static final int MAX_STREAMS = 300;

    /**
     * How long a stream is kept if its url has no expiration time.
     */
    private static final long DEFAULT_STREAM_LIFETIME_MILLISECONDS = 6 * 60 * 60 * 1000;

    /**
     * Headers of the app that name the app, which are replaced with the headers of the website.
     */
    private static final String[] REPLACED_HEADERS = {"User-Agent", "Origin", "Referer"};

    /**
     * Streams by the 'id' parameter of their url.
     */
    private static final Map<String, WebsiteStream> streams =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(MAX_STREAMS, true));

    private record ResponseReader(WebsiteStream stream, SabrResponseReader reader) {
    }

    /**
     * Readers of the media responses, by the native callbacks of their request.
     */
    private static final Map<Object, ResponseReader> responseReaders =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final String videoId;

    /**
     * The web client of the stream.
     */
    private final ClientType client;

    /**
     * The last session of the video. A new fetch of the streams of the video can have the same stream.
     */
    private volatile WebsiteSession.Session session;

    /**
     * {@link System#currentTimeMillis()} when the url of the stream expires.
     */
    private volatile long expireTime;

    private final SabrContexts contexts = new SabrContexts();

    /**
     * If the streams were found unplayable, which is reported once.
     */
    private volatile boolean failed;

    /**
     * The last stream protection status, or -1 if none was given yet.
     */
    @GuardedBy("this")
    private int lastStatus = -1;

    private WebsiteStream(String videoId, ClientType client, WebsiteSession.Session session) {
        this.videoId = videoId;
        this.client = client;
        setSession(session);
    }

    private void setSession(WebsiteSession.Session session) {
        this.session = session;
        long expireTime = System.currentTimeMillis() + DEFAULT_STREAM_LIFETIME_MILLISECONDS;
        try {
            // Seconds since the epoch.
            String expire = Uri.parse(session.serverAbrStreamingUrl).getQueryParameter("expire");
            if (expire != null) {
                expireTime = Long.parseLong(expire) * 1000;
            }
        } catch (NumberFormatException ex) {
            Logger.printInfo(() -> "Web client stream url has an invalid expiration time", ex);
        }
        this.expireTime = expireTime;
    }

    /**
     * Called when the streams of a video are fetched with a web client.
     */
    public static void onStreams(String videoId, ClientType client, WebsiteSession.Session session) {
        String streamId = session.streamId;
        if (streamId == null) {
            Logger.printDebug(() -> "Web client stream has no id: " + videoId);
            return;
        }
        synchronized (streams) {
            final long now = System.currentTimeMillis();
            streams.values().removeIf(mwebStream -> mwebStream.expireTime < now);

            WebsiteStream stream = streams.get(streamId);
            if (stream != null && stream.videoId.equals(videoId)) {
                stream.setSession(session);
                stream.failed = false;
            } else {
                streams.put(streamId, new WebsiteStream(videoId, client, session));
            }
        }
    }

    /**
     * Forgets the streams, so the sessions of the website are not used again.
     */
    static void clear() {
        streams.clear();
        responseReaders.clear();
    }

    /**
     * @param uri       The stream url of the request.
     * @param body      The body of the request.
     * @param callbacks Native callbacks of the request, which are also given the response.
     * @return The request to start.
     */
    static MediaHttpRequest onMediaRequest(MediaHttpRequest request, Uri uri, byte[] body,
                                           Object callbacks, Locale locale) throws IOException {
        String streamId = uri.getQueryParameter("id");
        WebsiteStream stream = streamId == null ? null : streams.get(streamId);
        if (stream == null) {
            // The session was forgotten, for example after a sign in, so the server refuses the request.
            Logger.printDebug(() -> "Web client stream without a website session");
            return request;
        }

        WebsiteSession.Session session = stream.session;
        byte[] clientInfo = session.clientInfo != null
                ? session.clientInfo
                : SabrRequestBody.buildClientInfo(stream.client, locale);
        byte[] newBody = SabrRequestBody.replaceClientInfo(body, clientInfo, session.poToken,
                stream.contexts.toSend(), session.ustreamerConfig);

        responseReaders.put(callbacks, new ResponseReader(stream, new SabrResponseReader(stream::onPart,
                SabrContexts.PART_SABR_CONTEXT_UPDATE, SabrContexts.PART_SABR_CONTEXT_SENDING_POLICY,
                PART_MEDIA_HEADER, PART_SABR_ERROR).hideParts(PART_SABR_SEEK, WebsiteStream::isSegmentStartSeek)));

        return request.patch_withBodyAndHeaders(newBody, getHeaders(request, stream.client));
    }

    /**
     * The headers of the app, with the headers that name the browser of the web client and its website
     * instead of the app.
     */
    private static ArrayList<HttpHeader> getHeaders(MediaHttpRequest request, ClientType client) {
        ArrayList<HttpHeader> headers = new ArrayList<>();
        ArrayList<HttpHeader> appHeaders = request.patch_getHeaders();
        if (appHeaders != null) {
            for (HttpHeader header : appHeaders) {
                if (!isReplacedHeader(((MediaHttpHeader) header).patch_getName())) {
                    headers.add(header);
                }
            }
        }
        String origin = client.getWebsiteOrigin();
        headers.add(new HttpHeader("User-Agent", client.getUserAgent()));
        headers.add(new HttpHeader("Origin", origin));
        headers.add(new HttpHeader("Referer", origin + "/"));
        return headers;
    }

    private static boolean isReplacedHeader(@Nullable String name) {
        for (String replaced : REPLACED_HEADERS) {
            if (replaced.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called with each chunk of a media response.
     */
    static void onMediaResponseChunk(Object callbacks, ByteBuffer chunk) {
        if (responseReaders.isEmpty()) {
            return;
        }
        ResponseReader responseReader = responseReaders.get(callbacks);
        if (responseReader == null) {
            return;
        }
        try {
            final int status = responseReader.reader.read(chunk);
            if (status >= 0) {
                responseReader.stream.onStatus(status);
            }
        } catch (Exception ex) {
            responseReaders.remove(callbacks);
            Logger.printInfo(() -> "Could not read media response", ex);
        }
    }

    private synchronized void onStatus(int status) {
        final int previous = lastStatus;
        lastStatus = status;
        if (previous != status) {
            Logger.printDebug(() -> client + " stream protection status: " + status
                    + " previous: " + previous + " video: " + videoId);
        }
        if (status == STATUS_ATTESTATION_REQUIRED) {
            onFailure("attestation required");
        } else if (status == STATUS_ATTESTATION_PENDING && previous < 0) {
            // The session the website made is accepted from the first response.
            // If it is not, the server stops the stream after some media, so the next client is used sooner.
            onFailure("attestation pending at the start");
        }
    }

    private void onFailure(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        StreamingDataRequest.onWebClientFailed(videoId, reason);
    }

    private void onPart(long partType, byte[] part) {
        if (partType == SabrContexts.PART_SABR_CONTEXT_UPDATE
                || partType == SabrContexts.PART_SABR_CONTEXT_SENDING_POLICY) {
            contexts.onPart(partType, part);
        } else if (partType == PART_SABR_ERROR) {
            onFailure("SABR error");
        } else if (partType == PART_MEDIA_HEADER) {
            try {
                String mediaVideoId = readMediaVideoId(part);
                if (mediaVideoId != null && !mediaVideoId.isEmpty() && !mediaVideoId.equals(videoId)) {
                    // An ad the server puts in the streams of the video.
                    Logger.printDebug(() -> client + " media of another video: " + mediaVideoId
                            + " stream video: " + videoId);
                    onFailure("media of another video");
                }
            } catch (IOException ex) {
                Logger.printInfo(() -> "Could not read media header", ex);
            }
        }
    }

    /**
     * @return If a SABR seek part only moves the player to the start of the first segment of the response.
     */
    private static boolean isSegmentStartSeek(ByteBuffer chunk, int offset, int length) {
        try {
            byte[] part = new byte[length];
            chunk.position(offset);
            chunk.get(part);
            CodedInputStream input = CodedInputStream.newInstance(part);
            for (int tag; (tag = input.readTag()) != 0; ) {
                if (WireFormat.getTagFieldNumber(tag) == SABR_SEEK_SOURCE_FIELD
                        && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                    final int source = input.readInt32();
                    return source == SEEK_SOURCE_PLAYBACK_START || source == SEEK_SOURCE_SEGMENT_START;
                }
                input.skipField(tag);
            }
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not read SABR seek", ex);
        }
        return false;
    }

    @Nullable
    private static String readMediaVideoId(byte[] part) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(part);
        for (int tag; (tag = input.readTag()) != 0; ) {
            if (WireFormat.getTagFieldNumber(tag) == MEDIA_HEADER_VIDEO_ID_FIELD
                    && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                return input.readString();
            }
            input.skipField(tag);
        }
        return null;
    }
}
