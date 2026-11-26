/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.view.MenuItem;
import android.widget.Toolbar;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * Icon buttons of the Morphe settings toolbar, such as search or screen info.
 */
public final class ToolbarButtons {

    private ToolbarButtons() {
    }

    /**
     * Adds an icon button to the toolbar.
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
