package app.morphe.extension.youtube.patches.announcements.requests;

import static app.morphe.extension.shared.requests.Route.Method.GET;

import java.io.IOException;
import java.net.HttpURLConnection;

import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

public class AnnouncementsRoutes {
    // FIXME
    private static final String ANNOUNCEMENTS_PROVIDER = "https://api.morphi.app/v1";
    public static final Route GET_LATEST_ANNOUNCEMENT_IDS = new Route(GET, "/announcements/latest/id?tag=\uD83C\uDF9E\uFE0F%20YouTube");
    public static final Route GET_LATEST_ANNOUNCEMENTS = new Route(GET, "/announcements/latest?tag=\uD83C\uDF9E\uFE0F%20YouTube");

    private AnnouncementsRoutes() {
    }

    public static HttpURLConnection getAnnouncementsConnectionFromRoute(Route route, String... params) throws IOException {
        return Requester.getConnectionFromRoute(ANNOUNCEMENTS_PROVIDER, route, params);
    }
}