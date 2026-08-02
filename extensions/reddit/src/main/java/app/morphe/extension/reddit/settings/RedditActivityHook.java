/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings;

import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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

import java.util.Objects;

import app.morphe.extension.reddit.settings.preference.RedditPreferenceFragment;
import app.morphe.extension.reddit.ui.MorpheSettingsIconVectorDrawable;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings({"deprecation", "unused"})
public class RedditActivityHook {
    private static final Drawable MORPHE_ICON = MorpheSettingsIconVectorDrawable.getIcon();
    private static final String MORPHE_LABEL = "Morphe";

    /**
     * Injection point.
     * 2026.29.0 and older.
     */
    public static Drawable getSettingIcon() {
        return MORPHE_ICON;
    }

    /**
     * Injection point.
     * 2026.29.0 and older.
     */
    public static String getSettingLabel() {
        return MORPHE_LABEL;
    }

    /**
     * Injection point.
     */
    public static boolean hook(Activity activity) {
        Intent intent = activity.getIntent();
        if (MORPHE_LABEL.equals(intent.getStringExtra("com.reddit.extra.initial_url"))) {
            initialize(activity);
            return true;
        }

        return false;
    }

    /**
     * Injection point.
     */
    public static Intent initializeByIntent(Context context) {
        Intent intent = new Intent();
        intent.setClassName(context, "com.reddit.webembed.browser.WebBrowserActivity");
        intent.putExtra("com.reddit.extra.initial_url", MORPHE_LABEL);
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
     */
    public static boolean openMorpheSettings(Enum<?> e) {
        if (isAcknowledgment(e)) {
            Activity activity = Utils.getActivity();
            if (activity != null) {
                new MorpheSettingsDialog().show(activity.getFragmentManager(), "morphe_settings");
                return true;
            }
        }
        return false;
    }

    public static class MorpheSettingsDialog extends DialogFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setStyle(STYLE_NO_TITLE, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            Activity activity = getActivity();

            // Ensure the dialog window fills the screen and shows the status bar.
            Dialog dialog = getDialog();
            if (dialog != null) {
                Window window = dialog.getWindow();
                if (window != null) {
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    window.setStatusBarColor(Utils.getAppBackgroundColor());
                    window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                }
            }

            LinearLayout linearLayout = new LinearLayout(activity);
            linearLayout.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            linearLayout.setBackgroundColor(Utils.getAppBackgroundColor());
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
            toolbar.setBackgroundColor(Utils.getAppBackgroundColor());
            toolbar.setTitle(MORPHE_LABEL);
            toolbar.setTitleTextColor(Utils.getAppForegroundColor());
            toolbar.setElevation(Dim.dp2);

            // Set title to bold and reduce font size by 1 to mimic the original app settings look.
            toolbar.post(() -> {
                TextView titleTextView = Utils.getChildView(toolbar, false,
                        view -> view instanceof TextView);
                if (titleTextView != null) {
                    titleTextView.setTypeface(Typeface.DEFAULT_BOLD);
                    titleTextView.setTextSize(19);
                }
            });

            Drawable backIcon = Objects.requireNonNull(ResourceUtils.getDrawable("icon_arrow_back"));
            backIcon.setTint(Utils.getAppForegroundColor());
            toolbar.setNavigationIcon(backIcon);
            toolbar.setNavigationOnClickListener(v -> dismiss());
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
