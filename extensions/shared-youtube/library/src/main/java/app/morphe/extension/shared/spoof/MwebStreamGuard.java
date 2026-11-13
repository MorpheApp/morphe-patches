/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4764
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * Watches the media responses of the Mobile Web client, and gives up the client for a video
 * when its streams cannot be played, so the video can be played with another client.
 * <p>
 * The streams of a web client can stop when the server asks for an attestation the app cannot give,
 * when the server answers with an error, or when the server puts an ad in the streams of the video,
 * which the player of the app does not expect.
 */
public final class MwebStreamGuard {
    // UMP parts of media responses.
    static final int PART_MEDIA_HEADER = 20;
    static final int PART_SABR_ERROR = 44;
    static final int PART_NEXT_REQUEST_POLICY = 35;

    // NextRequestPolicy.backoffTimeMs.
    private static final int NEXT_REQUEST_POLICY_BACKOFF_FIELD = 4;

    // MediaHeader.videoId.
    private static final int MEDIA_HEADER_VIDEO_ID_FIELD = 2;

    // StreamProtectionStatus.status.
    private static final int STATUS_ATTESTATION_PENDING = 2;
    private static final int STATUS_ATTESTATION_REQUIRED = 3;

    private static final int MAX_STREAMS = 10;

    /**
     * Videos by stream (the 'id' parameter of the stream url).
     */
    private static final Map<String, String> videoIdsByStream =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(MAX_STREAMS, true));

    /**
     * {@link SystemClock#elapsedRealtime()} of the first media request of each stream.
     */
    private static final Map<String, Long> firstRequestTimes =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(MAX_STREAMS, true));

    /**
     * The last stream protection status of each stream.
     */
    private static final Map<String, Integer> statusesByStream =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(MAX_STREAMS, true));

    private MwebStreamGuard() {
    }

    /**
     * Called when the streams of a video are fetched with the Mobile Web client.
     *
     * @param streamId The 'id' parameter of the stream url.
     */
    public static void onStreams(@Nullable String streamId, String videoId) {
        if (streamId != null) {
            videoIdsByStream.put(streamId, videoId);
        }
    }

    /**
     * Called with each media request of a stream. The first request of each stream is logged,
     * with the names of the url parameters and if the request uses the session of the website.
     */
    static void onRequest(String streamId, Uri uri, boolean hasWebsiteSession) {
        if (firstRequestTimes.putIfAbsent(streamId, SystemClock.elapsedRealtime()) == null) {
            Logger.printDebug(() -> "Mobile Web first media request of video: " + videoIdsByStream.get(streamId)
                    + ", website session: " + hasWebsiteSession
                    + ", url parameters: " + uri.getQueryParameterNames());
        }
    }

    /**
     * @return The video of the stream, or null if it is not known.
     */
    @Nullable
    static String getVideoId(String streamId) {
        return videoIdsByStream.get(streamId);
    }

    /**
     * Called with the stream protection status of a media response.
     */
    static void onStatus(String streamId, int status) {
        if (status < 0) {
            return;
        }
        Integer previous = statusesByStream.put(streamId, status);
        if (previous == null || previous != status) {
            Long firstRequestTime = firstRequestTimes.get(streamId);
            Logger.printDebug(() -> "Mobile Web stream protection status: " + status + " previous: " + previous
                    + " video: " + videoIdsByStream.get(streamId) + " after: "
                    + (firstRequestTime == null ? "?" : (SystemClock.elapsedRealtime() - firstRequestTime) + " ms"));
        }
        if (status == STATUS_ATTESTATION_REQUIRED) {
            onFailure(streamId, "attestation required");
        } else if (status == STATUS_ATTESTATION_PENDING && previous == null) {
            // The session the website made is accepted from the first response.
            // If it is not, the server stops the stream after some media, so the next client is used sooner.
            onFailure(streamId, "attestation pending at the start");
        }
    }

    private static void onFailure(String streamId, String reason) {
        String videoId = videoIdsByStream.get(streamId);
        if (videoId != null) {
            StreamingDataRequest.onWebClientFailed(videoId, reason);
        }
    }

    /**
     * Called with the parts of the media responses of a stream.
     */
    static void onPart(String streamId, long partType, byte[] part) {
        String videoId = videoIdsByStream.get(streamId);
        if (videoId == null) {
            return;
        }
        if (partType == PART_SABR_ERROR) {
            onFailure(streamId, "SABR error");
            return;
        }
        if (partType == PART_NEXT_REQUEST_POLICY) {
            try {
                final long backoff = readVarintField(part, NEXT_REQUEST_POLICY_BACKOFF_FIELD);
                if (backoff > 0) {
                    // The player waits before its next request.
                    Logger.printDebug(() -> "Mobile Web server asks to wait " + backoff + " ms, video: " + videoId);
                }
            } catch (IOException ex) {
                Logger.printInfo(() -> "Could not read next request policy", ex);
            }
            return;
        }
        if (partType == PART_MEDIA_HEADER) {
            try {
                String mediaVideoId = readMediaVideoId(part);
                if (mediaVideoId != null && !mediaVideoId.isEmpty() && !mediaVideoId.equals(videoId)) {
                    // An ad the server puts in the streams of the video.
                    Logger.printDebug(() -> "Mobile Web media of another video: " + mediaVideoId
                            + " stream video: " + videoId);
                    onFailure(streamId, "media of another video");
                }
            } catch (IOException ex) {
                Logger.printInfo(() -> "Could not read media header", ex);
            }
        }
    }

    private static long readVarintField(byte[] part, int field) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(part);
        for (int tag; (tag = input.readTag()) != 0; ) {
            if (WireFormat.getTagFieldNumber(tag) == field
                    && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                return input.readInt64();
            }
            input.skipField(tag);
        }
        return 0;
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
