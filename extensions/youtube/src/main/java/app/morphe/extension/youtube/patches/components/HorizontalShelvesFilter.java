/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import java.util.Collections;
import java.util.Set;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroupList;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.patches.LayoutReloadObserverPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public final class HorizontalShelvesFilter extends Filter {
    /**
     * Identifiers of the shelves hidden outside the player.
     * <p>
     * The feed behind the player is also reloaded while the player is open,
     * such as when entering or exiting fullscreen. These shelves are not under the player,
     * and must stay hidden, otherwise they show again until the feed is scrolled.
     */
    private static final Set<String> hiddenShelfIdentifiers = Collections.synchronizedSet(
            Collections.newSetFromMap(Utils.createSizeRestrictedMap(100)));

    /**
     * Browse id of the Movies page of the Explore menu.
     */
    private static final String MOVIES_PAGE_BROWSE_ID = "FEstorefront";

    /**
     * Pages of the Explore menu, other than the pages ending with {@link #EXPLORE_PAGE_BROWSE_ID_SUFFIX},
     * such as Podcasts. Music, Live, Gaming, Sports, News, Fashion &amp; beauty and Learning are
     * channels of YouTube.
     */
    private static final Set<String> EXPLORE_PAGE_BROWSE_IDS = Set.of(
            MOVIES_PAGE_BROWSE_ID,
            "FEchannel_memberships",
            "UC-9-kyTW8ZkZNDHQJ6FgpwQ",
            "UC4R8DWoMoI7CAwX8_LjQHig",
            "UCOpNcN46UbXVtpKMrmU4Abg",
            "UCEgdi0XIXXZ-qJOFPf4JSKw",
            "UCYfdidRxbB8Qhf0Nx7ioOYw",
            "UCrpQ4p1Ql_hG8rKXIKM1MOQ",
            "UCtFRv9O2AHqOZjjynzrv-xg"
    );

    private static final String EXPLORE_PAGE_BROWSE_ID_SUFFIX = "_destination";

    private final ByteArrayFilterGroupList descriptionBuffers = new ByteArrayFilterGroupList();
    private final ByteArrayFilterGroupList generalBuffers = new ByteArrayFilterGroupList();
    private final ByteArrayFilterGroup moviesSection;

    public HorizontalShelvesFilter() {
        StringFilterGroup horizontalShelves = new StringFilterGroup(
                null,
                "horizontal_shelf.e"
        );

        addPathCallbacks(horizontalShelves);

        moviesSection = new ByteArrayFilterGroup(
                Settings.HIDE_MOVIES_SECTION,
                "movie_card.e"
        );

        descriptionBuffers.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_ATTRIBUTES_SECTION,
                        "cell_video_attribute"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_QUIZZES_SECTION,
                        "post_base_wrapper_slim"
                )
        );

        generalBuffers.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_CREATOR_STORE_SHELF,
                        "shopping_item_card_list"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_HISTORY_SHELF,
                        // Browse id of the History page, opened by the shelf header.
                        "FEhistory"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_PLAYABLES,
                        "FEmini_app_destination"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_TICKET_SHELF,
                        "ticket_item.e"
                )
        );
    }

    private boolean hideShelves(ContextInterface contextInterface, String identifier) {
        if (!Settings.HIDE_HORIZONTAL_SHELVES.get()) {
            return false;
        }
        if (isPlayerOrDescription()) {
            return identifier != null && hiddenShelfIdentifiers.contains(identifier);
        }
        // The pages of the Explore menu show their videos in horizontal shelves, so hiding the shelves
        // left them empty (https://github.com/MorpheApp/morphe-patches/issues/1362).
        final String browseId = LayoutComponentsFilter.browseId;
        final boolean hide = !(EXPLORE_PAGE_BROWSE_IDS.contains(browseId)
                || browseId.endsWith(EXPLORE_PAGE_BROWSE_ID_SUFFIX))
                && (contextInterface.isHomeFeedOrRelatedVideo()
                || NavigationBar.isSearchBarActive()
                || NavigationBar.isBackButtonVisible()
                || NavigationButton.getSelectedNavigationButton() != NavigationButton.LIBRARY);
        if (identifier != null) {
            // Shelves of different feeds can have the same identifier, such as the History shelf of
            //  the 'You' tab, so the last feed where the shelf was loaded decides.
            if (hide) {
                hiddenShelfIdentifiers.add(identifier);
            } else {
                hiddenShelfIdentifiers.remove(identifier);
            }
        }
        return hide;
    }

    private boolean isPlayerOrDescription() {
        return EngagementPanel.isDescription()
                || PlayerType.getCurrent().isMaximizedOrFullscreen()
                || LayoutReloadObserverPatch.isActionBarVisible.get()
                || ShortsPlayerState.isOpen();
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              CharSequence path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        if (contentIndex != 0) {
            return false;
        }
        if (generalBuffers.check(buffer).isFiltered()) {
            return true;
        }
        // The movies of the Movies page are its content.
        if (!MOVIES_PAGE_BROWSE_ID.equals(LayoutComponentsFilter.browseId)
                && moviesSection.check(buffer).isFiltered()) {
            return true;
        }
        if (descriptionBuffers.check(buffer).isFiltered()) {
            if (isPlayerOrDescription()) {
                return true;
            }
        }
        return hideShelves(contextInterface, identifier);
    }
}
