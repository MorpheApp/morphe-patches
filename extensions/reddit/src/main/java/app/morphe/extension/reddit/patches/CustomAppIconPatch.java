/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2937
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * Standalone app icon picker for the Reddit app.
 *
 * Uses the exact activity-alias component names extracted from
 * AndroidManifest.xml in Reddit 2026.32.0.
 *
 * IMPORTANT — component name format:
 *   The manifest registers aliases with short names (no leading dot, no package prefix):
 *     android:name="launcher.classic"
 *   Android stores these RAW in PackageManager. setComponentEnabledSetting must be called
 *   with ComponentName(PACKAGE, "launcher.classic"), NOT ComponentName(PACKAGE,
 *   "com.reddit.frontpage.launcher.classic"). The fully-qualified form is rejected with
 *   "Component class ... does not exist in com.reddit.frontpage".
 *
 * IMPORTANT — icon loading:
 *   All aliases are disabled (android:enabled="false") in the manifest, so
 *   getActivityIcon() throws NameNotFoundException. Instead, we query
 *   GET_ACTIVITIES | GET_DISABLED_COMPONENTS and load icons directly from the
 *   app's Resources via ActivityInfo.icon. The per-alias webp files are confirmed
 *   present in res/mipmap-xxhdpi-v4/ in base.apk.
 *
 * IMPORTANT — process restart:
 *   Android kills and restarts the app process when a launcher alias's enabled state
 *   changes. We show a confirmation dialog before applying so the user is not surprised.
 */
@SuppressWarnings({"deprecation", "unused"})
public class CustomAppIconPatch {

    /**
     * Verified from AndroidManifest.xml in Reddit 2026.32.0.
     * componentName = the raw android:name value from the manifest (used directly in ComponentName).
     * For StartActivity the full class name is used since it IS a real class, not an alias.
     */
    public enum RedditIcon {
        DEFAULT(str("morphe_app_icon_default"), "launcher.default",
                "com.reddit.frontpage.StartActivity"),
        CLASSIC("Classic", "launcher.classic"),
        ALIEN_BLUE("Alien Blue", "launcher.alien_blue"),
        AMAZEDOGE("Amaze Doge", "launcher.amazedoge"),
        ASTRONAUT("Astronaut", "launcher.astronaut"),
        BRRR("Brrr", "launcher.brrr"),
        CHIBI("Chibi", "launcher.chibi"),
        DOGE("Doge", "launcher.doge"),
        MECHASNOO("Mecha Snoo", "launcher.mechasnoo"),
        NEON("Neon", "launcher.neon"),
        PIXELS("Pixels", "launcher.pixels"),
        PLANET("Planet", "launcher.planet"),
        PULLOVER("Pullover", "launcher.pullover"),
        REDDITGIFTS("Reddit Gifts", "launcher.redditgifts"),
        RETRO("Retro", "launcher.retro"),
        ROCKET("Rocket", "launcher.rocket"),
        STOCKS("Wall Street", "launcher.stocks"),
        TOTHEMOON("To The Moon", "launcher.tothemoon"),
        VAPORWAVE("Vaporwave", "launcher.vaporwave"),
        VITRUVIAN("Vitruvian", "launcher.vitruvian"),
        WALLSTREET("Wall Street Bets", "launcher.wallstreet");

        // Additional Reddit limited time icons exist, but they should not be shown to the user.
        //
        // If the user selects a time limited icon and later upgrades to a version that no
        // longer has the icon, then the launcher will no longer show Reddit and clearing the
        // app data will not fix it. The only fix is to completely uninstall then reinstall.

        public final List<String> componentNames;
        public final String label;

        RedditIcon(String label, String... componentNames) {
            this.componentNames = List.of(componentNames);
            this.label = label;
        }

        private boolean matchesActivity(ActivityInfo aInfo) {
            if (aInfo.name == null) return false;
            for (String name : componentNames) {
                if (aInfo.name.equals(name) || aInfo.name.equals(PACKAGE + '.' + name)) {
                    return true;
                }
            }
            return false;
        }

