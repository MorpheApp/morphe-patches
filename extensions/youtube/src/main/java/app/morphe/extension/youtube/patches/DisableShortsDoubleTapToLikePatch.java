package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableShortsDoubleTapToLikePatch {

    /**
     * Injection point.
     */
    public static boolean overrideDoubleTapToLike(boolean originalValue) {
        if (Settings.DISABLE_SHORTS_DOUBLE_TAP_TO_LIKE.get()) {
            return false;
        }

        return originalValue;
    }
}