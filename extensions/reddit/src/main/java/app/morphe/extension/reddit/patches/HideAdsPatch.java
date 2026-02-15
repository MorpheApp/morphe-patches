package app.morphe.extension.reddit.patches;

import com.reddit.domain.model.ILink;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class HideAdsPatch {
    private static final boolean HIDE_COMMENT_ADS = Settings.HIDE_COMMENT_ADS.get();
    private static final boolean HIDE_POST_ADS = Settings.HIDE_POST_ADS.get();

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean hideCommentAds() {
        return HIDE_COMMENT_ADS;
    }

    /**
     * Injection point.
     */
    public static boolean hideCommentAds(boolean original) {
        return HIDE_COMMENT_ADS || original;
    }

    /**
     * Injection point.
     */
    public static List<?> hideOldPostAds(List<?> list) {
        if (HIDE_POST_ADS) {
            List<Object> filteredList = new ArrayList<>();

            for (Object item : list) {
                if (!(item instanceof ILink iLink) || !iLink.getPromoted()) {
                    filteredList.add(item);
                }
            }

            return filteredList;
        }

        return list;
    }

    /**
     * Injection point.
     */
    public static List<?> hideNewPostAds(List<?> list) {
        return HIDE_POST_ADS ? null : list;
    }
}
