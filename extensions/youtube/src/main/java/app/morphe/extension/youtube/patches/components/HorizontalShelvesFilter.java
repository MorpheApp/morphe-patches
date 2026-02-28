/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public class HorizontalShelvesFilter extends Filter {
    private final ByteArrayFilterGroupList descriptionBuffers = new ByteArrayFilterGroupList();
    private final ByteArrayFilterGroupList generalBuffers = new ByteArrayFilterGroupList();

    public HorizontalShelvesFilter() {
        StringFilterGroup horizontalShelves = new StringFilterGroup(null, "horizontal_shelf.e");
        addPathCallbacks(horizontalShelves);

        descriptionBuffers.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_ATTRIBUTES_SECTION,
                        // May no longer work on v20.31+, even though the component is still there.
                        "cell_video_attribute"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_FEATURED_PLACES_SECTION,
                        "yt_fill_experimental_star",
                        "yt_fill_star"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_GAMING_SECTION,
                        "yt_outline_experimental_gaming",
                        "yt_outline_gaming"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_MUSIC_SECTION,
                        "yt_outline_experimental_audio",
                        "yt_outline_audio"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_QUIZZES_SECTION,
                        "post_base_wrapper_slim"
                )
        );

        generalBuffers.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_PLAYABLES,
                        "FEmini_app_destination"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_CREATOR_STORE_SHELF,
                        "shopping_item_card_list"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_TICKET_SHELF,
                        "ticket_item.e"
                )
        );
    }

    private boolean hideShelves() {
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            return true;
        }
        if (NavigationBar.isSearchBarActive()) {
            return true;
        }
        return NavigationButton.getSelectedNavigationButton() != NavigationButton.LIBRARY;
    }

    @Override
    boolean isFiltered(String identifier, String accessibility, String path, byte[] buffer,
                       StringFilterGroup matchedGroup, FilterContentType contentType, int contentIndex) {
        if (contentIndex != 0) {
            return false;
        }
        if (generalBuffers.check(buffer).isFiltered()) {
            return true;
        }
        if (EngagementPanel.isDescription()) {
            PlayerType playerType = PlayerType.getCurrent();
            if (!playerType.isNoneOrHidden() && !playerType.isMaximizedOrFullscreen()) {
                return false;
            }
            return descriptionBuffers.check(buffer).isFiltered();
        }
        return hideShelves() && Settings.HIDE_HORIZONTAL_SHELVES.get();
    }
}
