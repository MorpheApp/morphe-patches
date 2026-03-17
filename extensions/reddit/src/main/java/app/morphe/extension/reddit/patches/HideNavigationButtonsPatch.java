/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import android.content.res.Resources;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class HideNavigationButtonsPatch {
    /**
     * Interface to use obfuscated methods.
     */
    public interface NavigationButtonInterface {
        // Methods are added during patching.
        String patch_getLabel();
    }

    private static final List<String> labels = new ArrayList<>(10);
    private static Resources mResources;

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static void setResources(Resources resources) {
        labels.clear();
        mResources = resources;
    }

    /**
     * Injection point.
     */
    public static void mapResourceId(int id) {
        String resourceName = mResources.getResourceEntryName(id);
        for (NavigationButton button : NavigationButton.values()) {
            if (button.label.equals(resourceName) && button.shouldHide) {
                labels.add(mResources.getString(id));
            }
        }
    }

    /**
     * Injection point.
     */
    public static void hideNavigationButtons(List<Object> list, Object object) {
        if (list != null) {
            if (object instanceof NavigationButtonInterface button) {
                String label = button.patch_getLabel();
                if (label != null && labels.contains(label)) {
                    return;
                }
            }
            list.add(object);
        }
    }

    /**
     * Injection point.
     */
    public static boolean hideNavigationTab(@Nullable Enum<?> tab) {
        if (tab != null) {
            String tabName = tab.name();
            for (BottomNavTab navTab : BottomNavTab.values()) {
                if (navTab.name().equals(tabName) && navTab.enabled) {
                    return true;
                }
            }
        }
        return false;
    }

    private enum BottomNavTab {
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

        private final boolean enabled;

        BottomNavTab(boolean enabled) {
            this.enabled = enabled;
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
