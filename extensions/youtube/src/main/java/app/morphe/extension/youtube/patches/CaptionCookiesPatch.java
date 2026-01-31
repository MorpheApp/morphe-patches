package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class CaptionCookiesPatch {
    private static final boolean SET_CAPTION_COOKIES = Settings.SET_CAPTION_COOKIES.get();
    private static final String CAPTION_COOKIES = Settings.CAPTION_COOKIES.get();
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    private static volatile boolean requireCookies = false;

    /**
     * Injection point.
     */
    public static boolean getRequireCookies() {
        return requireCookies;
    }

    /**
     * Injection point.
     */
    public static void setRequireCookies(String url) {
        requireCookies = SET_CAPTION_COOKIES && !CAPTION_COOKIES.isEmpty()
                && url != null && url.contains("api/timedtext");
    }

    /**
     * Injection point.
     */
    public static String getCookies() {
        return CAPTION_COOKIES;
    }

    /**
     * Injection point.
     */
    public static String getUserAgent() {
        return USER_AGENT;
    }
}
