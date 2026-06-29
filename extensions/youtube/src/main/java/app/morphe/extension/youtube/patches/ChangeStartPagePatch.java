/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.BaseChangeStartPagePatch;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class ChangeStartPagePatch extends BaseChangeStartPagePatch {

    public enum StartPage {
        /**
         * Unmodified type, and same as un-patched.
         */
        DEFAULT("", null),

        /**
         * BrowseId.
         */
        ALL_SUBSCRIPTIONS("FEchannels", TRUE),
        BROWSE("FEguide_builder", TRUE),
        HISTORY("FEhistory", TRUE),
        LIBRARY("FElibrary", TRUE),
        MOVIE("FEstorefront", TRUE),
        NOTIFICATIONS("FEactivity", TRUE),
        PLAYLISTS("FEplaylist_aggregation", TRUE),
        SUBSCRIPTIONS("FEsubscriptions", TRUE),
        YOUR_CLIPS("FEclips", TRUE),

        /**
         * Channel ID, this can be used as a browseId.
         */
        COURSES("UCtFRv9O2AHqOZjjynzrv-xg", TRUE),
        FASHION("UCrpQ4p1Ql_hG8rKXIKM1MOQ", TRUE),
        GAMING("UCOpNcN46UbXVtpKMrmU4Abg", TRUE),
        LIVE("UC4R8DWoMoI7CAwX8_LjQHig", TRUE),
        MUSIC("UC-9-kyTW8ZkZNDHQJ6FgpwQ", TRUE),
        NEWS("UCYfdidRxbB8Qhf0Nx7ioOYw", TRUE),
        SHOPPING("UCkYQyvc_i9hXEo4xic9Hh2g", TRUE),
        SPORTS("UCEgdi0XIXXZ-qJOFPf4JSKw", TRUE),
        VIRTUAL_REALITY("UCzuqhhs6NWbgTzMuM09WKDQ", TRUE),

        /**
         * Playlist ID, this can be used as a browseId.
         */
        LIKED_VIDEO("VLLL", TRUE),
        WATCH_LATER("VLWL", TRUE),

        /**
         * Intent action.
         */
        SEARCH("com.google.android.youtube.action.open.search", FALSE),
        SHORTS("com.google.android.youtube.action.open.shorts", FALSE);

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

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        private boolean isIntentAction() {
            return FALSE.equals(isBrowseId);
        }
    }

    /**
     * Intent action when YouTube is cold started from the launcher.
     * <p>
     * If you don't check this, the hooking will also apply in the following cases:
     * Case 1. The user clicked Shorts button on the YouTube shortcut.
     * Case 2. The user clicked Shorts button on the YouTube widget.
     * In this case, instead of opening Shorts, the start page specified by the user is opened.
     */
    private static final StartPage START_PAGE = Settings.CHANGE_START_PAGE.get();

    public static String overrideBrowseId(@NonNull String original) {
        return processBrowseId(original, START_PAGE.isBrowseId(), START_PAGE.id, START_PAGE.id);
    }

    public static void overrideIntentAction(@NonNull Intent intent) {
        if (!START_PAGE.isIntentAction()) {
            return;
        }

        if (!ACTION_MAIN.equals(intent.getAction())) {
            Logger.printDebug(() -> "Ignore override intent action" +
                    " as the current activity is not the entry point of the application");
            return;
        }

        String intentAction = START_PAGE.id;
        Logger.printDebug(() -> "Changing intent action to: " + intentAction);
        intent.setAction(intentAction);
    }
}
