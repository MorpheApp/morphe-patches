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
        DEFAULT("", null),
        CHARTS("FEmusic_charts", TRUE),
        EXPLORE("FEmusic_explore", TRUE),
        HISTORY("FEmusic_history", TRUE),
        LIBRARY("FEmusic_library_landing", TRUE),
        PODCASTS("FEmusic_non_music_audio", TRUE),
        SAMPLES("FEmusic_immersive", TRUE),
        SUBSCRIPTIONS("FEmusic_library_corpus_artists", TRUE),
        EPISODES_FOR_LATER("VLSE", TRUE),
        LIKED_MUSIC("VLLM", TRUE),
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

    public static String overrideBrowseId(@Nullable String original) {
        StartPage startPage = Settings.CHANGE_START_PAGE.get();

        if (!startPage.isBrowseId()) {
            return original;
        }

        if (!"FEmusic_home".equals(original)) {
            return original;
        }

        String overrideBrowseId = startPage.id;
        if (overrideBrowseId.isEmpty()) {
            return original;
        }

        Logger.printDebug(() -> "Changing browseId to: " + startPage.name());
        return overrideBrowseId;
    }

    public static void overrideIntentAction(@NonNull Intent intent) {
        StartPage startPage = Settings.CHANGE_START_PAGE.get();

        if (!startPage.isIntentAction()) {
            return;
        }

        if (!ACTION_MAIN.equals(intent.getAction())) {
            return;
        }

        if (startPage == StartPage.SEARCH) {
            Activity mActivity = Utils.getActivity();
            if (mActivity != null) {
                Logger.printDebug(() -> "Changing intent action to: " + startPage.name());
                ExtendedUtils.setSearchIntent(mActivity, intent);
            }
        }
    }
}