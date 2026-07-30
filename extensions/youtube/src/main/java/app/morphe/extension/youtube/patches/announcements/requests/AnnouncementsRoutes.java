package app.morphe.extension.youtube.patches.announcements.requests;

import java.io.IOException;
import java.net.HttpURLConnection;

import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

public class AnnouncementsRoutes {
    // FIXME
    private static final String ANNOUNCEMENTS_PROVIDER = "https://api.morphi.app/v1";
    public static final Route GET_LATEST_ANNOUNCEMENT_IDS =
            new Route(Route.Method.GET, "/announcements/latest/id?tag=%F0%9F%8E%9E%EF%B8%8F%20YouTube");
    public static final Route GET_LATEST_ANNOUNCEMENTS =
            new Route(Route.Method.GET, "/announcements/latest?tag=%F0%9F%8E%9E%EF%B8%8F%20YouTube");

    private AnnouncementsRoutes() {
    }

    public static HttpURLConnection getAnnouncementsConnectionFromRoute(Route route, String... params) throws IOException {
        return Requester.getConnectionFromRoute(ANNOUNCEMENTS_PROVIDER, route, params);
    }
}
