/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.music.patches;

import static java.lang.Boolean.TRUE;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
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
        PLAYLISTS("FEmusic_liked_playlists", TRUE),
        PODCASTS("FEmusic_non_music_audio", TRUE),
        SUBSCRIPTIONS("FEmusic_library_corpus_artists", TRUE),
        EPISODES_FOR_LATER("VLSE", TRUE),
        LIKED_MUSIC("VLLM", TRUE),
        SEARCH("", false);

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

    public static void overrideIntentActionOnCreate(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) return;

        StartPage startPage = Settings.CHANGE_START_PAGE.get();
        if (startPage != StartPage.SEARCH) return;

        Intent originalIntent = activity.getIntent();
        if (originalIntent == null) return;

        if (ACTION_MAIN.equals(originalIntent.getAction())) {
            Logger.printDebug(() -> "Cold start: Firing search activity directly");
            Intent searchIntent = new Intent();
            ExtendedUtils.setSearchIntent(activity, searchIntent);
            activity.startActivity(searchIntent);
        }
    }
}