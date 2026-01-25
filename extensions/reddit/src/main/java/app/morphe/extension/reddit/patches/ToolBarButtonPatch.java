package app.morphe.extension.reddit.patches;

import android.view.View;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public class ToolBarButtonPatch {

    public static boolean patchEnabled;

    public static void setPatchEnabled() {
        patchEnabled = true;
    }

    public static void hideToolBarButton(View view) {
        if (!Settings.HIDE_TOOLBAR_BUTTON.get())
            return;

        view.setVisibility(View.GONE);
    }
}
