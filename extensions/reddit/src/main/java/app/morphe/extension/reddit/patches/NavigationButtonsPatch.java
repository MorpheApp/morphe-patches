package app.morphe.extension.reddit.patches;

import android.content.res.Resources;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class NavigationButtonsPatch {
    private static Resources mResources;
    private static final Map<Object, String> navigationMap = new LinkedHashMap<>(NavigationButton.values().length);

    // Map labels back to their resource names. eg. "Chat" -> "label_chat"
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
            if (button.label.equals(labelName) && button.enabled) {
                navigationMap.putIfAbsent(object, label);
            }
        }
    }

    public static void hideNavigationButtons(List<Object> list, Object object) {
        if (list != null && !navigationMap.containsKey(object)) {
            list.add(object);
        }
    }

    private enum NavigationButton {
        ANSWERS(Settings.HIDE_ANSWERS_BUTTON.get(), "answers_label"),
        CHAT(Settings.HIDE_CHAT_BUTTON.get(), "label_chat"),
        CREATE(Settings.HIDE_CREATE_BUTTON.get(), "action_create"),
        DISCOVER(Settings.HIDE_DISCOVER_BUTTON.get(), "communities_label");
        private final boolean enabled;
        private final String label;

        NavigationButton(final boolean enabled, final String label) {
            this.enabled = enabled;
            this.label = label;
        }
    }
}
