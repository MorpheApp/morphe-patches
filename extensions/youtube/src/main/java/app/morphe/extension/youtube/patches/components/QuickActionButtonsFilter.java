/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ConversionContext.ContextInterface;

@SuppressWarnings("unused")
public final class QuickActionButtonsFilter extends Filter {
    private static final String QUICK_ACTIONS_PATH = "quick_actions.e";

    private final StringFilterGroup quickActions;
    private final StringFilterGroup buttonFilterPath;
    private final ByteArrayFilterGroupList bufferButtonsGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup playlistFilterPath;
    private final ByteArrayFilterGroupList bufferPlaylistGroupList = new ByteArrayFilterGroupList();

    public QuickActionButtonsFilter() {
        quickActions = new StringFilterGroup(
                Settings.HIDE_QUICK_ACTIONS,
                QUICK_ACTIONS_PATH
        );

        addIdentifierCallbacks(quickActions);

        buttonFilterPath = new StringFilterGroup(
                null,
                "|ContainerType|button.e"
        );

        playlistFilterPath = new StringFilterGroup(
                null,
                "|fullscreen_video_action_button.e"
        );

        addPathCallbacks(
                new StringFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_LIKE_BUTTON,
                        "|like_button"
                ),
                new StringFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_DISLIKE_BUTTON,
                        "|dislike_button"
                ),
                new StringFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_SAVE_BUTTON,
                        "|save_to_playlist_button"
                ),
                new StringFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_MORE_BUTTON,
                        "|overflow_menu_button"
                ),
                new StringFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_MORE_VIDEOS_BUTTON,
                        "|fullscreen_related_videos"
                ),
                buttonFilterPath,
                playlistFilterPath
        );

        bufferButtonsGroupList.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_ASK_BUTTON,
                        "yt_fill_experimental_spark",
                        "yt_fill_spark"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_COMMENTS_BUTTON,
                        "yt_outline_experimental_text_bubble",
                        "yt_outline_message_bubble"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_LIVE_CHAT_BUTTON,
                        "yt_outline_experimental_bubble_stack",
                        "yt_outline_message_bubble_overlap"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_SHARE_BUTTON,
                        "yt_outline_experimental_share",
                        "yt_outline_share"
                )
        );

        bufferPlaylistGroupList.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_MIX_BUTTON,
                        "yt_outline_experimental_mix",
                        "yt_outline_youtube_mix"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_QUICK_ACTIONS_PLAYLIST_BUTTON,
                        "yt_outline_experimental_playlist",
                        "yt_outline_list_play_arrow"
                )
        );
    }

    /**
     * Checks if all child elements of the Quick Actions menu are set to be hidden.
     * If they are, we can just hide the parent container entirely.
     */
    private boolean isEveryFilterGroupEnabled() {
        for (StringFilterGroup group : pathCallbacks) {
            if (!group.isEnabled()) return false;
        }

        for (ByteArrayFilterGroup group : bufferButtonsGroupList) {
            if (!group.isEnabled()) return false;
        }

        for (ByteArrayFilterGroup group : bufferPlaylistGroupList) {
            if (!group.isEnabled()) return false;
        }

        return true;
    }

    @Override
    boolean isFiltered(ContextInterface contextInterface,
                       String identifier,
                       String accessibility,
                       String path,
                       byte[] buffer,
                       StringFilterGroup matchedGroup,
                       FilterContentType contentType,
                       int contentIndex) {

        if (!path.startsWith(QUICK_ACTIONS_PATH)) {
            return false;
        }

        if (matchedGroup == quickActions && !isEveryFilterGroupEnabled()) {
            return false;
        }

        if (matchedGroup == buttonFilterPath) {
            return bufferButtonsGroupList.check(buffer).isFiltered();
        }

        if (matchedGroup == playlistFilterPath) {
            if (path.contains("overflow_menu_button")) {
                return false;
            }
            return bufferPlaylistGroupList.check(buffer).isFiltered();
        }

        return true;
    }
}