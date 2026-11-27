/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toolbar;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import app.morphe.extension.reddit.settings.preference.RedditPreferenceFragment;
import app.morphe.extension.reddit.settings.preference.RedditSettingsStyle;
import app.morphe.extension.reddit.settings.search.RedditSearchViewController;
import app.morphe.extension.reddit.ui.MorpheSettingsIconVectorDrawable;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

@SuppressWarnings({"deprecation", "unused"})
public class RedditActivityHook {
    private static final Drawable MORPHE_ICON = MorpheSettingsIconVectorDrawable.getIcon();
    private static final String MORPHE_LABEL = "Morphe";
    private static final String EXTRA_INITIAL_URL = "com.reddit.extra.initial_url";
    private static final String PRIVACY_POLICY_URI = Objects.requireNonNull(
            ResourceUtils.getString("privacy_policy_uri"));

    /**
     * Keys of the Compose settings rows. The privacy policy row is copied as the Morphe settings row.
     * 2026.30.0+
     */
    private static final String PRIVACY_POLICY_ROW_KEY = "key_pref_privacy_policy";
    private static final String MORPHE_SETTING_ROW_KEY = "key_pref_morphe";
    private static final String MORPHE_SETTINGS_DIALOG_TAG = "morphe_settings";
    private static final String MORPHE_SETTINGS_ICON_DRAWABLE = "morphe_settings_icon_reddit";

    /**
     * Draws the Morphe icon in the leading icon slot of the Compose settings row.
     */
    private static final Function2<Object, Object, Object> MORPHE_ICON_COMPOSABLE = new MorpheIconComposable();

    /**
     * Compose settings row of the app. The interface and its methods are added during patching.
     * 2026.30.0+
     */
    public interface ComposeSettingRow {
        String patch_getKey();

        /**
         * @return A copy of this row with another key.
         */
        Object patch_copyWithKey(String key);
    }

    /**
     * Injection point.
     * 2026.29.0 and older.
     */
    public static Drawable getSettingIcon() {
        return MORPHE_ICON;
    }

    /**
     * Injection point.
     */
    public static String getSettingLabel() {
        return MORPHE_LABEL;
    }

    /**
     * Injection point.
     */
    public static Function0<Object> getGoogleSignInFunction() {
        // Must use anonymous declaration.
        // Using a lambda creates a different bytecode that doesn't work.
        // noinspection Convert2Lambda
        return new Function0<>() {
            @Override
            public Object invoke() {
                Utils.showToastLong(str("morphe_google_signin_not_available_toast"));
                return null;
            }
        };
    }

    /**
     * Injection point.
     */
    public static boolean hook(Activity activity) {
        if (isMorpheSettingsActivity(activity)) {
            initialize(activity);
            return true;
        }

        return false;
    }

    private static boolean isMorpheSettingsActivity(Activity activity) {
        return MORPHE_LABEL.equals(activity.getIntent().getStringExtra(EXTRA_INITIAL_URL));
    }

    /**
     * Injection point.
     * 2026.29.0 and older.
     * <p>
     * Overrides {@link Activity#finish()} of the settings activity.
     *
     * @return If the restart dialog is shown, and the original finish method must not run yet.
     */
    public static boolean handleFinish(Activity activity) {
        if (!isMorpheSettingsActivity(activity)) {
            return false;
        }

        return AbstractPreferenceFragment.showRestartDialogIfNeeded(activity);
    }

    /**
     * Injection point.
     */
    public static Intent initializeByIntent(Context context) {
        Intent intent = new Intent();
        intent.setClassName(context, "com.reddit.webembed.browser.WebBrowserActivity");
        intent.putExtra(EXTRA_INITIAL_URL, MORPHE_LABEL);
        return intent;
    }

    /**
     * Injection point.
     */
    public static void initialize(Activity activity) {
        int fragmentId = View.generateViewId();
        FrameLayout fragment = new FrameLayout(activity);
        fragment.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        fragment.setId(fragmentId);

        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setFitsSystemWindows(true);
        linearLayout.setTransitionGroup(true);
        linearLayout.addView(fragment);
        linearLayout.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        activity.setContentView(linearLayout);

        activity.getFragmentManager()
                .beginTransaction()
                .replace(fragmentId, new RedditPreferenceFragment())
                .commit();
    }

    /**
     * Injection point.
     */
    public static boolean isAcknowledgment(Enum<?> e) {
        return e != null && "ACKNOWLEDGMENTS".equals(e.name());
    }

