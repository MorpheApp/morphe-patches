package app.morphe.extension.reddit.patches;

import android.content.res.Resources;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class NavigationButtonsPatch {
    private static Resources mResources;
    private static final Map<Object, String> hiddenButtonMap = new HashMap<>(NavigationButton.values().length);
    private static final Map<String, String> labelMap = new HashMap<>();

    public static void setResources(Resources resources) {
        mResources = resources;
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static void mapResourceId(int id) {
        String resourceName = mResources.getResourceEntryName(id);
        String label = mResources.getString(id);
        labelMap.put(label, resourceName);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static void setNavigationMap(Object object, String label) {
        String labelName = labelMap.get(label);
        for (NavigationButton button : NavigationButton.values()) {
            if (button.label.equals(labelName) && button.shouldHide) {
                hiddenButtonMap.putIfAbsent(object, label);
            }
        }
    }

    public static void hideNavigationButtons(List<Object> list, Object object) {
        if (list != null && !hiddenButtonMap.containsKey(object)) {
            list.add(object);
        }
    }

    private enum NavigationButton {
        ANSWERS(Settings.HIDE_ANSWERS_BUTTON.get(), "answers_label"),
        CHAT(Settings.HIDE_CHAT_BUTTON.get(), "label_chat"),
        CREATE(Settings.HIDE_CREATE_BUTTON.get(), "action_create"),
        DISCOVER(Settings.HIDE_DISCOVER_BUTTON.get(), "communities_label"),
        GAMES(Settings.HIDE_GAMES_BUTTON.get(), "label_games");
        private final boolean shouldHide;
        private final String label;

        NavigationButton(final boolean shouldHide, final String label) {
            this.shouldHide = shouldHide;
            this.label = label;
        }
    }
}
