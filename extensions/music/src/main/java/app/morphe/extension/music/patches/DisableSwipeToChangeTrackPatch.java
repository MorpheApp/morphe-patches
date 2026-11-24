/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4982
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.view.View;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ResourceUtils;

@SuppressWarnings("unused")
public class DisableSwipeToChangeTrackPatch {

    /**
     * Ids of the pagers that change the track when swiped, or 0 if not looked up yet.
     */
    private static int playerViewPagerId;
    private static int miniplayerViewPagerId;

    /**
     * Injection point. Called when the app turns the swipe of a track pager on or off.
     *
     * @param pager   The pager of the player artwork or of the miniplayer.
     * @param enabled If the app turns the swipe on.
     * @return If the swipe is turned on.
     */
    public static boolean isUserInputEnabled(View pager, boolean enabled) {
        if (!enabled) return false;

        if (playerViewPagerId == 0) {
            playerViewPagerId = ResourceUtils.getIdIdentifier("player_view_pager");
            miniplayerViewPagerId = ResourceUtils.getIdIdentifier("mini_player_view_pager");
        }

        final int id = pager.getId();
        if (id == playerViewPagerId) {
            return !Settings.DISABLE_PLAYER_SWIPE_TO_CHANGE_TRACK.get();
        }
        if (id == miniplayerViewPagerId) {
            return !Settings.DISABLE_MINIPLAYER_SWIPE_TO_CHANGE_TRACK.get();
        }

        return true;
    }
}
