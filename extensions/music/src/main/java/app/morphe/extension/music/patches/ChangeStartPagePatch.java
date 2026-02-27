package app.morphe.extension.music.patches;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.utils.ExtendedUtils;

@SuppressWarnings("unused")
public final class ChangeStartPagePatch {

    public enum StartPage {
        /**
         * Unmodified type, same as un-patched.
         */
        DEFAULT("", null),

        /**
         * Browse IDs.
         */
        CHARTS("FEmusic_charts", TRUE),
        EXPLORE("FEmusic_explore", TRUE),
        HISTORY("FEmusic_history", TRUE),
        LIBRARY("FEmusic_library_landing", TRUE),
        PODCASTS("FEmusic_non_music_audio", TRUE),
        SAMPLES("FEmusic_immersive", TRUE),
        SUBSCRIPTIONS("FEmusic_library_corpus_artists", TRUE),

        /**
         * Playlist IDs.
         */
        EPISODES_FOR_LATER("VLSE", TRUE),
        LIKED_MUSIC("VLLM", TRUE),

        /**
         * Intent Action.
         */
        SEARCH("", FALSE);

        @NonNull
        final String id;

        @Nullable
        final Boolean isBrowseId;

        StartPage(@NonNull String id, @Nullable Boolean isBrowseId) {
            this.id = id;
            this.isBrowseId = isBrowseId;
        }

        private boolean isBrowseId() {
            return TRUE.equals(isBrowseId);
        }

        private boolean isIntentAction() {
            return FALSE.equals(isBrowseId);
        }
    }

    private static final String ACTION_MAIN = "android.intent.action.MAIN";

    private static final StartPage START_PAGE = Settings.CHANGE_START_PAGE.get();

    private static boolean appLaunched = false;

    public static String overrideBrowseId(@NonNull String original) {
        if (!START_PAGE.isBrowseId()) {
            return original;
        }

        if (!original.equals("FEmusic_home")) {
            return original;
        }

        if (appLaunched) {
            Logger.printDebug(() -> "Ignore override browseId as the app already launched");
            return original;
        }
        appLaunched = true;

        String overrideBrowseId = START_PAGE.id;
        if (overrideBrowseId.isEmpty()) {
            return original;
        }

        Logger.printDebug(() -> "Changing browseId to: " + START_PAGE.name());
        return overrideBrowseId;
    }

    public static void overrideIntentAction(@NonNull Intent intent) {
        if (!START_PAGE.isIntentAction()) {
            return;
        }

        if (!ACTION_MAIN.equals(intent.getAction())) {
            Logger.printDebug(() -> "Ignore override intent action as the current activity is not the entry point");
            return;
        }

        if (appLaunched) {
            Logger.printDebug(() -> "Ignore override intent action as the app already launched");
            return;
        }
        appLaunched = true;

        if (START_PAGE == StartPage.SEARCH) {
            Activity mActivity = Utils.getActivity();
            if (mActivity != null) {
                Logger.printDebug(() -> "Changing intent action to: " + START_PAGE.name());
                ExtendedUtils.setSearchIntent(mActivity, intent);
            }
        }
    }
}