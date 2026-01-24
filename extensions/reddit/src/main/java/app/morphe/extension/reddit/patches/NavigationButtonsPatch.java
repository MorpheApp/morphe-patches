package app.morphe.extension.reddit.patches;

import android.content.res.Resources;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class NavigationButtonsPatch {
    private static Resources mResources;
    private static final Map<Object, String> navigationMap = new LinkedHashMap<>(NavigationButton.values().length);

    public static void setResources(Resources resources) {
        mResources = resources;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static void setNavigationMap(Object object, String label) {
        for (NavigationButton button : NavigationButton.values()) {
            if (label.equals(mResources.getString(button.id)) && button.enabled) {
                navigationMap.putIfAbsent(object, label);
            }
        }
    }

    public static void hideNavigationButtons(List<Object> list, Object object) {
        if (list != null && !navigationMap.containsKey(object)) {
            list.add(object);
        }
    }

    public static List<Object> hideNavigationButtons(List<Object> list) {
        try {
            for (NavigationButton button : NavigationButton.values()) {
                if (button.enabled && list.size() > button.index) {
                    list.remove(button.index);
                }
            }
        } catch (Exception exception) {
            Logger.printException(() -> "Failed to remove button list", exception);
        }
        return list;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static Object[] hideNavigationButtons(Object[] array) {
        try {
            for (NavigationButton button : NavigationButton.values()) {
                if (button.enabled && array.length > button.index) {
                    Object buttonObject = array[button.index];
                    array = Arrays.stream(array)
                            .filter(item -> !Objects.equals(item, buttonObject))
                            .toArray(Object[]::new);
                }
            }
        } catch (Exception exception) {
            Logger.printException(() -> "Failed to remove button array", exception);
        }
        return array;
    }

    public static void hideNavigationButtons(ViewGroup viewGroup) {
        try {
            if (viewGroup == null) return;
            for (NavigationButton button : NavigationButton.values()) {
                if (button.enabled && viewGroup.getChildCount() > button.index) {
                    View view = viewGroup.getChildAt(button.index);
                    if (view != null) view.setVisibility(View.GONE);
                }
            }
        } catch (Exception exception) {
            Logger.printException(() -> "Failed to remove button view", exception);
        }
    }

    private enum NavigationButton {
        CHAT(Settings.HIDE_CHAT_BUTTON.get(), 3, "label_chat"),
        CREATE(Settings.HIDE_CREATE_BUTTON.get(), 2, "action_create"),
        DISCOVER(Settings.HIDE_DISCOVER_BUTTON.get(), 1, "communities_label");
        private final boolean enabled;
        private final int index;
        private final int id;

        NavigationButton(final boolean enabled, final int index, final String label) {
            this.enabled = enabled;
            this.index = index;

            // FIXME: This is returning invalid resource IDs for some reason.
            this.id = ResourceUtils.getIdentifier(label, ResourceUtils.ResourceType.STRING, Utils.getContext());

            if (this.id == 0) {
                throw new IllegalArgumentException(Utils.getContext().getPackageName() + ": Unable to find navigation button corresponding to label " + label);
            }
        }
    }
}
