package app.morphe.extension.youtube.patches;

import android.view.MotionEvent;
import android.view.View;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class TapToSeekPatch {
    public static boolean tapToSeekEnabled() {
        return Settings.TAP_TO_SEEK.get();
    }

    /**
     * Injection point.
     *
     * @return If the touch event ends a gesture that the time bar ignores because it is disabled,
     *         so the scrub opened by the tap must be completed.
     */
    public static boolean shouldCompleteScrub(View timeBar, MotionEvent event) {
        if (!Settings.TAP_TO_SEEK.get() || timeBar.isEnabled()) {
            return false;
        }

        final int action = event.getActionMasked();
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
    }
}
