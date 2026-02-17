/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import android.view.View;
import android.widget.ImageView;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class ToolBarPatch {

    /**
     * Injection point.
     */
    public static void hookToolBar(Enum<?> iconEnum, ImageView imageView) {
        if (iconEnum != null && imageView.getParent() instanceof View view) {
            String enumName = iconEnum.name();
            Logger.printDebug(() -> "enum: " + enumName);
            hookToolBar(enumName, view);
        }
    }

    private static void hookToolBar(String enumString, View parentView) {
        // Code added during patching.
    }
}