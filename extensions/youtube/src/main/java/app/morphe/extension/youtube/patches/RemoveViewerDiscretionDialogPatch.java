package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class RemoveViewerDiscretionDialogPatch {

    /**
     * Injection point.
     */
    public static boolean hideViewDiscretionDialog(boolean originalValue) {
        if (Settings.REMOVE_VIEWER_DISCRETION_DIALOG.get()) {
            return true;
        }
        return originalValue;
    }
}
