package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.Utils.hideViewBy0dpUnderCondition;

import android.view.View;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class HideFilterBarPatch {

    /**
     * Injection point
     */
    public static void hideFilterBar(View view) {
        hideViewBy0dpUnderCondition(Settings.HIDE_FILTER_BAR, view);
    }
}
