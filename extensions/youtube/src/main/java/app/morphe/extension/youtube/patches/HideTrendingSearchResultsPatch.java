package app.morphe.extension.youtube.patches;

@SuppressWarnings("unused")
public class HideTrendingSearchResultsPatch {

    /**
     * Injection point.
     */
    public static boolean isTypingStringEmpty(String value) {
        return value.isEmpty();
    }
}
