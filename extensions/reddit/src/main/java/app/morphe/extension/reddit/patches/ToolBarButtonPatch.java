package app.morphe.extension.reddit.patches;

import android.view.View;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public class ToolBarButtonPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static void hideToolBarButton(View view) {
        if (!Settings.HIDE_TOOLBAR_BUTTON.get())
            return;

        view.setVisibility(View.GONE);
    }
}
