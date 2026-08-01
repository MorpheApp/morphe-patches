package app.morphe.extension.shared.sponsorblock.requests;

import app.morphe.extension.shared.requests.Route;

public class SBRoutes {
    public static final Route IS_USER_VIP = new Route(Route.Method.GET, "/api/isUserVIP?userID={user_id}");
    public static final Route GET_SEGMENTS = new Route(Route.Method.GET, "/api/skipSegments?videoID={video_id}&categories={categories}");
    public static final Route VIEWED_SEGMENT = new Route(Route.Method.POST, "/api/viewedVideoSponsorTime?UUID={segment_id}");
    public static final Route GET_USER_STATS = new Route(Route.Method.GET, "/api/userInfo?userID={user_id}&values=[\"userID\",\"userName\",\"reputation\",\"segmentCount\",\"ignoredSegmentCount\",\"viewCount\",\"minutesSaved\"]");
    public static final Route CHANGE_USERNAME = new Route(Route.Method.POST, "/api/setUsername?userID={user_id}&username={username}");
    public static final Route SUBMIT_SEGMENTS = new Route(Route.Method.POST, "/api/skipSegments?userID={user_id}&videoID={video_id}&category={category}&startTime={start_time}&endTime={end_time}&videoDuration={duration}&actionType={action_type}&userAgent={user_agent}");
    public static final Route VOTE_ON_SEGMENT_QUALITY = new Route(Route.Method.POST, "/api/voteOnSponsorTime?userID={user_id}&UUID={segment_id}&type={type}");
    public static final Route VOTE_ON_SEGMENT_CATEGORY = new Route(Route.Method.POST, "/api/voteOnSponsorTime?userID={user_id}&UUID={segment_id}&category={category}");

    private SBRoutes() {
    }
}
