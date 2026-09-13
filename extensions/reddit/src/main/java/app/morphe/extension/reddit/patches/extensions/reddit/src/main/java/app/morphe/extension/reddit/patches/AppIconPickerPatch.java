/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
@SuppressWarnings("deprecation")
public class AppIconPickerPatch {

    private static final String PACKAGE = "com.reddit.frontpage";

    /**
     * Verified from AndroidManifest.xml in Reddit 2026.32.0.
     * componentName = the raw android:name value from the manifest (used directly in ComponentName).
     * For StartActivity the full class name is used since it IS a real class, not an alias.
     */
    private static final String[][] ICONS = {
            // componentName (raw manifest name)    display label
            {"com.reddit.frontpage.StartActivity",  "Orange Red (Default)"},
            {"launcher.classic",                    "Classic"},
            {"launcher.alien_blue",                 "Alien Blue"},
            {"launcher.amazedoge",                  "Amaze Doge"},
            {"launcher.astronaut",                  "Astronaut"},
            {"launcher.brrr",                       "Brrr"},
            {"launcher.chibi",                      "Chibi"},
            {"launcher.doge",                       "Doge"},
            {"launcher.mechasnoo",                  "Mecha Snoo"},
            {"launcher.neon",                       "Neon"},
            {"launcher.pixels",                     "Pixels"},
            {"launcher.planet",                     "Planet"},
            {"launcher.pullover",                   "Pullover"},
            {"launcher.redditgifts",                "Reddit Gifts"},
            {"launcher.retro",                      "Retro"},
            {"launcher.rocket",                     "Rocket"},
            {"launcher.stocks",                     "Wall Street"},
            {"launcher.tothemoon",                  "To The Moon"},
            {"launcher.vaporwave",                  "Vaporwave"},
            {"launcher.vitruvian",                  "Vitruvian"},
            {"launcher.wallstreet",                 "Wall Street Bets"},
            {"launcher.worldcup2026",               "World Cup 2026"},
    };

    public static boolean isPatchIncluded() {
        return true;
    }

    public static void showIconPicker(android.content.Context context) {
        List<IconEntry> icons = buildIconList(context);
        String currentComponent = detectCurrentIcon(context, icons);
        showPickerDialog(context, icons, currentComponent);
    }

    // ── Build icon list ───────────────────────────────────────────────────────

    private static List<IconEntry> buildIconList(android.content.Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, Drawable> iconByComponentName = loadIconsFromPackageInfo(pm);

        List<IconEntry> list = new ArrayList<>();
        for (String[] entry : ICONS) {
            String componentName = entry[0];
            String label         = entry[1];
            Drawable icon        = iconByComponentName.get(componentName);
            list.add(new IconEntry(componentName, label, icon));
        }
        return list;
    }

