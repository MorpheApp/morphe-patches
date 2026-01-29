package app.morphe.extension.reddit.patches;

import com.reddit.domain.model.ILink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class GeneralAdsPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    private static List<?> filterChildren(final Iterable<?> links) {
        final List<Object> filteredList = new ArrayList<>();

        for (Object item : links) {
            if (item instanceof ILink iLink && iLink.getPromoted()) continue;

            filteredList.add(item);
        }

        return filteredList;
    }

    public static Object hideCommentAdMap(Map<Object, Object> map, Object key, Object value) {
        if (!hideCommentAds()) {
            return map.put(key, value);
        }

        return map;
    }

    public static boolean hideCommentAds() {
        return Settings.HIDE_COMMENT_ADS.get();
    }

    public static List<?> hideOldPostAds(List<?> list) {
        if (!Settings.HIDE_OLD_POST_ADS.get())
            return list;

        return filterChildren(list);
    }

    public static List<?> hideNewPostAds(List<?> list) {
        return Settings.HIDE_NEW_POST_ADS.get()
                ? null
                : list;
    }

    public static void hideNewPostAds(ArrayList<Object> arrayList, Object object) {
        if (Settings.HIDE_NEW_POST_ADS.get())
            return;

        arrayList.add(object);
    }
}
