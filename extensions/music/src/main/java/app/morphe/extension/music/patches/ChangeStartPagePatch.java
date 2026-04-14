/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static java.lang.Boolean.TRUE;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

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

    private static final String SETTINGS_CLASS = "com.google.android.apps.youtube.music.settings.SettingsCompatActivity";
    private static final String SETTINGS_ATTRIBUTION_FRAGMENT_KEY = ":android:show_fragment";
    private static final String SETTINGS_ATTRIBUTION_FRAGMENT_VALUE = "com.google.android.apps.youtube.music.settings.fragment.SettingsHeadersFragment";
    private static final String SETTINGS_ATTRIBUTION_HEADER_KEY = ":android:no_headers";
    private static final int SETTINGS_ATTRIBUTION_HEADER_VALUE = 1;

    private static final String SHORTCUT_ACTION = "com.google.android.youtube.music.action.shortcut";
    private static final String SHORTCUT_CLASS = "com.google.android.apps.youtube.music.activities.InternalMusicActivity";
    private static final String SHORTCUT_TYPE = "com.google.android.youtube.music.action.shortcut_type";
    private static final String SHORTCUT_ID_SEARCH = "Eh4IBRDTnQEYmgMiEwiZn+H0r5WLAxVV5OcDHcHRBmPqpd25AQA=";
    private static final int SHORTCUT_TYPE_SEARCH = 1;

    private static void openSearch() {
        Activity mActivity = Utils.getActivity();
        if (mActivity == null) {
            return;
        }
        Intent intent = new Intent();
        setSearchIntent(mActivity, intent);
        mActivity.startActivity(intent);
    }

    private static void openSetting() {
        Activity mActivity = Utils.getActivity();
        if (mActivity == null) {
            return;
        }
        Intent intent = new Intent();
        intent.setPackage(mActivity.getPackageName());
        intent.setClassName(mActivity, SETTINGS_CLASS);
        intent.putExtra(SETTINGS_ATTRIBUTION_FRAGMENT_KEY, SETTINGS_ATTRIBUTION_FRAGMENT_VALUE);
        intent.putExtra(SETTINGS_ATTRIBUTION_HEADER_KEY, SETTINGS_ATTRIBUTION_HEADER_VALUE);
        mActivity.startActivity(intent);
    }

    private static void setSearchIntent(Activity mActivity, Intent intent) {
        intent.setAction(SHORTCUT_ACTION);
        intent.setClassName(mActivity, SHORTCUT_CLASS);
        intent.setPackage(mActivity.getPackageName());
        intent.putExtra(SHORTCUT_TYPE, SHORTCUT_TYPE_SEARCH);
        intent.putExtra(SHORTCUT_ACTION, SHORTCUT_ID_SEARCH);
    }

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
            setSearchIntent(activity, searchIntent);
            activity.startActivity(searchIntent);
        }
    }
}