    /**
     * Load icons for all activities including disabled aliases.
     * Returns a map of manifest component name → Drawable.
     * Keys match the raw android:name values in the manifest.
     */
    @SuppressWarnings("deprecation")
    private static Map<String, Drawable> loadIconsFromPackageInfo(PackageManager pm) {
        Map<String, Drawable> result = new HashMap<>();
        try {
            Resources appRes = pm.getResourcesForApplication(PACKAGE);
            PackageInfo pi = pm.getPackageInfo(
                    PACKAGE,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_DISABLED_COMPONENTS);

            if (pi.activities == null) return result;

            for (ActivityInfo ai : pi.activities) {
                if (ai.name == null) continue;

                int iconRes = ai.icon != 0 ? ai.icon : ai.applicationInfo.icon;
                if (iconRes == 0) continue;

                try {
                    Drawable d = appRes.getDrawable(iconRes, null);
                    // Store under both the full name and the short name so we hit
                    // regardless of which form is in our ICONS table.
                    result.put(ai.name, d);
                    if (ai.name.startsWith(PACKAGE + ".")) {
                        result.put(ai.name.substring(PACKAGE.length() + 1), d);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ── Detect current icon ───────────────────────────────────────────────────

    private static String detectCurrentIcon(android.content.Context context, List<IconEntry> icons) {
        PackageManager pm = context.getPackageManager();
        for (IconEntry icon : icons) {
            if (icon.componentName.equals("com.reddit.frontpage.StartActivity")) continue;
            try {
                int state = pm.getComponentEnabledSetting(
                        new ComponentName(PACKAGE, icon.componentName));
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return icon.componentName;
                }
            } catch (Exception ignored) {}
        }
        return "com.reddit.frontpage.StartActivity";
    }

    // ── Picker dialog ─────────────────────────────────────────────────────────

    private static void showPickerDialog(android.content.Context context,
                                         List<IconEntry> icons, String current) {
        IconAdapter adapter = new IconAdapter(context, icons, current);
        new AlertDialog.Builder(context)
                .setTitle("Choose App Icon")
                .setAdapter(adapter, (dialog, which) ->
                        confirmAndApply(context, icons, icons.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Confirm then apply ────────────────────────────────────────────────────

    private static void confirmAndApply(android.content.Context context,
                                        List<IconEntry> all, IconEntry selected) {
        new AlertDialog.Builder(context)
                .setTitle("Change App Icon")
                .setMessage("The app will restart to apply the \""
                        + selected.label + "\" icon.")
                .setPositiveButton("Apply", (d, w) -> applyIcon(context, all, selected))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Apply icon ────────────────────────────────────────────────────────────

    /**
     * Switch the active launcher alias.
     *
     * Component names are passed RAW (as in the manifest) to ComponentName —
     * e.g. "launcher.classic", NOT "com.reddit.frontpage.launcher.classic".
     * Passing the fully-qualified form causes:
     *   "Component class com.reddit.frontpage.launcher.classic does not exist in com.reddit.frontpage"
     * because the PM looks up component names by their stored manifest value.
     */
    private static void applyIcon(android.content.Context context,
                                  List<IconEntry> all, IconEntry selected) {
        try {
            PackageManager pm = context.getPackageManager();

            // Disable all non-default aliases.
            for (IconEntry icon : all) {
                if (icon.componentName.equals("com.reddit.frontpage.StartActivity")) continue;
                pm.setComponentEnabledSetting(
                        new ComponentName(PACKAGE, icon.componentName),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            }

            if (selected.componentName.equals("com.reddit.frontpage.StartActivity")) {
                pm.setComponentEnabledSetting(
                        new ComponentName(PACKAGE, "com.reddit.frontpage.StartActivity"),
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP);
            } else {
                pm.setComponentEnabledSetting(
                        new ComponentName(PACKAGE, selected.componentName),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            }

            // App will restart — no toast needed, the confirmation dialog already informed the user.

        } catch (SecurityException e) {
            new AlertDialog.Builder(context)
                    .setTitle("Permission Denied")
                    .setMessage("Could not change the app icon. Try reinstalling the patched app.")
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            new AlertDialog.Builder(context)
                    .setTitle("Error")
                    .setMessage("Failed to apply icon: " + e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    // ── List adapter ──────────────────────────────────────────────────────────

    private static class IconAdapter extends ArrayAdapter<IconEntry> {
        private final String currentComponentName;

        IconAdapter(android.content.Context ctx, List<IconEntry> items, String current) {
            super(ctx, 0, items);
            this.currentComponentName = current;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            IconEntry e = getItem(position);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(32, 20, 32, 20);

            ImageView img = new ImageView(getContext());
            int size = 112;
            img.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            if (e.icon != null) {
                img.setImageDrawable(e.icon);
            } else {
                try {
                    img.setImageDrawable(getContext().getPackageManager()
                            .getApplicationIcon(PACKAGE));
                } catch (Exception ignored) {}
            }
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(img);

            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            colParams.setMarginStart(28);
            col.setLayoutParams(colParams);

            TextView title = new TextView(getContext());
            title.setText(e.label);
            title.setTextSize(15f);
            col.addView(title);

            if (e.componentName.equals(currentComponentName)) {
                TextView badge = new TextView(getContext());
                badge.setText("✓ Active");
                badge.setTextSize(12f);
                col.addView(badge);
            }
            row.addView(col);
            return row;
        }
    }

    // ── Data model ────────────────────────────────────────────────────────────

    public static class IconEntry {
        final String componentName; // raw manifest android:name value
        final String label;
        final Drawable icon;

        IconEntry(String componentName, String label, Drawable icon) {
            this.componentName = componentName;
            this.label         = label;
            this.icon          = icon;
        }
    }
}