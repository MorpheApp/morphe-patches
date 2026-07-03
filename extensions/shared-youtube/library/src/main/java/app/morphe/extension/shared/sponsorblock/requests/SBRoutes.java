package app.morphe.extension.shared.sponsorblock.requests;

import static app.morphe.extension.shared.requests.Route.Method.GET;
import static app.morphe.extension.shared.requests.Route.Method.POST;

import app.morphe.extension.shared.requests.Route;

public class SBRoutes {
    public static final Route IS_USER_VIP = new Route(GET, "/api/isUserVIP?userID={user_id}");
    public static final Route GET_SEGMENTS = new Route(GET, "/api/skipSegments?videoID={video_id}&categories={categories}");
    public static final Route VIEWED_SEGMENT = new Route(POST, "/api/viewedVideoSponsorTime?UUID={segment_id}");
    public static final Route GET_USER_STATS = new Route(GET, "/api/userInfo?userID={user_id}&values=[\"userID\",\"userName\",\"reputation\",\"segmentCount\",\"ignoredSegmentCount\",\"viewCount\",\"minutesSaved\"]");
    public static final Route CHANGE_USERNAME = new Route(POST, "/api/setUsername?userID={user_id}&username={username}");
    public static final Route SUBMIT_SEGMENTS = new Route(POST, "/api/skipSegments?userID={user_id}&videoID={video_id}&category={category}&startTime={start_time}&endTime={end_time}&videoDuration={duration}&actionType={action_type}&userAgent={user_agent}");
    public static final Route VOTE_ON_SEGMENT_QUALITY = new Route(POST, "/api/voteOnSponsorTime?userID={user_id}&UUID={segment_id}&type={type}");
    public static final Route VOTE_ON_SEGMENT_CATEGORY = new Route(POST, "/api/voteOnSponsorTime?userID={user_id}&UUID={segment_id}&category={category}");

    private SBRoutes() {
    }
}