    /**
     * Injection point.
     * 2026.25.0 - 2026.29.0
     */
    public static boolean openMorpheSettings(Activity activity, Uri uri) {
        if (uri != null && PRIVACY_POLICY_URI.equals(uri.toString())) {
            showSettingsDialog(activity);
            return true;
        }
        return false;
    }

    /**
     * Injection point.
     * 2026.30.0+
     *
     * @param rows All Compose settings rows of the app.
     * @return The rows and the Morphe settings row, a copy of the privacy policy row.
     */
    public static Set<Object> addMorpheSettingRow(Set<Object> rows) {
        try {
            for (Object row : rows) {
                if (row instanceof ComposeSettingRow settingRow
                        && PRIVACY_POLICY_ROW_KEY.equals(settingRow.patch_getKey())) {
                    Set<Object> newRows = new LinkedHashSet<>(rows);
                    newRows.add(settingRow.patch_copyWithKey(MORPHE_SETTING_ROW_KEY));
                    return newRows;
                }
            }
            Logger.printException(() -> "Privacy policy settings row not found");
        } catch (Exception ex) {
            Logger.printException(() -> "addMorpheSettingRow failure", ex);
        }
        return rows;
    }

    /**
     * Modified during patching.
     *
     * @return If the privacy policy link of the app settings opens the Morphe settings,
     *         so the Morphe settings must show the privacy policy link instead.
     */
    public static boolean isPrivacyPolicyReplaced() {
        return false;
    }

    /**
     * Injection point.
     * 2026.30.0+
     *
     * @param key The key of a Compose settings row.
     * @return If the row is the Morphe settings row.
     */
    public static boolean isMorpheSettingRow(String key) {
        return MORPHE_SETTING_ROW_KEY.equals(key);
    }

    /**
     * Injection point.
     * 2026.30.0+
     *
     * @return The leading icon of the Morphe settings row.
     */
    public static Function2<Object, Object, Object> getSettingIconComposable() {
        return MORPHE_ICON_COMPOSABLE;
    }

