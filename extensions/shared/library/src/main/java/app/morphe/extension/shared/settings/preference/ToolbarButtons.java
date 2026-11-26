/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.MenuItem;
import android.widget.Toolbar;

import java.lang.reflect.Method;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;

@SuppressWarnings({"deprecation", "unused"})
public final class ToolbarButtons {

    private ToolbarButtons() {}

    /**
     * Clicks a preference as if its row was tapped. A nested PreferenceScreen opens its screen.
     *
     * @param preference The preference to click.
     * @param rootScreen The main screen of the fragment that holds the preference.
     */
    @SuppressWarnings("all")
    public static void performClick(Preference preference, PreferenceScreen rootScreen) {
        try {
            Method m = Preference.class.getDeclaredMethod("performClick", PreferenceScreen.class);
            m.setAccessible(true);
            m.invoke(preference, rootScreen);
        } catch (Exception e) {
            Logger.printException(() -> "Failed to invoke performClick()", e);
        }
    }

    /**
     * Adds an icon button to the toolbar, such as search or screen info.
     *
     * @param iconName Drawable name, the "_bold" variant is used with bold icons.
     */
    @SuppressLint("AlwaysShowAction")
    public static MenuItem addToolbarButton(Toolbar toolbar, String iconName,
                                            CharSequence description, Runnable onClick) {
        MenuItem item = toolbar.getMenu().add(description);
        item.setIcon(getToolbarIconDrawable(iconName));
        item.setContentDescription(description);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        item.setOnMenuItemClickListener(menuItem -> {
            onClick.run();
            return true;
        });
        return item;
    }

    /**
     * @return The icon, bold or not depending on the Morphe UI setting, and colored like
     *         everything else Morphe draws. The drawable follows the text color of the platform,
     *         which is not the foreground color of the app.
     */
    public static Drawable getToolbarIconDrawable(String iconName) {
        Drawable icon = ResourceUtils.getDrawableOrThrow(
                Utils.appIsUsingBoldIcons() ? iconName + "_bold" : iconName);

        // Mutate, otherwise every user of the same drawable is colored as well.
        Drawable mutated = icon.mutate();
        mutated.setTint(ThemeUtils.getAppForegroundColor());
        return mutated;
    }
}
