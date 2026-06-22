/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.ResourceUtils;

@SuppressWarnings("unused")
public final class HideNavigationButtonsPatch {
    /**
     * Interface to use obfuscated methods.
     */
    public interface NavigationButtonInterface {
        // Methods are added during patching.
        String patch_getLabel();
    }

    private static final Map<String, NavigationButtonLegacy> labelToButtonLegacy = new HashMap<>();
    private static final Map<String, NavigationButton> labelToButton = new HashMap<>();

    static {
        for (NavigationButtonLegacy button : NavigationButtonLegacy.values()) {
            labelToButtonLegacy.put(button.label, button);
        }

        for (NavigationButton button : NavigationButton.values()) {
            labelToButton.put(button.name(), button);
        }
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static void hideNavigationButtonsLegacy(List<Object> list, Object object) {
        if (list == null) {
            return;
        }

        if (object instanceof NavigationButtonInterface button) {
            String label = button.patch_getLabel();
            if (label != null) {
                NavigationButtonLegacy buttonEnum = labelToButtonLegacy.get(label);
                if (buttonEnum != null && buttonEnum.shouldHide) {
                    return;
                }
            }
        }

        list.add(object);
    }

    /**
     * Injection point.
     */
    public static boolean hideNavigationTab(@Nullable Enum<?> tab) {
        if (tab == null) {
            return false;
        }

        NavigationButton button = labelToButton.get(tab.name());
        return button != null && button.shouldHide;
    }

    private enum NavigationButton {
        Answers(Settings.HIDE_ANSWERS_BUTTON.get()),
        Chat(Settings.HIDE_CHAT_BUTTON.get()),
        Communities(Settings.HIDE_DISCOVER_BUTTON.get()),
        Games(Settings.HIDE_GAMES_BUTTON.get()),
        Home(false),
        Inbox(false),
        MyCommunities(Settings.HIDE_DISCOVER_BUTTON.get()),
        Post(Settings.HIDE_CREATE_BUTTON.get()),
        Profile(false),
        UnifiedInbox(false);

        final boolean shouldHide;

        NavigationButton(boolean shouldHide) {
            this.shouldHide = shouldHide;
        }
    }

    private enum NavigationButtonLegacy {
        ANSWERS(Settings.HIDE_ANSWERS_BUTTON.get(), "answers_label"),
        CHAT(Settings.HIDE_CHAT_BUTTON.get(), "label_chat"),
        CREATE(Settings.HIDE_CREATE_BUTTON.get(), "action_create"),
        DISCOVER(Settings.HIDE_DISCOVER_BUTTON.get(), "communities_label"),
        GAMES(Settings.HIDE_GAMES_BUTTON.get(), "label_games");

        final boolean shouldHide;
        final String label;

        NavigationButtonLegacy(boolean shouldHide, String resourceName) {
            this.shouldHide = shouldHide;
            this.label = ResourceUtils.getString(resourceName);
        }
    }
}
