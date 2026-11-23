/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5254
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.music.settings.MusicActivityHook;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.GuideResponseOuterClass.ButtonRenderer;
import app.morphe.extension.shared.innertube.IconOuterClass.YTIconType;

@SuppressWarnings("unused")
public class NavigationBarPatch {
    private static String lastYTNavigationEnumName = "";

    public static void setLastAppNavigationEnum(@Nullable Enum<?> ytNavigationEnumName) {
        if (ytNavigationEnumName != null) {
            lastYTNavigationEnumName = ytNavigationEnumName.name();
        }
    }

    public static void hideNavigationLabel(TextView textview) {
        Utils.hideViewUnderCondition(Settings.HIDE_NAVIGATION_BAR_LABEL.get(), textview);
    }

    public static void hideNavigationButton(View view) {
        // Hide entire navigation bar.
        if (Settings.HIDE_NAVIGATION_BAR.get() && view.getParent() != null) {
            Utils.hideViewUnderCondition(true, (View) view.getParent());
            return;
        }

        // Hide navigation buttons based on their type.
        for (NavigationButton button : NavigationButton.values()) {
            if (button.ytEnumNames.contains(lastYTNavigationEnumName)) {
                Utils.hideViewUnderCondition(button.hidden, view);
                break;
            }
        }
    }

    //
    // Settings button
    //

    private static final boolean SHOW_NAVIGATION_BAR_SETTINGS_BUTTON = Settings.SHOW_NAVIGATION_BAR_SETTINGS_BUTTON.get();

    /**
     * If a tap of the Settings button opens the Morphe settings and a long press the YouTube Music settings,
     * or the other way around.
     */
    private static final boolean NAVIGATION_BAR_SETTINGS_BUTTON_TAP_OPENS_MORPHE =
            Settings.SHOW_NAVIGATION_BAR_SETTINGS_BUTTON_TYPE.get();

    private static final boolean SHOW_TOOLBAR_SETTINGS_BUTTON = Settings.SHOW_TOOLBAR_SETTINGS_BUTTON.get();

    private static final boolean TOOLBAR_SETTINGS_BUTTON_TAP_OPENS_MORPHE =
            Settings.SHOW_TOOLBAR_SETTINGS_BUTTON_TYPE.get();

    /**
     * Browse id of the Settings button of the navigation bar, which has no page.
     */
    private static final String SETTINGS_BUTTON_BROWSE_ID = "morphe_settings";

    /**
     * Name of the app string used as the label of the Settings buttons.
     */
    private static final String SETTINGS_LABEL_STRING_NAME = "settings";

    /**
     * ID of the menu item of the Settings button of the toolbar, added during patching.
     */
    private static final String SETTINGS_MENU_ITEM_ID_NAME = "morphe_settings_menu_item";

    /**
     * Order of the menu items of the toolbar, the same as the order of the buttons of the app.
     */
    private static final int TOOLBAR_MENU_ITEM_ORDER = 2;

    private static final String YOUTUBE_MUSIC_SETTINGS_ACTIVITY_CLASS =
            "com.google.android.apps.youtube.music.settings.SettingsCompatActivity";
    private static final String YOUTUBE_MUSIC_SETTINGS_FRAGMENT_CLASS =
            "com.google.android.apps.youtube.music.settings.fragment.SettingsHeadersFragment";
    private static final String EXTRA_SHOW_FRAGMENT = ":android:show_fragment";
    private static final String EXTRA_NO_HEADERS = ":android:no_headers";

    /**
     * Activity of the toolbar, for the Settings button that is added again
     * after a page removes all the buttons of the menu.
     */
    private static WeakReference<Activity> toolbarActivityRef = new WeakReference<>(null);

    /**
     * ID of the hidden offline settings item of the toolbar, which has the gear icon of the app.
     */
    private static final String OFFLINE_SETTINGS_MENU_ITEM_ID_NAME = "offline_settings_menu_item";

    /**
     * Gear icon of the app, in the style of the other toolbar icons, such as bold icons.
     */
    @Nullable
    private static volatile Drawable.ConstantState toolbarSettingsIcon;

    /**
     * Injection point.
     * Adds the Settings button after the other buttons of the navigation bar.
     *
     * @param buttons Proto messages of the navigation buttons.
     */
    public static List<Object> addSettingsNavigationButton(List<Object> buttons) {
        if (!SHOW_NAVIGATION_BAR_SETTINGS_BUTTON) return buttons;

        try {
            List<Object> newButtons = new ArrayList<>(buttons);
            newButtons.add(createNavigationButton(SETTINGS_BUTTON_BROWSE_ID,
                    str(SETTINGS_LABEL_STRING_NAME), YTIconType.SETTINGS_VALUE));
            return newButtons;
        } catch (Exception ex) {
            Logger.printException(() -> "addSettingsNavigationButton failure", ex);
            return buttons;
        }
    }

