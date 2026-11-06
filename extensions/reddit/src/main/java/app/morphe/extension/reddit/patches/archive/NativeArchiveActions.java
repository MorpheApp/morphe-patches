package app.morphe.extension.reddit.patches.archive;

/** All host calls are replaced and structurally verified against the supported APK at patch time. */
public final class NativeArchiveActions {
    private NativeArchiveActions() {}
    static Object scope() { throw new IllegalStateException("Missing Reddit action bridge"); }
    static boolean signedIn(Object scope) { return false; }
    static Object service(Object scope, boolean comment) { return null; }
    static Object load(Object service, String fullname, boolean comment, ArchiveActionContinuation callback) { return null; }
    static Object vote(Object service, String fullname, boolean comment, int direction, ArchiveActionContinuation callback) { return null; }
    static Object unwrap(Object result) { return null; }
    static boolean success(Object result, boolean comment) { return false; }
    static ArchiveVoteState state(Object model, boolean comment) { return null; }
    static Integer cachedDirection(String fullname) { return null; }
    static void publish(String fullname, int direction) {}
    static Object suspended() { return null; }
    static String[] postInfo(Object model) { return null; }
    static Object joinEvent(String fullname, String subredditId, String subreddit) { return null; }
    static Object repostEvent(String fullname, String subredditId) { return null; }
    static void dispatch(Object eventContext, Object event) {}
    public static void joinSucceeded(Object receiver) {}
    public static void joinFailed(Object receiver) {}
    public static void joinResult(Object event, boolean success) { ArchiveActions.joinResult(event, success); }
}
