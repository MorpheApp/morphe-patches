package app.morphe.extension.music.sponsorblock.requests;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.settings.MusicSponsorBlockSettings;
import app.morphe.extension.music.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.music.sponsorblock.objects.SponsorSegment;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public class SBRequester {

    private static final int TIMEOUT_TCP_MS = 7_000;
    private static final int TIMEOUT_HTTP_MS = 10_000;
    private static final int HTTP_OK = 200;

    private SBRequester() {}

    private static void handleConnectionError(@NonNull String message, @Nullable Exception ex) {
        if (MusicSponsorBlockSettings.SB_TOAST_ON_CONNECTION_ERROR.get()) {
            Utils.showToastShort(message);
        }
        if (ex != null) {
            Logger.printInfo(() -> message, ex);
        }
    }

    @NonNull
    public static SponsorSegment[] getSegments(@NonNull String videoId) {
        Utils.verifyOffMainThread();
        List<SponsorSegment> segments = new ArrayList<>();
        try {
            HttpURLConnection connection = getConnection(
                    SBRoutes.GET_SEGMENTS, videoId, SegmentCategory.sponsorBlockAPIFetchCategories());
            final int responseCode = connection.getResponseCode();

            if (responseCode == HTTP_OK) {
                JSONArray array = Requester.parseJSONArray(connection);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    JSONArray seg = obj.getJSONArray("segment");
                    final long start = (long) (seg.getDouble(0) * 1000);
                    final long end = (long) (seg.getDouble(1) * 1000);
                    String uuid = obj.getString("UUID");
                    boolean locked = obj.getInt("locked") == 1;
                    String categoryKey = obj.getString("category");
                    SegmentCategory category = SegmentCategory.byCategoryKey(categoryKey);
                    if (category == null) {
                        Logger.printException(() -> "Unknown SponsorBlock category: " + categoryKey);
                    } else if (end > start) {
                        segments.add(new SponsorSegment(category, uuid, start, end, locked));
                    }
                }
                Logger.printDebug(() -> {
                    StringBuilder sb = new StringBuilder("Downloaded segments:");
                    for (SponsorSegment s : segments) sb.append('\n').append(s);
                    return sb.toString();
                });
            } else if (responseCode == 404) {
                Logger.printDebug(() -> "No SponsorBlock segments for video: " + videoId);
            } else {
                handleConnectionError(str("morphe_music_sb_connection_failure_status", responseCode), null);
                connection.disconnect();
            }
        } catch (SocketTimeoutException ex) {
            handleConnectionError(str("morphe_music_sb_connection_failure_timeout"), ex);
        } catch (IOException ex) {
            handleConnectionError(str("morphe_music_sb_connection_failure_generic"), ex);
        } catch (Exception ex) {
            Logger.printException(() -> "getSegments failure", ex);
        }
        return segments.toArray(new SponsorSegment[0]);
    }

    private static HttpURLConnection getConnection(app.morphe.extension.shared.requests.Route route,
                                                   String... params) throws IOException {
        HttpURLConnection conn = Requester.getConnectionFromRoute(
                MusicSponsorBlockSettings.SB_API_URL.get(), route, params);
        conn.setConnectTimeout(TIMEOUT_TCP_MS);
        conn.setReadTimeout(TIMEOUT_HTTP_MS);
        return conn;
    }
}
