/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class MediaViewerFadePatch {

    /**
     * Reddit's default fade strength.
     */
    private static final float DEFAULT_STRENGTH = 1f;

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    private static boolean isDefaultStrength() {
        return Settings.MEDIA_VIEWER_FADE.get() >= DEFAULT_STRENGTH;
    }

    private static float getStrength() {
        return Math.max(0f, Math.min(Settings.MEDIA_VIEWER_FADE.get(), DEFAULT_STRENGTH));
    }

    /**
     * Injection point.
     * <p>
     * Scales one color stop alpha of the fade gradient.
     */
    public static float scaleFadeAlpha(float alpha) {
        return alpha * getStrength();
    }

    /**
     * Injection point.
     * <p>
     * Reddit can replace the fade with a darker variant. Only allow that when
     * the fade is left at Reddit's default strength.
     */
    public static boolean useAlternateFade() {
        return isDefaultStrength();
    }

    /**
     * Injection point.
     * <p>
     * Newer versions choose between several fade styles. Only the legacy gradient can be
     * lowered, so use it when the fade is changed from Reddit's default.
     */
    public static boolean useLegacyFadeStyle() {
        return !isDefaultStrength();
    }
}
