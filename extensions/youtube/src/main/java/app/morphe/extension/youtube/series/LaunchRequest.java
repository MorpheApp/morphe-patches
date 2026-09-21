package app.morphe.extension.youtube.series;

import java.util.regex.Pattern;

/** Android-independent validation. All persisted/domain times are milliseconds. */
public final class LaunchRequest {
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern PLAYLIST_ID = Pattern.compile("[A-Za-z0-9_-]{2,200}");

    public final String videoId;
    public final String playlistId;
    public final long positionMs;

    public LaunchRequest(String videoId, String playlistId, long positionMs) {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException("Enter an 11-character video ID.");
        }
        if (playlistId == null
                || (!playlistId.isEmpty() && !PLAYLIST_ID.matcher(playlistId).matches())) {
            throw new IllegalArgumentException("Enter a playlist ID, or leave it empty.");
        }
        if (positionMs < 0) throw new IllegalArgumentException("Position must not be negative.");
        this.videoId = videoId;
        this.playlistId = playlistId;
        this.positionMs = positionMs;
    }

    public long seconds() {
        return positionMs / 1000L;
    }
}
