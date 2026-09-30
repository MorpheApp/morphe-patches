package app.morphe.extension.music.patches;

import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class HideButtonsPatch {

    /**
     * Injection point
     */
    public static int hideCastButton(int original) {
        return Settings.HIDE_CAST_BUTTON.get() ? View.GONE : original;
    }

    /**
     * Injection point
     */
    public static void hideCastButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_CAST_BUTTON, view);
    }

    /**
     * Injection point
     */
    public static boolean hideHistoryButton(boolean original) {
        return original && !Settings.HIDE_HISTORY_BUTTON.get();
    }

    /**
     * Injection point
     */
    public static void hideNotificationButton(View view) {
        if (view.getParent() instanceof ViewGroup viewGroup) {
            Utils.hideViewBy0dpUnderCondition(Settings.HIDE_NOTIFICATION_BUTTON, viewGroup);
        }
    }

    /**
     * Injection point
     */
    public static void hideSearchButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_SEARCH_BUTTON, view);
    }

    /**
     * Injection point
     */
    public static void hideVoiceSearchButton(View view) {
        // The app changes the visibility of this button while typing, so it is hidden by size.
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_VOICE_SEARCH_BUTTON, view);
    }

    /**
     * Injection point
     */
    public static void hideSoundSearchButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_SOUND_SEARCH_BUTTON, view);
    }

    /**
     * Injection point
     */
    public static void hideLibraryNewButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_LIBRARY_NEW_BUTTON, view);
    }
}
