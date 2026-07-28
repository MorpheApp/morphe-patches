/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroupList;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class ExploreMenuFilter extends Filter {

    private final StringFilterGroup divider;
    private final StringFilterGroup exploreMenu;
    private final ByteArrayFilterGroupList exploreMenuGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup incognitoExploreButton;
    private final ByteArrayFilterGroup incognitoExploreButtonBuffer;

    public ExploreMenuFilter() {
        divider = new StringFilterGroup(
                null,
                "|divider.e"
        );

        exploreMenu = new StringFilterGroup(
                null,
                "|ContainerType|CollectionType|CellType|"
        );

        exploreMenuGroupList.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_SHOPPING_MENU,
                        "yt_outline_bag",
                        "yt_outline_experimental_bag"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_MUSIC_MENU,
                        "yt_outline_audio",
                        "yt_outline_experimental_audio"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_MOVIES_MENU,
                        "yt_outline_clapperboard",
                        "yt_outline_experimental_clapperboard"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_HYPE_MENU,
                        "yt_outline_star_shooting",
                        "yt_outline_experimental_hype"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_LIVE_MENU,
                        "yt_outline_radar_live",
                        "yt_outline_experimental_live"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_GAMING_MENU,
                        "yt_outline_gaming",
                        "yt_outline_experimental_gaming"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_NEWS_MENU,
                        "yt_outline_news",
                        "yt_outline_experimental_news"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_SPORTS_MENU,
                        "yt_outline_trophy",
                        "yt_outline_experimental_trophy"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_COURSES_MENU,
                        "yt_outline_creator_academy",
                        "yt_outline_experimental_graduation_cap"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_FASHION_MENU,
                        "yt_outline_fashion",
                        "yt_outline_experimental_fashion"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_PODCASTS_MENU,
                        "yt_outline_podcast",
                        "yt_outline_experimental_podcast"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_PLAYABLES_MENU,
                        "yt_outline_tic_tac_toe",
                        "yt_outline_experimental_playables"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_MEMBERSHIPS_MENU,
                        "yt_fill_memberships",
                        "yt_fill_experimental_star_circle"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_YOUTUBE_PREMIUM_MENU,
                        "product_logo_youtube"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_YOUTUBE_STUDIO_MENU,
                        "product_logo_youtube_studio"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_YOUTUBE_MUSIC_MENU,
                        "product_logo_youtube_music"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_YOUTUBE_KIDS_MENU,
                        "product_logo_youtube_kids"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_YOUTUBE_CREATE_MENU,
                        "product_logo_youtube_create"
                )
        );

        incognitoExploreButton = new StringFilterGroup(
                Settings.HIDE_EXPLORE_BUTTON,
                "|CellType|ContainerType|ContainerType|ContainerType|"
        );

        incognitoExploreButtonBuffer = new ByteArrayFilterGroup(
                null,
                "yt_outline_compass",
                "yt_outline_experimental_compass"
        );

        final var moreDrawerButton = new StringFilterGroup(
                Settings.HIDE_EXPLORE_BUTTON,
                "more_drawer_button.e"
        );

        final var privacyToS = new StringFilterGroup(
                Settings.HIDE_PRIVACY_TOS_FOOTER,
                "privacy_tos.e"
        );

        addPathCallbacks(
                divider,
                exploreMenu,
                incognitoExploreButton,
                moreDrawerButton,
                privacyToS
        );
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              String path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              Filter.FilterContentType contentType,
                              int contentIndex) {

        if (matchedGroup == divider) {
            return path.contains("more_drawer.e");
        }

        if (matchedGroup == exploreMenu) {
            return path.startsWith("more_drawer.e") && exploreMenuGroupList.check(buffer).isFiltered();
        }

        if (matchedGroup == incognitoExploreButton) {
            return path.startsWith("search_bar_entry_point.e") && incognitoExploreButtonBuffer.check(buffer).isFiltered();
        }

        return true;
    }
}
