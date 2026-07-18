package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.spoof.SpoofAppVersionPatch.isSpoofingToLessThan;
import static app.morphe.extension.youtube.patches.VersionCheckPatch.IS_20_31_OR_GREATER;

import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;

@SuppressWarnings("unused")
public class LegacyPlayerControlsPatch {

    public static final class RestoreOldPlayerButtonsAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return IS_20_31_OR_GREATER && !isSpoofingToLessThan("20.31.00");
        }
    }

    public static final boolean RESTORE_OLD_PLAYER_BUTTONS =
            Settings.RESTORE_OLD_PLAYER_BUTTONS.get() || !YouTubeActivityHook.useBoldIcons(true);

    /**
     * Injection point.
     */
    public static boolean useNullBottomGradient() {
        return RESTORE_OLD_PLAYER_BUTTONS;
    }

    /**
     * Injection point.
     */
    public static void hideBottomGradientScrim(ImageView bottomGradientScrim) {
        if (!RESTORE_OLD_PLAYER_BUTTONS) {
            return;
        }
        if (bottomGradientScrim != null) {
            Utils.runOnMainThread(() -> {
                bottomGradientScrim.setImageAlpha(0);
                bottomGradientScrim.setVisibility(View.GONE);
            });
        }
    }

    /**
     * Injection point.
     */
    public static boolean usePlayerBottomControlsExploderLayout(boolean original) {
        return !RESTORE_OLD_PLAYER_BUTTONS;
    }
}
