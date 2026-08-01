package app.morphe.extension.shared.returnyoutubedislike.requests;

import java.io.IOException;
import java.net.HttpURLConnection;

import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

class ReturnYouTubeDislikeRoutes {
    static final String RYD_API_URL = "https://returnyoutubedislikeapi.com/";

    static final Route SEND_VOTE = new Route(Route.Method.POST, "interact/vote");
    static final Route CONFIRM_VOTE = new Route(Route.Method.POST, "interact/confirmVote");
    static final Route GET_DISLIKES = new Route(Route.Method.GET, "votes?videoId={video_id}");
    static final Route GET_REGISTRATION = new Route(Route.Method.GET, "puzzle/registration?userId={user_id}");
    static final Route CONFIRM_REGISTRATION = new Route(Route.Method.POST, "puzzle/registration?userId={user_id}");

    private ReturnYouTubeDislikeRoutes() {
    }

    static HttpURLConnection getRYDConnectionFromRoute(Route route, String... params) throws IOException {
        return Requester.getConnectionFromRoute(RYD_API_URL, route, params);
    }

}
