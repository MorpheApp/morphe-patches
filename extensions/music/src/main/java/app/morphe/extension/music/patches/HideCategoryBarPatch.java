package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.Utils.hideViewBy0dpUnderCondition;

import android.view.View;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class HideCategoryBarPatch {

    /**
     * Injection point
     */
    public static void hideCategoryBar(View view) {
        hideViewBy0dpUnderCondition(Settings.HIDE_CATEGORY_BAR, view);
    }
}