        @Nullable
        @SuppressWarnings("deprecation")
        public Drawable getIcon(Context context) {
            try {
                PackageManager pm = context.getPackageManager();
                Resources appRes = pm.getResourcesForApplication(PACKAGE);
                PackageInfo pInfo = pm.getPackageInfo(
                        PACKAGE,
                        PackageManager.GET_ACTIVITIES | PackageManager.GET_DISABLED_COMPONENTS);

                if (pInfo.activities != null) {
                    for (ActivityInfo aInfo : pInfo.activities) {
                        if (matchesActivity(aInfo)) {
                            int iconRes = aInfo.icon != 0 ? aInfo.icon : aInfo.applicationInfo.icon;
                            if (iconRes != 0) {
                                return appRes.getDrawable(iconRes, null);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not load icon for " + componentNames, ex);
            }
            return null;
        }
    }

    private static final String PACKAGE = "com.reddit.frontpage";

    public static boolean isPatchIncluded() {
        return true;
    }

    public static Preference getIconPreference(Context context) {
        RedditIcon currentComponent = detectCurrentIcon(context);

        Preference preference = new Preference(context);
        preference.setTitle(str("morphe_app_icon_title"));
        preference.setSummary(currentComponent == null ? "Unknown" : currentComponent.label);
        preference.setOnPreferenceClickListener(pref -> {
            CustomAppIconPatch.showIconPicker(context);
            return true;
        });
        return preference;
    }

    public static void showIconPicker(Context context) {
        RedditIcon currentComponent = detectCurrentIcon(context);
        showPickerDialog(context, currentComponent);
    }

    @Nullable
    private static RedditIcon detectCurrentIcon(Context context) {
        PackageManager pm = context.getPackageManager();
        for (RedditIcon icon : RedditIcon.values()) {
            for (String name : icon.componentNames) {
                final int state = pm.getComponentEnabledSetting(
                        new ComponentName(PACKAGE, name));
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    return icon;
                }
            }
        }
        return null;
    }

    private static void showPickerDialog(Context context, @Nullable RedditIcon current) {
        IconAdapter adapter = new IconAdapter(context, current);
        new AlertDialog.Builder(context)
                .setTitle(str("morphe_app_icon_choose_title"))
                .setAdapter(adapter, (dialog, which)
                        -> confirmAndApply(context, RedditIcon.values()[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void confirmAndApply(Context context, RedditIcon selected) {
        new AlertDialog.Builder(context)
                .setTitle(str("morphe_settings_restart_title"))
                .setMessage(str("morphe_settings_restart_dialog_message"))
                .setPositiveButton(android.R.string.ok, (dialog, which)
                        -> applyIcon(context, selected))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Switch the active launcher alias.
     *
     * Component names are passed RAW (as in the manifest) to ComponentName —
     * e.g. "launcher.classic", NOT "com.reddit.frontpage.launcher.classic".
     * Passing the fully-qualified form causes:
     *   "Component class com.reddit.frontpage.launcher.classic does not exist in com.reddit.frontpage"
     * because the PM looks up component names by their stored manifest value.
     */
    private static void applyIcon(Context context, RedditIcon selected) {
        try {
            PackageManager pm = context.getPackageManager();

            // Disable non selected aliases.
            for (RedditIcon icon : RedditIcon.values()) {
                if (icon == selected) continue;
                for (String name : icon.componentNames) {
                    pm.setComponentEnabledSetting(
                            new ComponentName(PACKAGE, name),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                }
            }

            for (String name : selected.componentNames) {
                pm.setComponentEnabledSetting(
                        new ComponentName(PACKAGE, name),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        0);
            }
        } catch (SecurityException ex) {
            // Should never happen, no need to localize text.
            new AlertDialog.Builder(context)
                    .setTitle("Permission Denied")
                    .setMessage("Could not change the app icon. Try reinstalling the patched app: "
                            + ex.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ex) {
            new AlertDialog.Builder(context)
                    .setTitle("Error")
                    .setMessage("Failed to apply icon: " + ex.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private static class IconAdapter extends ArrayAdapter<RedditIcon> {
        @Nullable
        private final RedditIcon currentComponent;

        IconAdapter(Context ctx, @Nullable RedditIcon current) {
            super(ctx, 0, List.of(RedditIcon.values()));
            currentComponent = current;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            RedditIcon redditIcon = getItem(position);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(32, 20, 32, 20);

            ImageView img = new ImageView(getContext());
            final int size = 112;
            img.setLayoutParams(new LinearLayout.LayoutParams(size, size));

            Drawable iconDrawable;
            if (redditIcon == null || (iconDrawable = redditIcon.getIcon(getContext())) == null) {
                try {
                    iconDrawable = getContext().getPackageManager().getApplicationIcon(PACKAGE);
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not set icon", ex); // Should never happen.
                    iconDrawable = null;
                }
            }
            img.setImageDrawable(iconDrawable);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(img);

            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            colParams.setMarginStart(28);
            col.setLayoutParams(colParams);

            TextView title = new TextView(getContext());
            if (redditIcon != null) title.setText(redditIcon.label);
            title.setTextSize(15f);
            col.addView(title);

            if (redditIcon == currentComponent) {
                TextView badge = new TextView(getContext());
                badge.setText(str("morphe_app_icon_active"));
                badge.setTextSize(12f);
                col.addView(badge);
            }
            row.addView(col);
            return row;
        }
    }
}
