package app.morphe.extension.youtube.patches;

import android.view.View;
import android.widget.ImageView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;

@SuppressWarnings("unused")
public class LegacyPlayerControlsPatch {

    public static final class RestoreOldPlayerButtonsAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return VersionCheckPatch.IS_20_31_OR_GREATER && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");
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
