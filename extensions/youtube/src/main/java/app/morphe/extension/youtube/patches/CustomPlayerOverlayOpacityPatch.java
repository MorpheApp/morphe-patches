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

import android.widget.ImageView;

import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class CustomPlayerOverlayOpacityPatch {

    private static final int PLAYER_OVERLAY_OPACITY_LEVEL =
            (SeekBarPreference.clampToRange(Settings.PLAYER_OVERLAY_OPACITY) * 255) / 100;

    /**
     * Injection point.
     */
    public static void changeOpacity(ImageView imageView) {
        imageView.setImageAlpha(PLAYER_OVERLAY_OPACITY_LEVEL);
    }
}
