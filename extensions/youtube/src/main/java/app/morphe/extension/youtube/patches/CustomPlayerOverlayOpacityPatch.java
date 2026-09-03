/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2764
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class CustomPlayerOverlayOpacityPatch {

    private static final int PLAYER_OVERLAY_OPACITY_LEVEL =
            (SeekBarPreference.clampToRange(Settings.PLAYER_OVERLAY_OPACITY) * 255) / 100;

    /**
     * Injection point.
     */
    public static void changeOpacity(ImageView scrimOverlay) {
        scrimOverlay.setImageAlpha(PLAYER_OVERLAY_OPACITY_LEVEL);

        if (PLAYER_OVERLAY_OPACITY_LEVEL == 255) return;

        // The new player layout leaves the full screen scrim unused and dims with a top and
        // bottom gradient instead, so the same opacity is applied to those siblings.
        if (scrimOverlay.getParent() instanceof View parent) {
            changeGradientOpacity(parent, "top_gradient_scrim_overlay");
            changeGradientOpacity(parent, "bottom_gradient_scrim_overlay");
        }
    }

    private static void changeGradientOpacity(View parent, String resourceName) {
        final int id = ResourceUtils.getIdentifier(ResourceType.ID, resourceName);
        if (id == 0) return;

        View gradient = parent.findViewById(id);
        if (gradient == null) {
            Logger.printDebug(() -> "Could not find player scrim: R.id." + resourceName);
            return;
        }

        // The gradients are set as a background rather than an image, so setImageAlpha
        // does nothing for them.
        Drawable background = gradient.getBackground();
        if (background != null) {
            // Mutate so the alpha does not leak into the same drawable used elsewhere.
            background.mutate().setAlpha(PLAYER_OVERLAY_OPACITY_LEVEL);
        }
    }
}
