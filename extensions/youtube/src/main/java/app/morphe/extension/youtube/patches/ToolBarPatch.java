/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class ToolBarPatch {
    private static final ThreadLocal<String> pendingIconEnum = new ThreadLocal<>();

    /**
     * Injection point. Called immediately after YouTube resolves the menu icon enum.
     */
    public static void setToolbarIconEnum(@Nullable Enum<?> iconEnum) {
        pendingIconEnum.set(iconEnum == null ? null : iconEnum.name());
    }

    /**
     * Injection point. Called immediately after YouTube loads the toolbar ImageView.
     */
    public static void setToolbarImageView(ImageView imageView) {
        String enumName = pendingIconEnum.get();
        pendingIconEnum.remove();
        if (enumName != null && imageView.getParent() instanceof View parentView) {
            Logger.printDebug(() -> "enum: " + enumName);
            hookToolBar(enumName, parentView, imageView);
        }
    }

    private static void hookToolBar(String enumString, View parentView, ImageView imageView) {
        // Code added during patching.
    }
}
