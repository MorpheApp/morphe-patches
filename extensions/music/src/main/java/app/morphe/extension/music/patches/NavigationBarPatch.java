package app.morphe.extension.music.patches;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.GuideResponseOuterClass.ButtonRenderer;
import app.morphe.extension.shared.innertube.IconOuterClass.YTIconType;

@SuppressWarnings("unused")
public class NavigationBarPatch {
    private static String lastYTNavigationEnumName = "";

    public static void setLastAppNavigationEnum(@Nullable Enum<?> ytNavigationEnumName) {
        if (ytNavigationEnumName != null) {
            lastYTNavigationEnumName = ytNavigationEnumName.name();
        }
    }

    public static void hideNavigationLabel(TextView textview) {
        Utils.hideViewUnderCondition(Settings.HIDE_NAVIGATION_BAR_LABEL.get(), textview);
    }

    public static void hideNavigationButton(View view) {
        // Hide entire navigation bar.
        if (Settings.HIDE_NAVIGATION_BAR.get() && view.getParent() != null) {
            Utils.hideViewUnderCondition(true, (View) view.getParent());
            return;
        }

        // Hide navigation buttons based on their type.
        for (NavigationButton button : NavigationButton.values()) {
            if (button.ytEnumNames.contains(lastYTNavigationEnumName)) {
                Utils.hideViewUnderCondition(button.hidden, view);
                break;
            }
        }
    }

    @SuppressWarnings("ArraysAsListWithZeroOrOneArgument")
    private enum NavigationButton {
        HOME(
                Arrays.asList(
                        "TAB_HOME"
                ),
                Settings.HIDE_NAVIGATION_BAR_HOME_BUTTON.get()
        ),
        SAMPLES(
                Arrays.asList(
                        "TAB_SAMPLES"
                ),
                Settings.HIDE_NAVIGATION_BAR_SAMPLES_BUTTON.get()
        ),
        EXPLORE(
                Arrays.asList(
                        "TAB_EXPLORE"
                ),
                Settings.HIDE_NAVIGATION_BAR_EXPLORE_BUTTON.get()
        ),
        LIBRARY(
                Arrays.asList(
                        "LIBRARY_MUSIC",
                        "TAB_BOOKMARK" // YouTube Music 8.24+
                ),
                Settings.HIDE_NAVIGATION_BAR_LIBRARY_BUTTON.get()
        ),
        UPGRADE(
                Arrays.asList(
                        "TAB_MUSIC_PREMIUM"
                ),
                Settings.HIDE_NAVIGATION_BAR_UPGRADE_BUTTON.get()
        );

        private final List<String> ytEnumNames;
        private final boolean hidden;

        NavigationButton(List<String> ytEnumNames, boolean hidden) {
            this.ytEnumNames = ytEnumNames;
            this.hidden = hidden;
        }
    }

    //
    // Toolbar
    //

    /**
     * Injection point.
     * Called only for the activity button, as the same top bar button class also builds other
     * buttons, such as the search button on artist pages.
     */
    public static void hideActivityButton(View view) {
        if (view.getParent() instanceof ViewGroup viewGroup) {
            Utils.hideViewBy0dpUnderCondition(Settings.HIDE_ACTIVITY_BUTTON, viewGroup);
        }
    }

    /**
     * Injection point.
     * The server sends the top bar buttons without an id, so the Ask Music button is told apart by its icon.
     */
    public static void hideAskMusicButton(View view, Object renderer) {
        if (!Settings.HIDE_ASK_MUSIC_BUTTON.get() || !(renderer instanceof MessageLite message)) {
            return;
        }
        try {
            YTIconType icon = ButtonRenderer.parseFrom(message.toByteArray()).getIcon().getYtIconType();
            if (icon == YTIconType.SPARK && view.getParent() instanceof ViewGroup viewGroup) {
                Utils.hideViewBy0dpUnderCondition(true, viewGroup);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideAskMusicButton failure", ex);
        }
    }

    /**
     * Injection point.
     * Without a renderer the app hides the Ask Music buttons of the player, miniplayer and fullscreen.
     */
    @Nullable
    public static Object hidePlayerAskMusicButton(@Nullable Object renderer) {
        return Settings.HIDE_ASK_MUSIC_BUTTON.get() ? null : renderer;
    }

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
    public static void hideSearchButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_SEARCH_BUTTON, view);
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
    public static void hideVoiceSearchButton(View view) {
        // The app changes the visibility of this button while typing, so it is hidden by size.
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_VOICE_SEARCH_BUTTON, view);
    }
}
