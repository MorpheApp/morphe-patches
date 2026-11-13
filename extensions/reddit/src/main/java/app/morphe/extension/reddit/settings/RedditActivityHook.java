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
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import java.lang.reflect.Field;
import java.util.Objects;

import app.morphe.extension.reddit.settings.preference.RedditPreferenceFragment;
import app.morphe.extension.reddit.settings.preference.RedditSettingsStyle;
import app.morphe.extension.reddit.ui.MorpheSettingsIconVectorDrawable;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
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
     * Key of the Compose settings row that is turned into the Morphe settings row.
     * 2026.30.0+
     */
    private static final String MORPHE_SETTING_ROW_KEY = "key_pref_privacy_policy";
    private static final String MORPHE_SETTINGS_DIALOG_TAG = "morphe_settings";
    private static final String MORPHE_SETTINGS_ICON_DRAWABLE = "morphe_settings_icon_reddit";

    /**
     * Draws the Morphe icon in the leading icon slot of the Compose settings row.
     */
    private static final Function2<Object, Object, Object> MORPHE_ICON_COMPOSABLE = new MorpheIconComposable();

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
     * 2026.25.0 - 2026.29.0, and the fallback if the Compose settings row is not found.
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
            final boolean modernStyle = RedditSettingsStyle.isModernStyle();
            // Icons use the text color of the rows, the title is white or black like the app titles.
            final int appForegroundColor = modernStyle
                    ? RedditSettingsStyle.getTextColor()
                    : ThemeUtils.getAppForegroundColor();
            final int titleColor = modernStyle
                    ? RedditSettingsStyle.getTitleColor()
                    : appForegroundColor;
            final int appBackgroundColor = modernStyle
                    ? RedditSettingsStyle.getBackgroundColor()
                    : ThemeUtils.getAppBackgroundColor();

            // Ensure the dialog window fills the screen and shows the status bar.
            Dialog dialog = getDialog();
            if (dialog != null) {
                Window window = dialog.getWindow();
                if (window != null) {
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    window.setStatusBarColor(appBackgroundColor);
                    window.setNavigationBarColor(appBackgroundColor);
                    // Dark system bar icons only on a light background.
                    window.getDecorView().setSystemUiVisibility(Utils.isDarkModeEnabled()
                            ? 0
                            : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                }
            }

            LinearLayout linearLayout = new LinearLayout(activity);
            linearLayout.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            linearLayout.setBackgroundColor(appBackgroundColor);
            linearLayout.setFitsSystemWindows(true);

            final int actionBarSize;
            TypedValue tv = new TypedValue();
            if (activity.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                actionBarSize = TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
            } else {
                actionBarSize = (int) (56 * getResources().getDisplayMetrics().density);
            }

            Toolbar toolbar = new Toolbar(activity);
            toolbar.setLayoutParams(new LinearLayout.LayoutParams(-1, actionBarSize));
            toolbar.setBackgroundColor(appBackgroundColor);
            toolbar.setTitle(MORPHE_LABEL);
            toolbar.setTitleTextColor(titleColor);
            boolean isDark = Utils.isDarkModeEnabled();
            toolbar.setElevation(modernStyle ? (isDark ? Dim.dp1 : Dim.dp6) : Dim.dp2);
            if (modernStyle && !isDark) {
                toolbar.setTranslationZ(Dim.dp2);
            }

            toolbar.post(() -> {
                TextView titleTextView = Utils.getChildView(toolbar, false,
                        view -> view instanceof TextView);
                if (titleTextView != null) {
                    if (modernStyle) {
                        RedditSettingsStyle.styleToolbarTitle(titleTextView);
                    } else {
                        titleTextView.setTextSize(18);
                    }
                }
            });

            Drawable backIcon = Objects.requireNonNull(ResourceUtils.getDrawable("icon_arrow_back"));
            backIcon.setTint(appForegroundColor);
            toolbar.setNavigationIcon(backIcon);
            toolbar.setNavigationOnClickListener(v -> closeSettings());
            linearLayout.addView(toolbar);

            final int fragmentId = View.generateViewId();
            FrameLayout fragmentContainer = new FrameLayout(activity);
            fragmentContainer.setId(fragmentId);
            linearLayout.addView(fragmentContainer, new LinearLayout.LayoutParams(-1, -1));

            getChildFragmentManager().beginTransaction()
                    .replace(fragmentId, new RedditPreferenceFragment())
                    .commit();

            return linearLayout;
        }
    }
}
