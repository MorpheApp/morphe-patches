package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.Utils.hideViewUnderCondition;

import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class HidePlayerFlyoutMenuPatch {

    private HidePlayerFlyoutMenuPatch() {
    }

    /**
     * Injection point.
     */
    public static void hidePlayerFlyoutMenuCaptionsFooter(View view) {
        hideViewUnderCondition(Settings.HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER.get(), view);
    }

    /**
     * Injection point.
     */
    public static void hidePlayerFlyoutMenuQualityFooter(View view) {
        hideViewUnderCondition(Settings.HIDE_PLAYER_FLYOUT_QUALITY_FOOTER.get(), view);
    }

    /**
     * Injection point.
     * Must return a View to avoid layout crashes.
     */
    @Nullable
    public static View hidePlayerFlyoutMenuQualityHeader(View view) {
        if (!Settings.HIDE_PLAYER_FLYOUT_QUALITY_HEADER.get()) {
            return view;
        }

        return new View(view.getContext());
    }
}