package app.morphe.extension.shared.spoof.requests;

import static app.morphe.extension.shared.spoof.requests.PlayerRoutes.getPlayerResponseConnectionFromRoute;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.shared.spoof.VideoDetailsClients;

public class VideoDetailsRequest {
    private final Map<String, String> infoTypes = new HashMap<>() {{
        put("channelID", "player?prettyPrint=false&fields=videoDetails.channelId");
        put("saveVideoToWatchLater", "browse/edit_playlist?fields=status,playlistEditResults");
    }};
    private static final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
        1,
        20,
        10 * 1000,
        TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),
        r -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        }
    );
    private final Future<?> future;
    private byte[] requestBody;
    public VideoDetailsRequest(String videoID, Map<String, String> playerHeaders, String infoToFetch) {
        this.future = threadPoolExecutor.submit(
            () -> {
                for (VideoDetailsClients client : VideoDetailsClients.values()) {
                    if (client.infoToBindTo.contains(infoToFetch)) {
                        HttpURLConnection connection = getPlayerResponseConnectionFromRoute(
                            new Route(
                                Route.Method.POST,

                                Objects.requireNonNull(
                                    infoTypes.get(infoToFetch),

                                    "Cannot get the info type to fetch"
                                )
                            ).compile(),

                            Arrays.asList(
                                client.userAgent,

                                String.valueOf(client.clientID),

                                client.clientVersion
                            )
                        );

                        if (playerHeaders != null) {
                            for (String requestKey : List.of(
                                                        "Authorization",
                                                        "X-GOOG-API-FORMAT-VERSION",
                                                        "X-Goog-Visitor-Id"
                                                    )
                            ) {
                                connection.setRequestProperty(requestKey, playerHeaders.get(requestKey));
                            }
                        }

                        requestBody = new JSONObject() {{
                            put(
                                "context",

                                new JSONObject() {{
                                    put(
                                        "client",

                                        new JSONObject() {{
                                            put("clientName", client.name());
                                            put("clientVersion", client.clientVersion);
                                            if (client.deviceModel != null) {
                                                put("deviceMake", client.deviceMake);
                                                put("deviceModel", client.deviceModel);
                                                put("osName", client.osName);
                                                put("osVersion", client.osVersion);
                                                put("androidSdkVersion", client.androidSDKVersion);
                                            }
                                        }}
                                    );

                                    put(
                                        "user",

                                        new JSONObject() {{
                                            put("lockedSafetyMode", false);
                                        }}
                                    );
                                }}
                            );
                            put("contentCheckOk", true);
                            put("racyCheckOk", true);
                            put("videoId", videoID);
                            if (infoToFetch.equals("saveVideoToWatchLater")) {
                                put("playlistId", "WL");
                                put("excludeWatchLater", false);
                                put(
                                    "actions",

                                    new JSONArray() {{
                                        put(
                                            0,

                                            new JSONObject() {{
                                                put("action", "ACTION_ADD_VIDEO");
                                                put("addedVideoId", videoID);
                                            }}
                                        );
                                    }}
                                );
                            }
                        }}
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);

                        connection.setFixedLengthStreamingMode(requestBody.length);
                        connection.getOutputStream().write(requestBody);

                        if (connection.getResponseCode() == 200) {
                            try (BufferedReader reader =
                                new BufferedReader(
                                    new InputStreamReader(connection.getInputStream())
                                )
                            ) {
                                StringBuilder jsonBuilder = new StringBuilder();
                                String line;

                                while ((line = reader.readLine()) != null) {
                                    jsonBuilder.append(
                                        String.format(
                                            "%s\n",

                                            line
                                        )
                                    );
                                }

                                String jsonBuilderString = jsonBuilder.toString();
                                JSONObject jsonResponse = new JSONObject(jsonBuilderString);

                                switch (infoToFetch) {
                                    case "channelID" -> {
                                        return jsonResponse
                                                .getJSONObject("videoDetails")
                                                .getString("channelId");
                                    }

                                    case "saveVideoToWatchLater" -> {
                                        return jsonBuilderString;
                                    }
                                }
                            } catch (Exception e) {
                                Logger.printException(() -> "VideoDetailsRequest: ", e);
                            }
                        }
                    }
                }

                return null;
            }
        );
    }

    public Object getRequestedInfo() {
        try {
            return future.get(10 * 1000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Logger.printException(() -> "VideoDetailsRequest: ", e);

            return null;
        }
    }
}
