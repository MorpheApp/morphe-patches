package app.morphe.extension.music.sponsorblock.requests;

import static app.morphe.extension.shared.requests.Route.Method.GET;
import static app.morphe.extension.shared.requests.Route.Method.POST;

import app.morphe.extension.shared.requests.Route;

class SBRoutes {
    static final Route GET_SEGMENTS = new Route(GET, "/api/skipSegments?videoID={video_id}&categories={categories}");
    static final Route IS_USER_VIP = new Route(GET, "/api/isUserVIP?userID={user_id}");

    private SBRoutes() {}
}
