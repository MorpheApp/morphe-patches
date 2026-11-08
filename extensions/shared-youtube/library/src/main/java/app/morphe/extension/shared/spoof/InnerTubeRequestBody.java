/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4373
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.spoof;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * The body of an InnerTube request that loads a video: a player request, a watch next request,
 * or a request that holds a player request, such as a watch request or a Shorts watch request.
 */
final class InnerTubeRequestBody {
    // PlayerRequest.videoId and WatchNextRequest.videoId.
    private static final int VIDEO_ID_FIELD = 2;
    // GetWatchRequest.playerRequest and ReelItemWatchRequest.playerRequest.
    private static final int PLAYER_REQUEST_FIELD = 2;

    private static final int GZIP_MAGIC_FIRST_BYTE = 0x1F;
    private static final int GZIP_MAGIC_SECOND_BYTE = 0x8B;

    private InnerTubeRequestBody() {
    }

    /**
     * @param holdsPlayerRequest If the video id is in a player request that the body holds.
     * @return The video id, or null if the body has none.
     */
    @Nullable
    static String readVideoId(byte[] body, boolean holdsPlayerRequest) throws IOException {
        // The app compresses some request bodies, and then sends them with a gzip content encoding header.
        byte[] message = body.length > 1
                && (body[0] & 0xFF) == GZIP_MAGIC_FIRST_BYTE
                && (body[1] & 0xFF) == GZIP_MAGIC_SECOND_BYTE
                ? decompress(body) : body;
        if (holdsPlayerRequest) {
            message = SabrRequestBody.readMessageField(message, PLAYER_REQUEST_FIELD);
            if (message == null) {
                return null;
            }
        }
        byte[] videoId = SabrRequestBody.readMessageField(message, VIDEO_ID_FIELD);
        return videoId == null ? null : new String(videoId, StandardCharsets.UTF_8);
    }

    private static byte[] decompress(byte[] body) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(body))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(body.length * 4);
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) > 0; ) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
