/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.Utils.equalsAny;
import static app.morphe.extension.shared.Utils.hideViewUnderCondition;
import static app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.apps.youtube.app.application.Shell_SettingsActivity;
import com.google.android.libraries.social.licenses.LicenseActivity;
import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.Accessibility;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.AccessibilityData;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.ButtonRenderer;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.Buttons;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemRenderer;
import app.morphe.extension.youtube.innertube.IconOuterClass.Icon;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;

@SuppressWarnings("unused")
public final class NavigationBarPatch {

    public static class ReplaceToolbarCreateButtonAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON.get()
                    && !Settings.HIDE_TOOLBAR_CREATE_BUTTON.get();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(
                    Settings.SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON,
                    Settings.HIDE_TOOLBAR_CREATE_BUTTON
            );
        }
    }

    private static final Map<NavigationButton, Boolean> shouldHideMap = new EnumMap<>(NavigationButton.class) {
        {
            put(NavigationButton.HOME, Settings.HIDE_HOME_BUTTON.get());
            put(NavigationButton.CREATE, Settings.HIDE_CREATE_BUTTON.get());
            put(NavigationButton.NOTIFICATIONS, Settings.HIDE_NOTIFICATIONS_BUTTON.get());
            put(NavigationButton.SHORTS, Settings.HIDE_SHORTS_BUTTON.get());
            put(NavigationButton.SUBSCRIPTIONS, Settings.HIDE_SUBSCRIPTIONS_BUTTON.get());
        }
    };

    private static final boolean SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON = Settings.SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON.get();

    private static final boolean DISABLE_TRANSLUCENT_STATUS_BAR = Settings.DISABLE_TRANSLUCENT_STATUS_BAR.get();

    private static final boolean DISABLE_TRANSLUCENT_NAVIGATION_BAR_LIGHT = Settings.DISABLE_TRANSLUCENT_NAVIGATION_BAR_LIGHT.get();

    private static final boolean DISABLE_TRANSLUCENT_NAVIGATION_BAR_DARK = Settings.DISABLE_TRANSLUCENT_NAVIGATION_BAR_DARK.get();

    private static final boolean NARROW_NAVIGATION_BUTTONS = Settings.NARROW_NAVIGATION_BUTTONS.get();

    private static final boolean HIDE_NAVIGATION_BAR = Settings.HIDE_NAVIGATION_BAR.get();

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
        if (SHOW_SEARCH_BUTTON && button == NavigationButton.SEARCH) {
            Utils.runOnMainThread(() -> tabView.setOnClickListener(openSearchBarOnClickListener));
            return;
        }

        if (SHOW_SETTINGS_BUTTON && button == NavigationButton.SETTINGS) {
            Utils.runOnMainThread(() -> tabView.setOnClickListener(openSettingsOnClickListener));
            return;
        }

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
    public static void hideNavigationBar(View view) {
        hideViewUnderCondition(HIDE_NAVIGATION_BAR, view);
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

    // Navigation search and settings button
    private static final boolean SHOW_SEARCH_BUTTON = Settings.SHOW_SEARCH_BUTTON.get();
    private static final IntegerSetting SEARCH_BUTTON_INDEX = Settings.SEARCH_BUTTON_INDEX;

    private static volatile WeakReference<TextView> searchQueryRef = new WeakReference<>(null);

    private static Object pivotBarRenderer = null;
    private static View.OnClickListener openSearchBar = null;

    private static final boolean SHOW_SETTINGS_BUTTON = Settings.SHOW_SETTINGS_BUTTON.get();
    private static final IntegerSetting SETTINGS_BUTTON_INDEX = Settings.SETTINGS_BUTTON_INDEX;

    private static Object pivotBarSettingsRenderer = null;
    private static final View.OnClickListener openSettingsOnClickListener = NavigationBarPatch::openYouTubeSettings;

    private static final View.OnClickListener openSearchBarOnClickListener = v -> {
        if (NavigationBar.isSearchBarActive() && searchQueryRef.get() != null) {
            // If the search bar is active, simply click on the search query view.
            searchQueryRef.get().callOnClick();
        } else if (openSearchBar != null) {
            // If the search bar is not active, click the OnClickListener of the search button.
            openSearchBar.onClick(v);
        } else {
            // If the OnClickListener of the search button is not initialized, execute the shortcut.
            Context context = v.getContext();
            Intent intent = new Intent();
            intent.setAction("com.google.android.youtube.action.open.search");
            intent.setPackage(context.getPackageName());
            context.startActivity(intent);
        }
    };

    /**
     * Injection point.
     *
     * @param searchQuery The text view of the search query shown in the search results.
     */
    public static void searchQueryViewLoaded(TextView searchQuery) {
        if (SHOW_SEARCH_BUTTON) {
            searchQueryRef = new WeakReference<>(searchQuery);
        }
    }

    /**
     * Injection point.
     *
     * @param messageLite MessageLite class of ButtonRenderer in topBarRenderer.
     * @param listener    OnClickListener for topBar buttons (Create, Notification, Search, Settings).
     */
    public static void setSearchBarOnClickListener(MessageLite messageLite, View.OnClickListener listener) {
        if (SHOW_SEARCH_BUTTON) {
            try {
                var buttonRenderer = ButtonRenderer.parseFrom(messageLite.toByteArray());
                if (buttonRenderer.hasIcon()) {
                    var iconName = buttonRenderer.getIcon().getYtIconType().name();

                    // Check the icon name to see if it is the OnClickListener of the search button.
                    if (NavigationButton.SEARCH.ytEnumNames.contains(iconName)) {
                        openSearchBar = listener;
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to set search bar OnClickListener", ex);
            }
        }
    }

    /**
     * Injection point.
     *
     * @param messageLite MessageLite class of PivotBarItemRenderer.
     */
    @Nullable
    public static byte[] parsePivotBarItemRenderer(MessageLite messageLite) {
        if (SHOW_SEARCH_BUTTON) {
            try {
                var pivotBarItemBuilder = PivotBarItemRenderer.parseFrom(messageLite.toByteArray()).toBuilder();
                var iconName = pivotBarItemBuilder.getIcon().getYtIconType().name();

                // Check the icon name to see if it is the PivotBarItemRenderer of the home button.
                // If other buttons besides the home button are used, the code becomes more complex.
                if (NavigationButton.HOME.ytEnumNames.contains(iconName)) {
                    // Change the label and icon of the navigation button.
                    var newAccessibilityData = AccessibilityData.newBuilder().setLabel(str("menu_search")).build();
                    var newAccessibility = Accessibility.newBuilder().setAccessibilityData(newAccessibilityData).build();
                    var ytIconType = Utils.appIsUsingBoldIcons() ? YTIconType.SEARCH_BOLD : YTIconType.SEARCH_CAIRO;
                    var newIcon = Icon.newBuilder().setYtIconType(ytIconType).build();

                    pivotBarItemBuilder.clearAccessibility();
                    pivotBarItemBuilder.setAccessibility(newAccessibility);
                    pivotBarItemBuilder.clearIcon();
                    pivotBarItemBuilder.setIcon(newIcon);

                    return pivotBarItemBuilder.build().toByteArray();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to parse PivotBarItemRenderer", ex);
            }
        }

        return null;
    }

    /**
     * Injection point.
     * Called after {@link #parsePivotBarItemRenderer(MessageLite)}.
     *
     * @param object Classes used in YouTube with MessageLite.
     */
    public static void setPivotBarRenderer(Object object) {
        if (SHOW_SEARCH_BUTTON) {
            pivotBarRenderer = object;
        }
    }

    /**
     * Injection point.
     * Called after {@link #setPivotBarRenderer(Object)}.
     *
     * @param list Proto list containing PivotBarRenderer.
     */
    public static List<Object> getPivotBarRendererList(List<Object> list) {
        if (list == null || list.isEmpty()) return list;

        boolean addSearch = SHOW_SEARCH_BUTTON && pivotBarRenderer != null;
        boolean addSettings = SHOW_SETTINGS_BUTTON && pivotBarSettingsRenderer != null;

        if (!addSearch && !addSettings) return list;

        List<Object> newList = new ArrayList<>(list);

        if (addSearch) {
            int preferredIndex = SEARCH_BUTTON_INDEX.get();
            if (preferredIndex < 0 || preferredIndex > newList.size()) {
                Utils.showToastShort(str("morphe_search_button_index_invalid", newList.size()));
                SEARCH_BUTTON_INDEX.resetToDefault();
                preferredIndex = SEARCH_BUTTON_INDEX.defaultValue;
            }
            newList.add(preferredIndex, pivotBarRenderer);
        }

        if (addSettings) {
            int preferredIndex = SETTINGS_BUTTON_INDEX.get();
            if (preferredIndex < 0 || preferredIndex > newList.size()) {
                Utils.showToastShort(str("morphe_settings_button_index_invalid", newList.size()));
                SETTINGS_BUTTON_INDEX.resetToDefault();
                preferredIndex = SETTINGS_BUTTON_INDEX.defaultValue;
            }
            newList.add(preferredIndex, pivotBarSettingsRenderer);
        }

        return newList;
    }

    @Nullable
    public static byte[] parseSettingsPivotBarItemRenderer(MessageLite messageLite) {
        if (SHOW_SETTINGS_BUTTON) {
            try {
                var pivotBarItemBuilder = PivotBarItemRenderer.parseFrom(messageLite.toByteArray()).toBuilder();
                var iconName = pivotBarItemBuilder.getIcon().getYtIconType().name();

                if (NavigationButton.HOME.ytEnumNames.contains(iconName)) {
                    var newAccessibilityData = AccessibilityData.newBuilder().setLabel(str("menu_settings")).build();
                    var newAccessibility = Accessibility.newBuilder().setAccessibilityData(newAccessibilityData).build();

                    var newIcon = Icon.newBuilder().setYtIconType(YTIconType.SETTINGS_CAIRO).build();

                    pivotBarItemBuilder.clearAccessibility();
                    pivotBarItemBuilder.setAccessibility(newAccessibility);
                    pivotBarItemBuilder.clearIcon();
                    pivotBarItemBuilder.setIcon(newIcon);

                    return pivotBarItemBuilder.build().toByteArray();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to parse Settings PivotBarItemRenderer", ex);
            }
        }
        return null;
    }

    public static void setPivotBarSettingsRenderer(Object object) {
        if (SHOW_SETTINGS_BUTTON) {
            pivotBarSettingsRenderer = object;
        }
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

    private static final String SETTING_BUTTON_ENUM_NAME = "SETTINGS_CAIRO";

    private static final boolean HIDE_TOOLBAR_CREATE_BUTTON = Settings.HIDE_TOOLBAR_CREATE_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_NOTIFICATION_BUTTON = Settings.HIDE_TOOLBAR_NOTIFICATION_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_SEARCH_BUTTON = Settings.HIDE_TOOLBAR_SEARCH_BUTTON.get();

    private static final boolean HIDE_TOOLBAR_MICROPHONE_BUTTON = Settings.HIDE_TOOLBAR_MICROPHONE_BUTTON.get();

    private static final boolean REPLACE_TOOLBAR_CREATE_BUTTON = SWAP_CREATE_WITH_NOTIFICATIONS_BUTTON
            && !HIDE_TOOLBAR_CREATE_BUTTON && Settings.REPLACE_TOOLBAR_CREATE_BUTTON.get();

    private static final boolean REPLACE_TOOLBAR_CREATE_BUTTON_TYPE = Settings.REPLACE_TOOLBAR_CREATE_BUTTON_TYPE.get();

    private static final boolean REARRANGE_TOOLBAR_BUTTONS = REPLACE_TOOLBAR_CREATE_BUTTON
            && Settings.REARRANGE_TOOLBAR_BUTTONS.get();

    /**
     * Interface to use obfuscated methods.
     */
    public interface SettingsController {
        // Methods are added during patching.
        void patch_openYouTubeSettings();
    }

    private static WeakReference<SettingsController> settingsControllerRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void hideCreateButton(String enumName, View parentView, ImageView imageView) {
        boolean shouldHide = HIDE_TOOLBAR_CREATE_BUTTON && equalsAny(enumName, CREATE_BUTTON_ENUMS);
        hideViewUnderCondition(shouldHide, parentView);
    }

    /**
     * Injection point.
     */
    public static void hideNotificationButton(String enumName, View parentView, ImageView imageView) {
        boolean shouldHide = HIDE_TOOLBAR_NOTIFICATION_BUTTON && equalsAny(enumName, NOTIFICATION_BUTTON_ENUMS);
        hideViewUnderCondition(shouldHide, parentView);
    }

    /**
     * Injection point.
     */
    public static void hideSearchButton(String enumName, View parentView, ImageView imageView) {
        boolean shouldHide = HIDE_TOOLBAR_SEARCH_BUTTON && NavigationButton.SEARCH.ytEnumNames.contains(enumName);
        hideViewUnderCondition(shouldHide, parentView);
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
    public static void hideMicrophoneButton(View view) {
        hideViewUnderCondition(HIDE_TOOLBAR_MICROPHONE_BUTTON, view);
    }

    /**
     * Injection point.
     */
    public static void hideMicrophoneButton(View view, int visibility) {
        view.setVisibility(HIDE_TOOLBAR_MICROPHONE_BUTTON ? View.GONE : visibility);
    }

    /**
     * Injection point.
     */
    public static void setSettingsController(@NonNull SettingsController settingsController) {
        if (REPLACE_TOOLBAR_CREATE_BUTTON || SHOW_SETTINGS_BUTTON) {
            settingsControllerRef = new WeakReference<>(settingsController);
        }
    }

    /**
     * Injection point.
     */
    public static void reRearrangeToolbarButtons(List<MessageLite> rawButtonList) {
        if (REARRANGE_TOOLBAR_BUTTONS && rawButtonList != null && !rawButtonList.isEmpty()) {
            try {
                boolean containsCreateButton = false;

                for (var rawButtons : rawButtonList) {
                    var buttons = Buttons.parseFrom(rawButtons.toByteArray());
                    if (buttons.hasButtonRenderer()) {
                        var navigationEndpoint = buttons.getButtonRenderer().getNavigationEndpoint();
                        // Rearrange only if there is a Create button.
                        if (navigationEndpoint.hasCreationEntryEndpoint()) {
                            containsCreateButton = true;
                            break;
                        }
                    }
                }

                if (containsCreateButton) {
                    Collections.rotate(rawButtonList, -1);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to parse Buttons", ex);
            }
        }
    }

    /**
     * Injection point.
     */
    @Nullable
    public static byte[] setCreateButtonIcon(MessageLite messageLite) {
        if (REPLACE_TOOLBAR_CREATE_BUTTON) {
            try {
                var buttonRenderer = ButtonRenderer.parseFrom(messageLite.toByteArray()).toBuilder();
                if (buttonRenderer.hasIcon()) {
                    var iconName = buttonRenderer.getIcon().getYtIconType().name();

                    if (Utils.equalsAny(iconName, CREATE_BUTTON_ENUMS)) {
                        var newIcon = Icon.newBuilder().setYtIconType(YTIconType.SETTINGS_CAIRO).build();

                        // Remove accessibility labels and onclick listeners.
                        buttonRenderer.clearButtonRendererAccessibilityData();
                        buttonRenderer.clearRendererAccessibilityData();
                        buttonRenderer.clearNavigationEndpoint();

                        // Replace icons.
                        buttonRenderer.clearIcon();
                        buttonRenderer.setIcon(newIcon);

                        return buttonRenderer.build().toByteArray();
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to parse ButtonRenderer", ex);
            }
        }

        return null;
    }

    /**
     * Injection point.
     */
    public static void setCreateButtonOnClickListener(String enumName, View parentView, ImageView imageView) {
        if (REPLACE_TOOLBAR_CREATE_BUTTON && SETTING_BUTTON_ENUM_NAME.equals(enumName)) {
            Utils.runOnMainThreadDelayed(() -> {
                if (REPLACE_TOOLBAR_CREATE_BUTTON_TYPE) {
                    imageView.setOnClickListener(NavigationBarPatch::openMorpheSettings);
                    imageView.setOnLongClickListener(button -> {
                        openYouTubeSettings(button);
                        return true;
                    });
                } else {
                    imageView.setOnClickListener(NavigationBarPatch::openYouTubeSettings);
                    imageView.setOnLongClickListener(button -> {
                        openMorpheSettings(button);
                        return true;
                    });
                }
            }, 100);
        }
    }

    private static void openYouTubeSettings(View view) {
        SettingsController settingsController = settingsControllerRef.get();
        if (settingsController != null) {
            settingsController.patch_openYouTubeSettings();
            return;
        }

        Activity activity = Utils.getActivity();
        Context context = activity != null ? activity : view.getContext();

        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setPackage(context.getPackageName());
            intent.setClass(context, Shell_SettingsActivity.class);

            if (activity == null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            context.startActivity(intent);
        } catch (Exception e) {
            Logger.printException(() -> "Failed to open YouTube settings", e);
        }
    }

    private static void openMorpheSettings(View view) {
        Activity activity = Utils.getActivity();
        Context context = activity != null ? activity : view.getContext();

        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setData(Uri.parse("morphe_settings_intent"));
            intent.setPackage(context.getPackageName());
            intent.setClass(context, LicenseActivity.class);

            if (activity == null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            context.startActivity(intent);
        } catch (Exception e) {
            Logger.printException(() -> "Failed to open Morphe settings", e);
        }
    }

    // Wide searchbar
    private static final boolean WIDE_SEARCHBAR_ENABLED = Settings.WIDE_SEARCHBAR.get();

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
        if (WIDE_SEARCHBAR_ENABLED) {
            try {
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
}