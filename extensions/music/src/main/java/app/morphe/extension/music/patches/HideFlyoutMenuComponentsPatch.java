/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.settings.BooleanSetting;

@SuppressWarnings("unused")
public class HideFlyoutMenuComponentsPatch {

    /**
     * Injection point.
     */
    public static boolean hideComponents(@Nullable Enum<?> flyoutMenuEnum) {
        if (flyoutMenuEnum == null) return false;

        final String name = flyoutMenuEnum.name();
        for (FlyoutPanelComponent component : FlyoutPanelComponent.values()) {
            if (component.name().equals(name) && component.setting.get()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Injection point.
     * <p>
     * Legacy hook for pre-Litho builds where like/dislike is a native child of
     * {@code end_buttons_container}. Newer builds render the buttons via Litho and
     * leave this container empty (see {@code PlayerFlyoutMenuComponentsFilter}); the
     * two hooks share {@link Settings#HIDE_FLYOUT_MENU_LIKE_DISLIKE} and are harmless
     * when both fire.
     */
    public static void hideLikeDislikeContainer(View view) {
        if (Settings.HIDE_FLYOUT_MENU_LIKE_DISLIKE.get()
                && view.getParent() instanceof ViewGroup viewGroup) {
            viewGroup.removeView(view);
        }
    }

    // Enum constant name matches the YT Music menu icon identifier at runtime.
    private enum FlyoutPanelComponent {
        ADD_CIRCLE(Settings.HIDE_FLYOUT_MENU_ADD_TO_LISTEN_LATER),
        ADD_CIRCLE_OUTLINE(Settings.HIDE_FLYOUT_MENU_ADD_TO_LISTEN_LATER),
        ADD_TO_PLAYLIST(Settings.HIDE_FLYOUT_MENU_SAVE_TO_PLAYLIST),
        ADD_TO_WATCH_LATER(Settings.HIDE_FLYOUT_MENU_ADD_TO_LISTEN_LATER),
        ALBUM(Settings.HIDE_FLYOUT_MENU_GO_TO_ALBUM),
        ARTIST(Settings.HIDE_FLYOUT_MENU_GO_TO_ARTIST),
        BOOKMARK(Settings.HIDE_FLYOUT_MENU_REMOVE_FROM_LIBRARY),
        BOOKMARK_BORDER(Settings.HIDE_FLYOUT_MENU_SAVE_EPISODE_FOR_LATER_SAVE_TO_LIBRARY),
        BROADCAST(Settings.HIDE_FLYOUT_MENU_GO_TO_PODCAST),
        CAPTIONS(Settings.HIDE_FLYOUT_MENU_CAPTIONS),
        CHECK(Settings.HIDE_FLYOUT_MENU_MARK_EPISODE_AS_PLAYED),
        DELETE(Settings.HIDE_FLYOUT_MENU_DELETE_PLAYLIST),
        DISMISS_QUEUE(Settings.HIDE_FLYOUT_MENU_DISMISS_QUEUE),
        EDIT(Settings.HIDE_FLYOUT_MENU_EDIT_PLAYLIST),
        FLAG(Settings.HIDE_FLYOUT_MENU_REPORT),
        HELP_OUTLINE(Settings.HIDE_FLYOUT_MENU_HELP),
        HIDE(Settings.HIDE_FLYOUT_MENU_NOT_INTERESTED),
        INFO(Settings.HIDE_FLYOUT_MENU_GO_TO_EPISODE),
        KEEP(Settings.HIDE_FLYOUT_MENU_PIN_TO_SPEED_DIAL),
        KEEP_OFF(Settings.HIDE_FLYOUT_MENU_UNPIN_FROM_SPEED_DIAL),
        LIBRARY_ADD(Settings.HIDE_FLYOUT_MENU_SAVE_EPISODE_FOR_LATER_SAVE_TO_LIBRARY),
        LIBRARY_REMOVE(Settings.HIDE_FLYOUT_MENU_REMOVE_FROM_LIBRARY),
        MIX(Settings.HIDE_FLYOUT_MENU_START_RADIO),
        MOON_Z(Settings.HIDE_FLYOUT_MENU_SLEEP_TIMER),
        OFFLINE_DOWNLOAD(Settings.HIDE_FLYOUT_MENU_DOWNLOAD),
        OFFLINE_DOWNLOAD_CAIRO(Settings.HIDE_FLYOUT_MENU_DOWNLOAD),
        PEOPLE_GROUP(Settings.HIDE_FLYOUT_MENU_VIEW_SONG_CREDIT),
        PERSON_CIRCLE_SLASH(Settings.HIDE_FLYOUT_MENU_DONT_RECOMMEND_ARTIST),
        PIN_OFF_OUTLINE(Settings.HIDE_FLYOUT_MENU_UNPIN_FROM_SPEED_DIAL),
        PIN_OUTLINE(Settings.HIDE_FLYOUT_MENU_PIN_TO_SPEED_DIAL),
        PLANNER_REVIEW(Settings.HIDE_FLYOUT_MENU_STATS_FOR_NERDS),
        PODCAST_CHECK(Settings.HIDE_FLYOUT_MENU_MARK_EPISODE_AS_PLAYED),
        QUEUE_MUSIC(Settings.HIDE_FLYOUT_MENU_ADD_TO_QUEUE),
        QUEUE_PLAY_NEXT(Settings.HIDE_FLYOUT_MENU_PLAY_NEXT),
        REMOVE_FROM_PLAYLIST(Settings.HIDE_FLYOUT_MENU_REMOVE_FROM_PLAYLIST),
        SETTINGS_MATERIAL(Settings.HIDE_FLYOUT_MENU_QUALITY),
        SHARE(Settings.HIDE_FLYOUT_MENU_SHARE),
        SHUFFLE(Settings.HIDE_FLYOUT_MENU_SHUFFLE_PLAY),
        SUBSCRIBE(Settings.HIDE_FLYOUT_MENU_SUBSCRIBE),
        WATCH_LATER(Settings.HIDE_FLYOUT_MENU_ADD_TO_LISTEN_LATER),
        WATCH_LATER_CAIRO(Settings.HIDE_FLYOUT_MENU_ADD_TO_LISTEN_LATER),
        WATCH_LATER_FILLED(Settings.HIDE_FLYOUT_MENU_ADD_TO_LISTEN_LATER);

        private final BooleanSetting setting;

        FlyoutPanelComponent(BooleanSetting setting) {
            this.setting = setting;
        }
    }
}
