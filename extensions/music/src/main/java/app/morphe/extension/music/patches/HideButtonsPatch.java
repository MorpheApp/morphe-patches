package app.morphe.extension.music.patches;

import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
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
    public static void hideNotificationButton(View view, Object notificationKey) {
        // The same top bar button class also builds other buttons, such as the search button on
        // artist pages. Only the notification button is created with a notification key.
        if (!hasNotificationKey(notificationKey)) {
            return;
        }

        if (view.getParent() instanceof ViewGroup viewGroup) {
            Utils.hideViewBy0dpUnderCondition(Settings.HIDE_NOTIFICATION_BUTTON, viewGroup);
        }
    }

    /**
     * The key is a desugared {@code j$.util.Optional}, so it is read by reflection.
     */
    private static boolean hasNotificationKey(Object notificationKey) {
        try {
            return notificationKey != null
                    && (boolean) notificationKey.getClass().getMethod("isPresent").invoke(notificationKey);
        } catch (Exception ex) {
            Logger.printException(() -> "hasNotificationKey failure", ex);
            return false;
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
