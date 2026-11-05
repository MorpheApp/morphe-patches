/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.ui;

import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.soundproof.domain.ArtistStatus;
import app.morphe.extension.shared.ResourceUtils;

/**
 * Icon and color of each status. The icon shape differs too, so the status reads without color.
 */
final class StatusAppearance {

    static final int HUMAN_COLOR = 0xFF6DD58C;
    static final int AI_COLOR = 0xFFFF8A80;
    private static final int UNKNOWN_COLOR = 0xFFC4C4C4;
    private static final int UNAVAILABLE_COLOR = 0xFF8A8A8A;

    private StatusAppearance() {
    }

    static int colorOf(ArtistStatus status) {
        return switch (status) {
            case HUMAN -> HUMAN_COLOR;
            case AI -> AI_COLOR;
            case UNKNOWN -> UNKNOWN_COLOR;
            case UNAVAILABLE -> UNAVAILABLE_COLOR;
        };
    }

    /**
     * @return The tinted icon, or null if the resource patch did not add it.
     */
    @Nullable
    static Drawable iconOf(ArtistStatus status) {
        Drawable icon = ResourceUtils.getDrawable(drawableNameOf(status));
        if (icon == null) {
            return null;
        }
        icon = icon.mutate();
        icon.setTint(colorOf(status));
        return icon;
    }

    private static String drawableNameOf(ArtistStatus status) {
        return switch (status) {
            case HUMAN -> "morphe_soundproof_shield_check";
            case AI -> "morphe_soundproof_shield_alert";
            case UNKNOWN -> "morphe_soundproof_shield_question";
            case UNAVAILABLE -> "morphe_soundproof_shield";
        };
    }
}