    /**
     * Injection point.
     * 2026.30.0+
     *
     * @param original The original click handler of the row, which captures the Compose context.
     * @return A click handler that opens the Morphe settings.
     */
    public static Function0<Object> getSettingOnClick(Function0<?> original) {
        final Activity rowActivity = findActivity(original);

        // Must use anonymous declaration.
        // noinspection Convert2Lambda
        return new Function0<>() {
            @Override
            public Object invoke() {
                Activity activity = rowActivity;
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    activity = Utils.getActivity();
                }
                showSettingsDialog(activity);
                return null;
            }
        };
    }

    /**
     * @return The activity of a Context captured by a Kotlin lambda, or null if none is found.
     */
    private static Activity findActivity(Object lambda) {
        if (lambda == null) return null;
        try {
            for (Field field : lambda.getClass().getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Activity activity = unwrapActivity(field.get(lambda));
                if (activity != null) {
                    return activity;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "findActivity failure", ex);
        }
        return null;
    }

    private static Activity unwrapActivity(Object object) {
        Object context = object;
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }

    /**
     * Opens the Morphe settings from app code that has no activity.
     */
    public static void openSettings() {
        Activity activity = Utils.getActivity();
        if (RedditSettingsStyle.isModernStyle()) {
            showSettingsDialog(activity);
        } else if (activity != null) { // 2026.24.0 and older.
            activity.startActivity(initializeByIntent(activity));
        }
    }

    /**
     * Shows the Morphe settings on top of the current screen.
     */
    public static void showSettingsDialog(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Logger.printDebug(() -> "No activity to show the settings");
                return;
            }

            FragmentManager fragmentManager = activity.getFragmentManager();
            if (fragmentManager.isStateSaved()) {
                Logger.printDebug(() -> "Activity state is saved, cannot show the settings");
                return;
            }
            // Ignore fast double taps.
            if (fragmentManager.findFragmentByTag(MORPHE_SETTINGS_DIALOG_TAG) != null) {
                return;
            }

            new MorpheSettingsDialog().show(fragmentManager, MORPHE_SETTINGS_DIALOG_TAG);
        } catch (Exception ex) {
            Logger.printException(() -> "showSettingsDialog failure", ex);
        }
    }

    /**
     * Reddit can use a theme that differs from the device night mode.
     * The configuration of the activity has the theme the app is showing.
     */
    private static void updateDarkModeStatus(Activity activity) {
        if (activity == null) return;
        final int nightMode = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (nightMode != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            Utils.setIsDarkModeEnabled(nightMode == Configuration.UI_MODE_NIGHT_YES);
        }
    }

    /**
     * Placeholder that is replaced during patching with a call to the icon composable of the app.
     * Not private, so the nested class calls it directly and not through a synthetic accessor.
     * 2026.30.0+
     *
     * @param composer Compose composer.
     * @param resourceId Vector drawable resource of the icon.
     */
    static void drawSettingIcon(Object composer, int resourceId) {
        // Replaced during patching.
    }

    static final class MorpheIconComposable implements Function2<Object, Object, Object> {
        private int iconResourceId = -1;

        @Override
        public Object invoke(Object composer, Object changed) {
            if (iconResourceId < 0) {
                iconResourceId = ResourceUtils.getDrawableIdentifier(MORPHE_SETTINGS_ICON_DRAWABLE);
            }
            if (iconResourceId != 0) {
                drawSettingIcon(composer, iconResourceId);
            }
            return null;
        }
    }

    public static class MorpheSettingsDialog extends DialogFragment {
        private static final int LAYOUT_MORPHE_SETTINGS_SEARCH_BAR = ResourceUtils.getIdentifierOrThrow(
                ResourceType.LAYOUT, "morphe_settings_search_bar");

        @Nullable
        private RedditSearchViewController searchViewController;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // Also runs when the dialog is restored after the app theme changes.
            updateDarkModeStatus(getActivity());
            if (RedditSettingsStyle.isModernStyle()) {
                RedditSettingsStyle.loadThemeColors(getActivity());
            }
            // The preference widgets use the text colors of the theme.
            setStyle(STYLE_NO_TITLE, Utils.isDarkModeEnabled()
                    ? android.R.style.Theme_DeviceDefault_NoActionBar
                    : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new Dialog(getActivity(), getTheme()) {
                @Override
                @SuppressLint("GestureBackNavigation")
                public void onBackPressed() {
                    if (searchViewController != null && searchViewController.handleBackPress()) {
                        return;
                    }
                    closeSettings();
                }
            };
        }

        /**
         * Closes the settings, after first showing the restart dialog if a changed setting requires it.
         */
        private void closeSettings() {
            if (!AbstractPreferenceFragment.showRestartDialogIfNeeded(getActivity())) {
                dismiss();
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            Activity activity = getActivity();
            final int appBackgroundColor = RedditSettingsStyle.getSettingsBackgroundColor();

            // Ensure the dialog window fills the screen and shows the status bar.
            Window window = Objects.requireNonNull(getDialog().getWindow());
            RedditSettingsStyle.styleWindow(window);

            LinearLayout linearLayout = new LinearLayout(activity);
            linearLayout.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            linearLayout.setBackgroundColor(appBackgroundColor);
            linearLayout.setFitsSystemWindows(true);

            final int actionBarSize = RedditSettingsStyle.getActionBarSize(activity);

            // Holds the toolbar and the search bar that is shown over it.
            FrameLayout toolbarParent = new FrameLayout(activity);
            toolbarParent.setLayoutParams(new LinearLayout.LayoutParams(-1, actionBarSize));
            toolbarParent.setBackgroundColor(appBackgroundColor);
            RedditSettingsStyle.applyToolbarElevation(toolbarParent);

            Toolbar toolbar = RedditSettingsStyle.createToolbar(activity, MORPHE_LABEL, this::closeSettings);
            toolbarParent.addView(toolbar);

            FrameLayout searchContainer = (FrameLayout) inflater.inflate(
                    LAYOUT_MORPHE_SETTINGS_SEARCH_BAR, toolbarParent, false);
            toolbarParent.addView(searchContainer);
            linearLayout.addView(toolbarParent);

            final int fragmentId = View.generateViewId();
            FrameLayout fragmentContainer = new FrameLayout(activity);
            fragmentContainer.setId(fragmentId);
            linearLayout.addView(fragmentContainer, new LinearLayout.LayoutParams(-1, -1));

            RedditPreferenceFragment fragment = new RedditPreferenceFragment();
            getChildFragmentManager().beginTransaction()
                    .replace(fragmentId, fragment)
                    .commit();

            searchViewController = new RedditSearchViewController(activity, window, toolbar,
                    searchContainer, fragmentContainer, fragment, this::closeSettings);
            fragment.setSearchViewController(searchViewController);

            return linearLayout;
        }

        @Override
        public void onDestroyView() {
            if (searchViewController != null) {
                if (searchViewController.isSearchActive()) {
                    searchViewController.closeSearch();
                }
                searchViewController = null;
            }
            super.onDestroyView();
        }
    }
}