    /**
     * Method is modified during patching to create the navigation button proto with the factory of the app.
     */
    @SuppressWarnings("SameParameterValue")
    private static Object createNavigationButton(String browseId, String label, int iconType) {
        throw new UnsupportedOperationException();
    }

    /**
     * Injection point.
     * Called for every navigation button, after {@link #setLastAppNavigationEnum(Enum)}.
     * The touches of the Settings button are handled here and not by the navigation bar,
     * so the navigation bar does not select the button, which has no page.
     */
    @SuppressLint("ClickableViewAccessibility")
    public static void setNavigationSettingsButton(View view) {
        if (!SHOW_NAVIGATION_BAR_SETTINGS_BUTTON || !YTIconType.SETTINGS.name().equals(lastYTNavigationEnumName)) {
            return;
        }

        try {
            GestureDetector detector = new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(@NonNull MotionEvent event) {
                    openSettings(view.getContext(), NAVIGATION_BAR_SETTINGS_BUTTON_TAP_OPENS_MORPHE);
                    return true;
                }

                @Override
                public void onLongPress(@NonNull MotionEvent event) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    openSettings(view.getContext(), !NAVIGATION_BAR_SETTINGS_BUTTON_TAP_OPENS_MORPHE);
                }
            });

            view.setOnTouchListener((v, event) -> {
                final int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.drawableHotspotChanged(event.getX(), event.getY());
                    v.setPressed(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                }
                detector.onTouchEvent(event);
                return true;
            });

            // Accessibility services click the button without touches, which would select it.
            view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public boolean performAccessibilityAction(@NonNull View host, int action, @Nullable Bundle args) {
                    if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                        openSettings(host.getContext(), NAVIGATION_BAR_SETTINGS_BUTTON_TAP_OPENS_MORPHE);
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "setNavigationSettingsButton failure", ex);
        }
    }

    /**
     * Injection point.
     * Called after the menu of the toolbar is inflated and the app set the icons of its items.
     */
    public static void setToolbarSettingsButton(Activity activity, Menu menu) {
        if (!SHOW_TOOLBAR_SETTINGS_BUTTON) return;

        toolbarActivityRef = new WeakReference<>(activity);
        try {
            MenuItem offlineSettingsItem = menu.findItem(ResourceUtils.getIdIdentifier(OFFLINE_SETTINGS_MENU_ITEM_ID_NAME));
            Drawable icon = offlineSettingsItem == null ? null : offlineSettingsItem.getIcon();
            if (icon != null) {
                toolbarSettingsIcon = icon.getConstantState();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to get the toolbar settings icon", ex);
        }
        setToolbarSettingsButton(menu);
    }

    /**
     * Injection point.
     * Called after a page added its buttons from the server, which can remove all the buttons of the menu first.
     */
    public static void setToolbarSettingsButton(Menu menu) {
        if (!SHOW_TOOLBAR_SETTINGS_BUTTON) return;

        try {
            Activity activity = toolbarActivityRef.get();
            if (activity == null) return;

            final int itemId = ResourceUtils.getIdIdentifier(SETTINGS_MENU_ITEM_ID_NAME);
            MenuItem item = menu.findItem(itemId);
            if (item == null) {
                item = menu.add(Menu.NONE, itemId, TOOLBAR_MENU_ITEM_ORDER, str(SETTINGS_LABEL_STRING_NAME));
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }

            // A view of its own, as menu items have no long press.
            if (item.getActionView() == null) {
                item.setActionView(createToolbarSettingsButton(activity, itemId));
            }
            item.setVisible(true);
        } catch (Exception ex) {
            Logger.printException(() -> "setToolbarSettingsButton failure", ex);
        }
    }

    /**
     * @return The Settings button of the toolbar, made from the layout of the search button so it looks the same.
     */
    private static View createToolbarSettingsButton(Activity activity, int id) {
        ImageView button = (ImageView) LayoutInflater.from(activity)
                .inflate(ResourceUtils.getLayoutIdentifier("search_button"), null);
        button.setId(id);
        Drawable.ConstantState icon = toolbarSettingsIcon;
        if (icon != null) {
            button.setImageDrawable(icon.newDrawable(activity.getResources()));
        } else {
            button.setImageResource(ResourceUtils.getDrawableIdentifier("yt_outline_gear_white_24"));
        }
        button.setContentDescription(str(SETTINGS_LABEL_STRING_NAME));
        button.setOnClickListener(v -> openSettings(v.getContext(), TOOLBAR_SETTINGS_BUTTON_TAP_OPENS_MORPHE));
        button.setOnLongClickListener(v -> {
            openSettings(v.getContext(), !TOOLBAR_SETTINGS_BUTTON_TAP_OPENS_MORPHE);
            return true;
        });
        return button;
    }

    private static void openSettings(Context context, boolean morpheSettings) {
        if (morpheSettings) {
            MusicActivityHook.openSettings(context);
            return;
        }

        try {
            Intent intent = new Intent();
            intent.setClassName(context, YOUTUBE_MUSIC_SETTINGS_ACTIVITY_CLASS);
            // The extras the app uses to open its settings.
            intent.putExtra(EXTRA_SHOW_FRAGMENT, YOUTUBE_MUSIC_SETTINGS_FRAGMENT_CLASS);
            intent.putExtra(EXTRA_NO_HEADERS, true);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to open YouTube Music settings", ex);
        }
    }

    @SuppressWarnings("ArraysAsListWithZeroOrOneArgument")
    private enum NavigationButton {
        HOME(
                Arrays.asList(
                        "TAB_HOME"
                ),
                Settings.HIDE_NAVIGATION_BAR_HOME_BUTTON.get()
        ),
        SAMPLES(
                Arrays.asList(
                        "TAB_SAMPLES"
                ),
                Settings.HIDE_NAVIGATION_BAR_SAMPLES_BUTTON.get()
        ),
        EXPLORE(
                Arrays.asList(
                        "TAB_EXPLORE"
                ),
                Settings.HIDE_NAVIGATION_BAR_EXPLORE_BUTTON.get()
        ),
        LIBRARY(
                Arrays.asList(
                        "LIBRARY_MUSIC",
                        "TAB_BOOKMARK" // YouTube Music 8.24+
                ),
                Settings.HIDE_NAVIGATION_BAR_LIBRARY_BUTTON.get()
        ),
        UPGRADE(
                Arrays.asList(
                        "TAB_MUSIC_PREMIUM"
                ),
                Settings.HIDE_NAVIGATION_BAR_UPGRADE_BUTTON.get()
        );

        private final List<String> ytEnumNames;
        private final boolean hidden;

        NavigationButton(List<String> ytEnumNames, boolean hidden) {
            this.ytEnumNames = ytEnumNames;
            this.hidden = hidden;
        }
    }

    //
    // Toolbar
    //

    /**
     * Injection point.
     * Called only for the activity button, as the same top bar button class also builds other
     * buttons, such as the search button on artist pages.
     */
    public static void hideActivityButton(View view) {
        if (view.getParent() instanceof ViewGroup viewGroup) {
            Utils.hideViewBy0dpUnderCondition(Settings.HIDE_ACTIVITY_BUTTON, viewGroup);
        }
    }

    /**
     * Injection point.
     * The server sends the top bar buttons without an id, so the Ask Music button is told apart by its icon.
     */
    public static void hideAskMusicButton(View view, Object renderer) {
        if (!Settings.HIDE_ASK_MUSIC_BUTTON.get() || !(renderer instanceof MessageLite message)) {
            return;
        }
        try {
            YTIconType icon = ButtonRenderer.parseFrom(message.toByteArray()).getIcon().getYtIconType();
            if (icon == YTIconType.SPARK && view.getParent() instanceof ViewGroup viewGroup) {
                Utils.hideViewBy0dpUnderCondition(true, viewGroup);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideAskMusicButton failure", ex);
        }
    }

    /**
     * Injection point.
     * Without a renderer the app hides the Ask Music buttons of the player, miniplayer and fullscreen.
     */
    @Nullable
    public static Object hidePlayerAskMusicButton(@Nullable Object renderer) {
        return Settings.HIDE_ASK_MUSIC_BUTTON.get() ? null : renderer;
    }

    /**
     * Injection point
     */
    public static int hideCastButton(int original) {
        return Settings.HIDE_CAST_BUTTON.get() ? View.GONE : original;
    }

    /**
     * Injection point
     */
    public static void hideCastButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_CAST_BUTTON, view);
    }

    /**
     * Injection point
     */
    public static boolean hideHistoryButton(boolean original) {
        return original && !Settings.HIDE_HISTORY_BUTTON.get();
    }

    /**
     * Injection point
     */
    public static void hideSearchButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_SEARCH_BUTTON, view);
    }


    /**
     * Injection point
     */
    public static void hideSoundSearchButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_SOUND_SEARCH_BUTTON, view);
    }

    /**
     * Injection point
     */
    public static void hideVoiceSearchButton(View view) {
        // The app changes the visibility of this button while typing, so it is hidden by size.
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_VOICE_SEARCH_BUTTON, view);
    }
}
