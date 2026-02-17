package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.Utils.equalsAny;
import static app.morphe.extension.shared.Utils.hideViewUnderCondition;
import static app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

import android.os.Build;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class NavigationBarPatch {

    private static final Map<NavigationButton, Boolean> shouldHideMap = new EnumMap<>(NavigationButton.class) {
        {
            put(NavigationButton.HOME, Settings.HIDE_HOME_BUTTON.get());
            put(NavigationButton.CREATE, Settings.HIDE_CREATE_BUTTON.get());
            put(NavigationButton.NOTIFICATIONS, Settings.HIDE_NOTIFICATIONS_BUTTON.get());
            put(NavigationButton.SHORTS, Settings.HIDE_SHORTS_BUTTON.get());
            put(NavigationButton.SUBSCRIPTIONS, Settings.HIDE_SUBSCRIPTIONS_BUTTON.get());
        }
    };

    private static final boolean SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON
            = Settings.SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON.get();

    private static final boolean DISABLE_TRANSLUCENT_STATUS_BAR
            = Settings.DISABLE_TRANSLUCENT_STATUS_BAR.get();

    private static final boolean DISABLE_TRANSLUCENT_NAVIGATION_BAR_LIGHT
            = Settings.DISABLE_TRANSLUCENT_NAVIGATION_BAR_LIGHT.get();

    private static final boolean DISABLE_TRANSLUCENT_NAVIGATION_BAR_DARK
            = Settings.DISABLE_TRANSLUCENT_NAVIGATION_BAR_DARK.get();

    private static final boolean NARROW_NAVIGATION_BUTTONS
            = Settings.NARROW_NAVIGATION_BUTTONS.get();

    /**
     * Injection point.
     */
    public static String swapCreateWithNotificationButton(String osName) {
        return SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON
                ? "Android Automotive"
                : osName;
    }

    /**
     * Injection point.
     */
    public static void navigationTabCreated(NavigationButton button, View tabView) {
        if (Boolean.TRUE.equals(shouldHideMap.get(button))) {
            tabView.setVisibility(View.GONE);
        }
    }

    /**
     * Injection point.
     */
    public static void hideNavigationButtonLabels(TextView navigationLabelsView) {
        hideViewUnderCondition(Settings.HIDE_NAVIGATION_BUTTON_LABELS, navigationLabelsView);
    }

    /**
     * Injection point.
     */
    public static boolean useAnimatedNavigationButtons(boolean original) {
        return Settings.NAVIGATION_BAR_ANIMATIONS.get();
    }

    /**
     * Injection point.
     */
    public static boolean enableNarrowNavigationButton(boolean original) {
        return NARROW_NAVIGATION_BUTTONS || original;
    }

    /**
     * Injection point.
     */
    public static boolean allowCollapsingToolbarLayout(boolean original) {
        if (DISABLE_TRANSLUCENT_STATUS_BAR) return false;
        return original;
    }

    /**
     * Injection point.
     */
    public static boolean useTranslucentNavigationStatusBar(boolean original) {
        // Must check Android version, as forcing this on Android 11 or lower causes app hang and crash.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return original;
        }

        if (DISABLE_TRANSLUCENT_STATUS_BAR) {
            return false;
        }

        return original;
    }

    /**
     * Injection point.
     */
    public static boolean useTranslucentNavigationButtons(boolean original) {
        // Feature requires Android 13+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return original;
        }

        if (!DISABLE_TRANSLUCENT_NAVIGATION_BAR_DARK && !DISABLE_TRANSLUCENT_NAVIGATION_BAR_LIGHT) {
            return original;
        }

        if (DISABLE_TRANSLUCENT_NAVIGATION_BAR_DARK && DISABLE_TRANSLUCENT_NAVIGATION_BAR_LIGHT) {
            return false;
        }

        return Utils.isDarkModeEnabled()
                ? !DISABLE_TRANSLUCENT_NAVIGATION_BAR_DARK
                : !DISABLE_TRANSLUCENT_NAVIGATION_BAR_LIGHT;
    }

    // Toolbar
    private static final String[] CREATE_BUTTON_ENUMS = {
            "CREATION_ENTRY", // Phone layout.
            "FAB_CAMERA" // Tablet layout.
    };

    private static final String[] NOTIFICATION_BUTTON_ENUMS = {
            "TAB_ACTIVITY_CAIRO", // New layout.
            "TAB_ACTIVITY" // Old layout.
    };

    private static final String[] SEARCH_BUTTON_ENUMS = {
            "SEARCH_CAIRO", // New layout.
            "SEARCH_BOLD", // Shorts.
            "SEARCH" // Old layout.
    };

    private static final boolean HIDE_TOOLBAR_CREATE_BUTTON = Settings.HIDE_TOOLBAR_CREATE_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_NOTIFICATION_BUTTON = Settings.HIDE_TOOLBAR_NOTIFICATION_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_SEARCH_BUTTON = Settings.HIDE_TOOLBAR_SEARCH_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_VOICE_SEARCH_BUTTON = Settings.HIDE_TOOLBAR_VOICE_SEARCH_BUTTON .get();

    /**
     * Injection point.
     */
    public static void hideCreateButton(String enumName, View view) {
        boolean shouldHide = HIDE_TOOLBAR_CREATE_BUTTON && equalsAny(enumName, CREATE_BUTTON_ENUMS);
        hideViewUnderCondition(shouldHide, view);
    }

    /**
     * Injection point.
     */
    public static void hideNotificationButton(String enumName, View view) {
        boolean shouldHide = HIDE_TOOLBAR_NOTIFICATION_BUTTON && equalsAny(enumName, NOTIFICATION_BUTTON_ENUMS);
        hideViewUnderCondition(shouldHide, view);
    }

    /**
     * Injection point.
     */
    public static void hideSearchButton(String enumName, View view) {
        boolean shouldHide = HIDE_TOOLBAR_SEARCH_BUTTON && equalsAny(enumName, SEARCH_BUTTON_ENUMS);
        hideViewUnderCondition(shouldHide, view);
    }

    /**
     * Injection point.
     */
    public static void hideOldSearchButton(MenuItem menuItem, int original) {
        int actionEnum = HIDE_TOOLBAR_SEARCH_BUTTON ? MenuItem.SHOW_AS_ACTION_NEVER : original;
        menuItem.setShowAsAction(actionEnum);
    }

    /**
     * Injection point.
     */
    public static void hideVoiceSearchButton(View view) {
        hideViewUnderCondition(HIDE_TOOLBAR_VOICE_SEARCH_BUTTON, view);
    }

    /**
     * Injection point.
     */
    public static void hideVoiceSearchButton(View view, int visibility) {
        view.setVisibility(HIDE_TOOLBAR_VOICE_SEARCH_BUTTON ? View.GONE : visibility);
    }

    // Wide searchbar
    private static final Boolean WIDE_SEARCHBAR_ENABLED = Settings.WIDE_SEARCHBAR.get();

    /**
     * Injection point.
     */
    public static boolean enableWideSearchbar(boolean original) {
        return WIDE_SEARCHBAR_ENABLED || original;
    }

    /**
     * Injection point.
     */
    public static void setActionBar(View view) {
        try {
            if (!WIDE_SEARCHBAR_ENABLED) return;

            View searchBarView = Utils.getChildViewByResourceName(view, "search_bar");

            final int paddingLeft = searchBarView.getPaddingLeft();
            final int paddingRight = searchBarView.getPaddingRight();
            final int paddingTop = searchBarView.getPaddingTop();
            final int paddingBottom = searchBarView.getPaddingBottom();
            final int paddingStart = Dim.dp8;

            if (Utils.isRightToLeftLocale()) {
                searchBarView.setPadding(paddingLeft, paddingTop, paddingStart, paddingBottom);
            } else {
                searchBarView.setPadding(paddingStart, paddingTop, paddingRight, paddingBottom);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setActionBar failure", ex);
        }
    }
}
