package app.morphe.extension.music.patches;

import android.view.View;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class HideFilterBarPatch {

    /**
     * Injection point
     */
    public static void hideFilterBar(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_FILTER_BAR, view);
    }
}
